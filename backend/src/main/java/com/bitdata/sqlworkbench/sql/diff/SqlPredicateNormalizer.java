package com.bitdata.sqlworkbench.sql.diff;

import com.bitdata.sqlworkbench.sql.DialectParserConfigs;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.sql.SqlWindow;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.SqlKind;

import java.util.ArrayList;
import java.util.List;

/**
 * P1 谓词规范化（ADR-0005 §3.5 / spec §3.5 P1 范围）。
 *
 * <p>在序列化为 {@link NodeToken} 之前对 AST 做局部 rewrite，使语义等价的
 * 谓词形态产生相同的 canonical form。范围：
 * <ul>
 *   <li><b>谓词方向归一</b>：{@code a < b} ≡ {@code b > a}、
 *       {@code a <= b} ≡ {@code b >= a}。规则：若右侧规范文本字典序 &lt;
 *       左侧，则交换操作数并翻转比较符。</li>
 *   <li><b>De Morgan</b>：{@code NOT (a OR b)} ≡ {@code NOT a AND NOT b}、
 *       {@code NOT (a AND b)} ≡ {@code NOT a OR NOT b}。规则：把 NOT 推到
 *       AND/OR 子节点上，保持 NOT 不在子节点之上（更利于 LCS 匹配）。</li>
 *   <li><b>&lt;&gt; ↔ !=</b>：把 {@code <>} 重写为 {@code !=}，让两种写法
 *       在序列化阶段产生同一 {@code EXPR_NOT_EQUALS} 节点类型。</li>
 * </ul>
 *
 * <p><b>下降集不变式</b>（2026-10-02 收口）：本方法必须下降到 {@link AstSerializer#walk}
 * 会进入的<b>每一个</b>节点类型 —— {@code SqlNodeList} / {@code SqlSelect}（含
 * selectList / from / where / group / having / windowList 的各 item / qualify /
 * orderList / offset / fetch）/ {@code SqlOrderBy} / {@code SqlJoin} / {@code SqlWith} /
 * {@code SqlWithItem} / {@code SqlBasicCall} / {@code SqlWindow}。判据不是「有没有
 * 列在清单里」，而是「序列化是否会让该子树的内容进入身份」：只要该节点的内容会落到
 * token 的 canonicalText 里，改写就必须先于序列化到达它，否则「P1 等价改写」落在未下降的
 * 子树里时，两种等价写法会算出 <b>不同</b> fragment id（ADR-0001 身份失真）。
 * {@code SqlWindow} 尤其如此：序列化侧把整个窗口定义体交给 token 文本承载（具名
 * {@code WINDOW_DEF} / 内联 {@code OP_OVER}），文本即内容，故其 orderList /
 * partitionList 承重。
 *
 * <p>P2（schema 重写）/ P3（schema 依赖）明确不在范围。
 */
public final class SqlPredicateNormalizer {

    /**
     * {@code SqlJoin} 操作数列表中 ON 条件表达式的位置（probe 2026-10-02：6 个操作数 =
     * {@code [left, condType, joinType, right, condType, condition]}）。
     * Calcite 1.37 未暴露 {@code setCondition}，故按索引回写；该索引由
     * {@code SqlPredicateNormalizerTest$WrapperDescentEquivalence.joinConditionIsNormalized} 钉住。
     */
    private static final int JOIN_CONDITION_OPERAND = 5;

    private SqlPredicateNormalizer() {}

    /**
     * Public entry: parse + normalize. dialect 决定引号风格与大小写敏感性（P2-12d）。
     *
     * <p>注：canonicalize 链路对大小写折叠不敏感（Calcite unquotedCasing 先折叠），
     * caseSensitive 仅影响 parser 内部表示，不影响本包等价性判定。
     */
    public static SqlNode normalize(String sql, String dialect) throws SqlParseException {
        SqlParser.Config config = DialectParserConfigs.forDialect(dialect);
        SqlNode root = SqlParser.create(sql, config).parseQuery();
        return normalizeAst(root);
    }

