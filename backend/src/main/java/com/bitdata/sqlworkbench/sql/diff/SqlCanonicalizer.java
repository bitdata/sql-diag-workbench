package com.bitdata.sqlworkbench.sql.diff;

import org.apache.calcite.sql.parser.SqlParseException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * P0 规范化器（ADR-0005）。
 *
 * <p>作用域：纯结构，无语义风险。
 * <ul>
 *   <li><b>AND/OR 子节点排序</b>：按 canonicalText 字典序，使等价换写 Δ = ∅。</li>
 *   <li><b>大小写 / 括号 / 空白</b>：依赖 Calcite toSqlString 在 caseSensitive=false 模式下的规范化输出。</li>
 * </ul>
 *
 * <p>算法：扫描序列，识别"连续 AND 块"（path 按**路径段**匹配 ".AND"）和"连续 OR 块"，
 * 按 canonicalText 字典序对每块单独排序。块外的 token 保持原序。
 *
 * <p>P1+（德摩根、谓词方向等）不在原型范围。
 */
public final class SqlCanonicalizer {

    private SqlCanonicalizer() {}

    /** 规范化结果 + 结构化降级信息。{@code unsupportedNodeTypes} 非空即降级。
     *  <p>注意：{@code unsupportedNodeTypes} 的元素是兜底 token 的 {@code canonicalText}
     *  形如 {@code <ClassSimpleName>#<digest>}（例如 {@code SqlUpdate#607b24d415a1}），
     *  <b>不是</b>裸节点类型名 —— 字段名容易读出相反的期待。 */
    public record CanonicalForm(String canonicalSql, List<String> unsupportedNodeTypes) {
        public boolean degraded() {
            return !unsupportedNodeTypes.isEmpty();
        }
    }

    /**
     * Public entry point for ADR-0001 fragment identity: canonicalize a raw
     * SQL string into a deterministic, normalized text form. Two SQLs that
     * are equivalent under P0+P1 normalization produce the same output.
     *
     * <p>Pipeline: {@code parse → P1 normalize(AST) → serialize → P0 canonicalize(tokens) → join}.
     */
    public static String canonicalizeSql(String sql, String dialect) throws SqlParseException {
        return canonicalizeSqlWithStatus(sql, dialect).canonicalSql();
    }

    /**
     * 与 {@link #canonicalizeSql} 同一管线，额外报告**结构化**降级：token 流里出现
     * UNKNOWN token 即说明有节点类型没被 {@link AstSerializer} 分派，此时算出来的身份
     * 落在兜底 token 上（内容摘要使其不再碰撞，但仍是「不支持」而不是「等价」）。
     *
     * <p>判据是 token 的 {@code nodeType()}（结构），不是对 canonical 串做子串匹配 ——
     * 合法 SQL 里可以合法地出现 UNKNOWN 这个名字。
     */
    public static CanonicalForm canonicalizeSqlWithStatus(String sql, String dialect)
            throws SqlParseException {
        org.apache.calcite.sql.SqlNode normalized = SqlPredicateNormalizer.normalize(sql, dialect);
        List<NodeToken> seq = AstSerializer.serializeNode(normalized);
        List<String> unsupported = seq.stream()
            .filter(t -> "UNKNOWN".equals(t.nodeType()))
            .map(NodeToken::canonicalText)
            .distinct()
            .toList();
        List<NodeToken> canon = canonicalize(seq);
        String joined = canon.stream()
            .map(t -> t.nodeType() + "@" + t.path() + ":" + t.canonicalText())
            .collect(Collectors.joining(" "));
        return new CanonicalForm(joined, unsupported);
    }

    public static List<NodeToken> canonicalize(List<NodeToken> sequence) {
        List<NodeToken> result = new ArrayList<>(sequence);
        sortCommutativeRuns(result, ".AND");
        sortCommutativeRuns(result, ".OR");
        renumberCommutativePaths(result, ".AND");
        renumberCommutativePaths(result, ".OR");
        return result;
    }

    /**
     * {@code marker}（形如 {@code ".OR"} / {@code ".AND"}）必须作为**完整路径段**出现才算命中。
     *
     * <p>不能用 {@code path().contains(marker)}：{@code ".ORDER_LIST"} 以 {@code ".OR"} 开头，
     * ORDER BY 项会被误判为 OR 块成员，先被按 canonicalText 排序、再被重编号进 {@code .OR.n}
     * （{@code renumberCommutativePaths} 整体替换 path，原父路径丢失）⇒
     * {@code ORDER BY a, b} 与 {@code ORDER BY b, a} 算出同一身份（ADR-0001 假等价）。
     *
     * <p>本迭代扫描结论：{@code AstSerializer} 发出的路径段中以 {@code OR} 开头的只有
     * {@code ORDER_LIST} 一个，故这是唯一误判站点（以 {@code AND} 开头的没有）。
     */
    private static boolean inCommutativeBlock(String path, String marker) {
        String segment = marker.substring(1); // ".OR" → "OR"
        for (String part : path.split("\\.")) {
            if (segment.equals(part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把序列中所有 path 按路径段匹配 {@code marker} 的连续 token 块按 canonicalText 排序。
     * 块边界：当前 token 不按路径段匹配 marker → 块结束。
     */
    private static void sortCommutativeRuns(List<NodeToken> seq, String marker) {
        int i = 0;
        while (i < seq.size()) {
            if (!inCommutativeBlock(seq.get(i).path(), marker)) {
                i++;
                continue;
            }
            // 找到块的起止
            int start = i;
            while (i < seq.size() && inCommutativeBlock(seq.get(i).path(), marker)) {
                i++;
            }
            int end = i;
            // 块内排序
            List<NodeToken> block = new ArrayList<>(seq.subList(start, end));
            block.sort(Comparator.comparing(NodeToken::canonicalText));
            for (int k = 0; k < block.size(); k++) {
                seq.set(start + k, block.get(k));
            }
        }
    }

    /**
     * 路径重新编号：把同一 AND/OR 块内的连续 token 的 path 归一为 {@code <marker>.<index>}。
     *
     * <p>原因：Calcite 把 N 元 AND 树解析为 (op1 AND op2) AND op3...，路径深度随操作数递增。
     * 排序后，"第 1 个操作数"在不同 SQL 中位于不同深度，无法用路径配对 DEL/ADD 成 MOD。
     * 按块内顺序重编号后，路径仅反映"在同一块内的位置"，跨 SQL 一致。
     */
    private static void renumberCommutativePaths(List<NodeToken> seq, String marker) {
        int i = 0;
        while (i < seq.size()) {
            if (!inCommutativeBlock(seq.get(i).path(), marker)) { i++; continue; }
            int start = i;
            while (i < seq.size() && inCommutativeBlock(seq.get(i).path(), marker)) i++;
            int end = i;
            for (int k = 0; k < (end - start); k++) {
                NodeToken t = seq.get(start + k);
                seq.set(start + k, new NodeToken(t.nodeType(), t.canonicalText(), marker + "." + k));
            }
        }
    }
}