    private static SqlNode normalizeAst(SqlNode node) {
        if (node == null) return null;
        if (node instanceof SqlNodeList list) {
            List<SqlNode> rewritten = new ArrayList<>(list.size());
            for (SqlNode item : list) rewritten.add(normalizeAst(item));
            return new SqlNodeList(rewritten, list.getParserPosition());
        }
        if (node instanceof org.apache.calcite.sql.SqlSelect select) {
            // Mutate in place (Calcite SqlNodes are mutable). 下降集必须与
            // AstSerializer.walk 对 SqlSelect 的递归集一致 —— 否则当「P1 等价改写」发生在
            // 未下降的子句内部时，两种等价写法会算出**不同** fragment id。
            // probe 2026-10-02：旧实现只下降 where / having，实测 selectList / from /
            // group / orderList 四处「等价改写⇒不同 id」。
            // windowList 的下降见下方（无 setWindowList，逐 item 原地归一）。
            if (select.getSelectList() != null) {
                select.setSelectList((SqlNodeList) normalizeAst(select.getSelectList()));
            }
            if (select.getFrom() != null) select.setFrom(normalizeAst(select.getFrom()));
            if (select.getWhere() != null) select.setWhere(normalizeAst(select.getWhere()));
            if (select.getGroup() != null) {
                select.setGroupBy((SqlNodeList) normalizeAst(select.getGroup()));
            }
            if (select.getHaving() != null) select.setHaving(normalizeAst(select.getHaving()));
            // 具名窗口定义（`WINDOW w AS (ORDER BY b < a)`）挂在 windowList 上，而
            // SqlSelect 未暴露 setWindowList —— 但每个 item 是 SqlWindow，其
            // setOrderList/setPartitionList 可原地改写，故只需**访问**每个 item，
            // 无需替换列表（与 setWhere/setHaving 的原地风格一致）。
            if (select.getWindowList() != null) {
                for (SqlNode item : select.getWindowList()) normalizeAst(item);
            }
            if (select.getQualify() != null) select.setQualify(normalizeAst(select.getQualify()));
            if (select.getOrderList() != null) {
                select.setOrderBy((SqlNodeList) normalizeAst(select.getOrderList()));
            }
            if (select.getOffset() != null) select.setOffset(normalizeAst(select.getOffset()));
            if (select.getFetch() != null) select.setFetch(normalizeAst(select.getFetch()));
            return select;
        }
        if (node instanceof SqlOrderBy orderBy) {
            // 顶层 ORDER BY / LIMIT / OFFSET / FETCH 查询的根（Calcite 1.37）。
            // 四个字段是 public final，无法原地改 → 重建同型节点。
            // 旧实现没有这条分支 ⇒ 这类查询内层的谓词从不归一
            // （probe 2026-10-02：`WHERE b<a ORDER BY a` 与 `WHERE a>b ORDER BY a` 不同 id）。
            return new SqlOrderBy(
                orderBy.getParserPosition(),
                normalizeAst(orderBy.query),
                (SqlNodeList) normalizeAst(orderBy.orderList),
                normalizeAst(orderBy.offset),
                normalizeAst(orderBy.fetch));
        }
        if (node instanceof SqlJoin join) {
            // JOIN 的 ON 条件不归一 ⇒ `ON x.b < y.c` 与 `ON y.c > x.b` 不同 id（probe 同上）。
            join.setLeft(normalizeAst(join.getLeft()));
            join.setRight(normalizeAst(join.getRight()));
            if (join.getCondition() != null) {
                join.setOperand(JOIN_CONDITION_OPERAND, normalizeAst(join.getCondition()));
            }
            return join;
        }
        if (node instanceof SqlWith with) {
            // withList / body 是 public 非 final 字段 → 原地改（与 setWhere/setHaving 同风格）。
            // 旧实现没有这条分支 ⇒ CTE 体与主查询体里的谓词从不归一。
            with.withList = (SqlNodeList) normalizeAst(with.withList);
            with.body = normalizeAst(with.body);
            return with;
        }
        if (node instanceof SqlWithItem withItem) {
            // name / columnList / recursive 不含谓词，只需下降 query（CTE 体）。
            if (withItem.query != null) withItem.query = normalizeAst(withItem.query);
            return withItem;
        }
        if (node instanceof SqlWindow window) {
            // 窗口定义体（具名 `WINDOW w AS (...)` 的 item，或内联 `OVER (...)` 的操作数）。
            // 序列化侧把整个定义体交给 token 文本承载 —— 具名走 WINDOW_DEF，内联走
            // OP_OVER，两者 canonicalText 都是该子树的 unparse 全文 —— 所以这里的
            // orderList / partitionList 是**承重**的：不下降则 `ORDER BY b < a` 与
            // `ORDER BY a > b` 算出不同 id（probe 2026-10-02：内联 f6a07b298514 vs
            // 4aa13fdbed15；具名 8b21775edbd7 vs f156c7752697）。这是「归一必须到达
            // 序列化会进入的每个节点」不变式的最后一处缺口 —— SqlWindow 是本任务给
            // 序列化新增的递归目标（WINDOW_DEF 分支），故同批收口。
            // 上下边界（lowerBound / upperBound）只有 ROWS/RANGE 的无谓词表达式，不下降。
            if (window.getPartitionList() != null) {
                window.setPartitionList((SqlNodeList) normalizeAst(window.getPartitionList()));
            }
            if (window.getOrderList() != null) {
                window.setOrderList((SqlNodeList) normalizeAst(window.getOrderList()));
            }
            return window;
        }

        if (node instanceof SqlBasicCall call) {
            SqlKind kind = call.getKind();

            // Apply recursively first (bottom-up).
            List<SqlNode> newOperands = new ArrayList<>(call.getOperandList().size());
            for (SqlNode op : call.getOperandList()) newOperands.add(normalizeAst(op));

            // P1: rewrite this node based on its kind.
            if (kind == SqlKind.LESS_THAN || kind == SqlKind.LESS_THAN_OR_EQUAL
                || kind == SqlKind.GREATER_THAN || kind == SqlKind.GREATER_THAN_OR_EQUAL) {
                return canonicalizeDirection(call, newOperands);
            }
            if (kind == SqlKind.NOT_EQUALS) {
                return rewriteNotEqual(call, newOperands);
            }
            if (kind == SqlKind.NOT) {
                return pushNotDown(call, newOperands);
            }

            // Default: rebuild with normalized operands.
            return rebuildCall(call, newOperands);
        }
        return node;
    }

    /**
     * P1 direction normalization. Two rules:
     * <ul>
     *   <li><b>Both sides are identifiers</b>: sort alphabetically; flip
     *       operator so the smaller one ends up on the left of LESS_THAN.
     *       `t.a < t.b` ≡ `t.b > t.a` → canonical `t.a < t.b`.</li>
     *   <li><b>Both sides are literals</b>: sort by value (lexicographic on
     *       canonical text).</li>
     *   <li><b>Mixed (identifier + literal)</b>: keep identifier on the
     *       LEFT (column-on-left convention). No swap. This makes `a &lt; 5`
     *       already canonical — swapping to `5 &gt; a` would lose the
     *       column-first convention.</li>
     * </ul>
     * After this, both `a &lt; 5` and `5 &gt; a` keep their original form,
     * but `a &lt; b` and `b &gt; a` both become `a &lt; b`.
     */
    private static SqlNode canonicalizeDirection(SqlBasicCall call, List<SqlNode> ops) {
        if (ops.size() != 2) return rebuildCall(call, ops);
        SqlNode left = ops.get(0);
        SqlNode right = ops.get(1);
        boolean leftIsIdent = left instanceof org.apache.calcite.sql.SqlIdentifier;
        boolean rightIsIdent = right instanceof org.apache.calcite.sql.SqlIdentifier;
        boolean leftIsLiteral = left instanceof org.apache.calcite.sql.SqlLiteral;
        boolean rightIsLiteral = right instanceof org.apache.calcite.sql.SqlLiteral;

        // Mixed: keep identifier on the left (column-first). No swap.
        if (leftIsIdent && rightIsLiteral) return rebuildCall(call, ops);
        if (leftIsLiteral && rightIsIdent) {
            // Identifier on right — flip to move it to the left.
            return flippedOp(call.getKind()).createCall(
                call.getParserPosition(), right, left);
        }

        // Both same kind: sort canonically.
        if (canonicalText(right).compareTo(canonicalText(left)) < 0) {
            return flippedOp(call.getKind()).createCall(
                call.getParserPosition(), right, left);
        }
        return rebuildCall(call, ops);
    }

    private static org.apache.calcite.sql.SqlOperator flippedOp(SqlKind kind) {
        return switch (kind) {
            case LESS_THAN             -> SqlStdOperatorTable.GREATER_THAN;
            case LESS_THAN_OR_EQUAL    -> SqlStdOperatorTable.GREATER_THAN_OR_EQUAL;
            case GREATER_THAN          -> SqlStdOperatorTable.LESS_THAN;
            case GREATER_THAN_OR_EQUAL -> SqlStdOperatorTable.LESS_THAN_OR_EQUAL;
            default                    -> SqlStdOperatorTable.EQUALS;
        };
    }

    /**
     * P1 rewrite: `<>` → `!=`. Both operators parse to SqlKind.NOT_EQUALS in
     * Calcite, so the AST kind is already the same; the difference is in the
     * unparsed text. The serialization step produces EXPR_NOT_EQUALS in both
     * cases — but we also rewrite here for symmetry with future text-based
     * comparators. The real benefit is in {@code canonicalizeSql}, where the
     * text "a<>b" and "a!=b" now produce identical canonical strings.
     */
    private static SqlNode rewriteNotEqual(SqlBasicCall call, List<SqlNode> ops) {
        return SqlStdOperatorTable.NOT_EQUALS.createCall(
            call.getParserPosition(), ops.toArray(new SqlNode[0]));
    }

    /**
     * P1 De Morgan: NOT (a OR b) → NOT a AND NOT b
     *              NOT (a AND b) → NOT a OR NOT b
     * If operand is already NOT, collapse: NOT NOT a → a.
     */
    private static SqlNode pushNotDown(SqlBasicCall call, List<SqlNode> ops) {
        if (ops.size() != 1) return rebuildCall(call, ops);
        SqlNode inner = ops.get(0);
        if (inner instanceof SqlBasicCall innerCall
            && (innerCall.getKind() == SqlKind.AND || innerCall.getKind() == SqlKind.OR)) {

            // Wrap each operand in NOT.
            List<SqlNode> negated = new ArrayList<>(innerCall.getOperandList().size());
            for (SqlNode op : innerCall.getOperandList()) {
                negated.add(SqlStdOperatorTable.NOT.createCall(
                    call.getParserPosition(), op));
            }
            // De Morgan flips AND ↔ OR.
            org.apache.calcite.sql.SqlOperator deMorganOp = innerCall.getKind() == SqlKind.AND
                ? SqlStdOperatorTable.OR
                : SqlStdOperatorTable.AND;
            return deMorganOp.createCall(
                call.getParserPosition(), negated.toArray(new SqlNode[0]));
        }
        // Collapse NOT NOT a → a
        if (inner instanceof SqlBasicCall innerCall
            && innerCall.getKind() == SqlKind.NOT
            && innerCall.getOperandList().size() == 1) {
            return innerCall.getOperandList().get(0);
        }
        return rebuildCall(call, ops);
    }

    private static SqlNode rebuildCall(SqlBasicCall call, List<SqlNode> ops) {
        return call.getOperator().createCall(
            call.getParserPosition(), ops.toArray(new SqlNode[0]));
    }

    private static String canonicalText(SqlNode node) {
        return AstSerializer.normalize(node.toSqlString(PostgresqlSqlDialect.DEFAULT).getSql());
    }
}
