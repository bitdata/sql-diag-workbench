package com.bitdata.sqlworkbench.sql.diff;

import com.bitdata.sqlworkbench.sql.DialectParserConfigs;

import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlSelectKeyword;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlWindow;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.SqlKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 把 Calcite SqlNode 序列化为 {@link NodeToken} 序列（ADR-0004）。
 *
 * <p>序列化规则：
 * <ul>
 *   <li><b>AND / OR</b>：不产生自身 token，仅遍历操作数（让 LCS 能识别"加一个 AND 子节点"）。</li>
 *   <li><b>比较/算术操作符</b>（{@code =, <, >, <=, >=, <>} 等）：合并为单个 token，
 *       canonicalText = 完整表达式文本。</li>
 *   <li><b>标识符/字面量</b>：单个 token。</li>
 *   <li><b>SqlOrderBy</b>：发 ORDER_BY token，先递归被包裹的 query，再遍历
 *       orderList / offset / fetch。Calcite 1.37 把 ORDER BY、LIMIT、OFFSET
 *       查询一律包成 SqlOrderBy，故这三类查询都走这条分支。</li>
 *   <li><b>SqlSelect</b>：发 SELECT token，遍历 SELECT_LIST / FROM / WHERE；另发
 *       SELECT_MODIFIER（canonicalText = DISTINCT 或 STREAM；ALL 是默认值，不发 token）、
 *       GROUP_BY、HAVING、WINDOW、QUALIFY、ORDER_BY 子句 token，并递归各子句内容
 *       （GROUP BY 项 / HAVING 谓词 / WINDOW 定义体 / QUALIFY 谓词 / ORDER BY 项 /
 *       offset / fetch）—— 这些子句参与身份，缺一则<b>不同查询算出同 id</b>。
 *       offset / fetch 自身不发 token，其操作数节点照常发 IDENT / LITERAL。</li>
 *   <li><b>SqlWindow</b>（具名窗口定义体，如 {@code WINDOW w AS (ORDER BY a)} 的 item）：
 *       合并为单个 WINDOW_DEF token，canonicalText = 定义体 unparse 全文；其内部不
 *       再遍历（与内联 {@code OVER (...)} 由 unparse 文本承载的做法一致）。</li>
 *   <li><b>其他 SqlBasicCall</b>（函数等）：合并为单个 token，canonicalText = 整个调用
 *       的 unparse 全文。两处例外仍会递归：<b>(1)</b> 操作数里含子查询
 *       （{@code SqlSelect} / {@code SqlWith}）且 operator 属 IN / EXISTS / 比较类时，
 *       逐个操作数递归 —— 否则子查询的内部结构丢失；<b>(2)</b> {@code AS} 且首操作数是
 *       子查询时，递归首操作数、别名另发 ALIAS token。</li>
 *   <li><b>未知节点</b>：发 UNKNOWN token 作为兜底（ADR-0005 诚实标注，暴露承重墙
 *       薄弱点），canonicalText = class 简单名 + 该节点内容摘要（SHA-256 前 12 位），
 *       使残余未知节点的身份仍可区分。</li>
 * </ul>
 */
public final class AstSerializer {

    private AstSerializer() {}

    public static List<NodeToken> serialize(String sql, String dialect) throws SqlParseException {
        // P2-12: parse with the dialect's quoting + case sensitivity (e.g. mysql
        // BACK_TICK) so reserved-word identifiers re-parse cleanly. The old
        // config() hardcoded withCaseSensitive(false) and used Calcite's default
        // DOUBLE_QUOTE, which silently mis-parsed mysql backtick identifiers.
        SqlParser.Config config = DialectParserConfigs.forDialect(dialect);
        SqlNode root = SqlParser.create(sql, config).parseQuery();
        return serializeNode(root);
    }

    /** Serialize an already-parsed (and optionally pre-normalized) SqlNode. */
    public static List<NodeToken> serializeNode(SqlNode root) {
        List<NodeToken> out = new ArrayList<>();
        walk(root, "root", out);
        return out;
    }

    private static void walk(SqlNode node, String path, List<NodeToken> out) {
        if (node == null) return;

        SqlKind kind = node.getKind();

        if (kind == SqlKind.AND || kind == SqlKind.OR) {
            // AND/OR 是结构性的：仅遍历子节点，不发 token
            // （让 LCS 把"加一个 AND 子条件"识别为 ADD，而不是 MOD 整个 AND 表达式）
            for (SqlNode operand : ((SqlBasicCall) node).getOperandList()) {
                walk(operand, path + "." + kind.name(), out);
            }
            return;
        }

        if (node instanceof SqlOrderBy orderBy) {
            // Calcite 1.37 把 ORDER BY / LIMIT / OFFSET 查询一律包成 SqlOrderBy
            // （recon 2026-10-02：LIMIT 5、OFFSET 3 与 ORDER BY a 同根类）。旧实现
            // 没有这条分支 ⇒ 整条查询坍缩成一个 UNKNOWN token，不同查询同 id。
            // 先递归被包裹的 query（内层结构因此可见），再走 ORDER BY 项与
            // offset/fetch 子句节点。
            out.add(new NodeToken("ORDER_BY", "ORDER BY", path));
            walk(orderBy.query, path + ".QUERY", out);
            if (orderBy.orderList != null) {
                for (SqlNode item : orderBy.orderList) {
                    walk(item, path + ".ORDER_LIST", out);
                }
            }
            if (orderBy.offset != null) walk(orderBy.offset, path + ".OFFSET", out);
            if (orderBy.fetch != null) walk(orderBy.fetch, path + ".FETCH", out);
            return;
        }

        if (node instanceof SqlSelect select) {
            out.add(new NodeToken("SELECT", "SELECT", path));
            if (select.getSelectList() != null) {
                for (SqlNode item : select.getSelectList()) {
                    walk(item, path + ".SELECT_LIST", out);
                }
            }
            if (select.getFrom() != null) walk(select.getFrom(), path + ".FROM", out);
            if (select.getWhere() != null) {
                out.add(new NodeToken("WHERE", "WHERE", path));
                walk(select.getWhere(), path + ".WHERE", out);
            }
            if (select.getFrom() != null) {
                // FROM 也带表名标识
                SqlNode from = select.getFrom();
                if (from instanceof SqlIdentifier id) {
                    out.add(new NodeToken("TABLE", id.toString(), path + ".FROM"));
                }
            }
            // DISTINCT 是语义修饰：`SELECT a` 与 `SELECT DISTINCT a` 是不同查询，
            // 但两者旧实现产出完全相同的 token 流（recon 2026-10-02 实测同 canonical）。
            // ALL 不在此列 —— 它是默认值，与省略等价，必须共享身份（ADR-0005）。
            if (select.isKeywordPresent(SqlSelectKeyword.DISTINCT)) {
                out.add(new NodeToken("SELECT_MODIFIER", "DISTINCT", path));
            }
            // STREAM 与 DISTINCT 同类（SqlSelectKeyword 仅这三值）：`SELECT STREAM a FROM t`
            // 与 `SELECT a FROM t` 是不同查询，旧实现同 id（probe 2026-10-02：均为
            // 1c607e78a4c96977）。共用 SELECT_MODIFIER，靠 canonicalText 区分 —— 同
            // SELECT_MODIFIER/DISTINCT 的做法。ALL 见上：默认值，不参与身份。
            if (select.isKeywordPresent(SqlSelectKeyword.STREAM)) {
                out.add(new NodeToken("SELECT_MODIFIER", "STREAM", path));
            }
            // 以下子句旧实现完全没走 ⇒ 它们**静默**不参与身份（连 UNKNOWN 都不产生，
            // 零-UNKNOWN 闸门查不出来）。recon 2026-10-02：`SELECT a FROM t` 与
            // `SELECT a FROM t GROUP BY a HAVING COUNT(*)>1` 同 id。
            if (select.getGroup() != null) {
                out.add(new NodeToken("GROUP_BY", "GROUP BY", path));
                for (SqlNode item : select.getGroup()) {
                    walk(item, path + ".GROUP_BY", out);
                }
            }
            if (select.getHaving() != null) {
                out.add(new NodeToken("HAVING", "HAVING", path));
                walk(select.getHaving(), path + ".HAVING", out);
            }
            // WINDOW（具名窗口定义）与 QUALIFY 旧实现完全没走（连 UNKNOWN 都不产生）。
            // recon 2026-10-02：`WINDOW w AS (ORDER BY a)` / `AS (ORDER BY b)` / 无 WINDOW
            // 三串同 id；`QUALIFY …(ORDER BY a)` / `(ORDER BY b)` 与裸查询同 id。
            // 注意 getWindowList() **永不为 null**（默认空 SqlNodeList，probe 2026-10-02）
            // —— 用 null 守卫会给每个 SELECT 都发一个 WINDOW token，故必须查 size()。
            if (select.getWindowList() != null && select.getWindowList().size() > 0) {
                out.add(new NodeToken("WINDOW", "WINDOW", path));
                for (SqlNode item : select.getWindowList()) {
                    walk(item, path + ".WINDOW", out);
                }
            }
            if (select.getQualify() != null) {
                out.add(new NodeToken("QUALIFY", "QUALIFY", path));
                walk(select.getQualify(), path + ".QUALIFY", out);
            }
            // 以下三条是 **API 对称性**分支，不是本配置下的活路径：probe 2026-10-02
            // 覆盖 8 种形态（ORDER BY / LIMIT / OFFSET / FETCH FIRST / OFFSET+FETCH /
            // derived table 内 ORDER BY / derived table 内 LIMIT / CTE 内 ORDER BY），
            // 共 8 个 SqlSelect 节点，orderList / offset / fetch **全部为 null** ——
            // Calcite 1.37 在 postgres 配置下把这些子句一律包进 SqlOrderBy（见上）。
            // 保留的理由：DialectParserConfigs 按 dialect 分别配置，其他 dialect 可能让
            // SqlSelect 自己承载这些子句；留着比留一个隐性空洞便宜。
            if (select.getOrderList() != null) {
                out.add(new NodeToken("ORDER_BY", "ORDER BY", path));
                for (SqlNode item : select.getOrderList()) {
                    walk(item, path + ".ORDER_LIST", out);
                }
            }
            if (select.getOffset() != null) walk(select.getOffset(), path + ".OFFSET", out);
            if (select.getFetch() != null) walk(select.getFetch(), path + ".FETCH", out);
            return;
        }

        if (node instanceof SqlWindow window) {
            // 具名窗口定义体（probe 2026-10-02：`WINDOW w AS (ORDER BY a)` 的 item 就是
            // SqlWindow，kind=WINDOW，unparse=`w` AS (ORDER BY `a`)`）。旧实现没有这条
            // 分支 ⇒ 会落 UNKNOWN 兜底。按 SqlBasicCall 的惯例合并为单个 token：整个
            // 定义体（窗口名 + PARTITION BY / ORDER BY / 边界）进 canonicalText，不遍历
            // 内部操作数 —— 与内联 OVER(...) 由 unparse 文本承载的做法一致。
            out.add(new NodeToken("WINDOW_DEF", unparse(window), path));
            return;
        }

        if (node instanceof SqlJoin join) {
            // Emit a JOIN token describing the join semantics, then walk
            // left, right, and condition so structural diffs across
            // JOIN changes are detected (spec §7.2 / §3.5 承重墙).
            String joinType = join.getJoinType().name();
            out.add(new NodeToken("JOIN_OP", joinType, path));
            walk(join.getLeft(), path + ".LEFT", out);
            walk(join.getRight(), path + ".RIGHT", out);
            if (join.getCondition() != null) {
                out.add(new NodeToken("JOIN_COND", "ON", path + ".COND"));
                walk(join.getCondition(), path + ".COND", out);
            }
            return;
        }

        if (node instanceof SqlWith with) {
            // CTE: emit WITH token, then walk each SqlWithItem (CTE alias +
            // body), then walk the main body. CTE bodies are recursive
            // SQL fragments, so walking them lets diffs see CTE body
            // changes as inner-token diffs (e.g. added predicate).
            out.add(new NodeToken("CTE", "WITH", path));
            if (with.withList != null) {
                for (SqlNode item : with.withList) {
                    if (item instanceof SqlWithItem withItem) {
                        walkWithItem(withItem, path + ".CTE", out);
                    } else {
                        walk(item, path + ".CTE", out);
                    }
                }
            }
            walk(with.body, path + ".BODY", out);
            return;
        }

        if (node instanceof SqlWithItem withItem) {
            walkWithItem(withItem, path, out);
            return;
        }

        if (node instanceof SqlIdentifier id) {
            out.add(new NodeToken("IDENT", id.toString(), path));
            return;
        }

        if (node instanceof SqlLiteral lit) {
            Object v = lit.getValue();
            String text = v == null ? lit.toString() : v.toString();
            out.add(new NodeToken("LITERAL", text, path));
            return;
        }

        if (node instanceof SqlBasicCall call) {
            // 比较/算术操作符：合并为单个 token，不遍历操作数。
            // 例外 1：IN / EXISTS / 比较 (=/!=/.../<) 的操作数里有 subquery 时
            // 必须递归进去，否则子查询的内部结构丢失，diff 看不出来。
            // 例外 2：AS（`FROM (SELECT ...) AS alias` 或 `JOIN (SELECT ...) su`）
            // 第一个操作数是子查询时必须递归；alias 单独 emit。
            SqlKind callKind = call.getKind();
            List<SqlNode> ops = call.getOperandList();
            boolean anySubqueryOperand = ops.stream()
                .anyMatch(op -> op instanceof SqlSelect || op instanceof SqlWith);

            if (anySubqueryOperand && isSubqueryHosting(callKind)) {
                out.add(new NodeToken(opKindLabel(callKind), unparse(call), path));
                int i = 0;
                for (SqlNode op : ops) {
                    walk(op, path + ".OP" + i, out);
                    i++;
                }
                return;
            }
            if (callKind == SqlKind.AS && ops.size() == 2 && isSubqueryLike(ops.get(0))) {
                // Subquery aliasing — recurse into the subquery (or set-op
                // like UNION), then emit the alias. Makes `t JOIN (SELECT
                // ...) su` and `t JOIN (a UNION b) su` expose inner tokens.
                walk(ops.get(0), path + ".SUBQUERY", out);
                String alias = unparse(ops.get(1));
                out.add(new NodeToken("ALIAS", alias, path + ".ALIAS"));
                return;
            }
            String text = unparse(call);
            out.add(new NodeToken(opKindLabel(callKind), text, path));
            return;
        }

        if (node instanceof SqlNodeList list) {
            for (SqlNode item : list) {
                walk(item, path + ".LIST", out);
            }
            return;
        }

        // 兜底：UNKNOWN 标记本身保留（ADR-0005：这是**有意**的诚实标注，暴露承重墙
        // 薄弱点）。但 canonicalText 必须带内容摘要 —— 旧实现只带类简单名，于是所有
        // 同型未知节点算出同一条 canonical 串 ⇒ 身份碰撞（recon 2026-10-02：
        // `UPDATE t SET a = 1` 与 `UPDATE u SET b = 2` 同 id）。摘要取该节点 unparse
        // 文本的 SHA-256 前 12 位，与 {@link #unparse(SqlNode)} 同源输入；不取整条
        // 语句文本，避免同型子树互相误分。
        out.add(new NodeToken("UNKNOWN", unknownText(node), path));
    }

    /** 兜底 token 的 canonicalText：类简单名 + 内容摘要，使残余未知节点身份内容可分。 */
    private static String unknownText(SqlNode node) {
        return node.getClass().getSimpleName() + "#" + shortDigest(unparse(node));
    }

    private static String shortDigest(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available on this JVM", e);
        }
    }

    /** 比较/算术类操作符的标签。 */
    private static String opKindLabel(SqlKind kind) {
        return switch (kind) {
            case EQUALS, NOT_EQUALS, LESS_THAN, LESS_THAN_OR_EQUAL,
                 GREATER_THAN, GREATER_THAN_OR_EQUAL, IS_NOT_DISTINCT_FROM,
                 IS_DISTINCT_FROM, LIKE, SIMILAR, BETWEEN, IN, EXISTS ->
                "EXPR_" + kind.name();
            case PLUS, MINUS, TIMES, DIVIDE, MOD ->
                "EXPR_ARITH";
            default -> "OP_" + kind.name();
        };
    }

    /** Operator kinds whose operands may legally be subqueries (SqlSelect /
     *  SqlWith). When such an operand exists, the call must recurse into
     *  the subquery so structural diffs are visible (spec §7.2). */
    private static boolean isSubqueryHosting(SqlKind kind) {
        return switch (kind) {
            case IN, EXISTS ->
                true;
            // Comparison ops (=, <, >, etc.) can also host subqueries in
            // standards-compliant SQL, even though Calcite's default parser
            // doesn't surface them as often.
            case EQUALS, NOT_EQUALS, LESS_THAN, LESS_THAN_OR_EQUAL,
                 GREATER_THAN, GREATER_THAN_OR_EQUAL ->
                true;
            default -> false;
        };
    }

    /**
     * A "subquery-like" node — something that wraps a query and may appear
     * in a FROM clause, JOIN side, or as a scalar subquery operand. Used to
     * decide whether an `AS (subquery) alias` should recurse into the inner
     * tree instead of collapsing to a single OP_AS token.
     */
    private static boolean isSubqueryLike(SqlNode node) {
        if (node instanceof SqlSelect || node instanceof SqlWith) return true;
        if (node instanceof SqlBasicCall call) {
            return switch (call.getKind()) {
                case UNION, INTERSECT, EXCEPT, VALUES -> true;
                default -> false;
            };
        }
        return false;
    }

    /**
     * Walk a CTE item: emit an alias token, then walk the body (a SqlNode
     * that may itself be a SELECT or another CTE).
     */
    private static void walkWithItem(SqlWithItem withItem, String path, List<NodeToken> out) {
        String alias = withItem.name.names.size() > 0
            ? withItem.name.names.get(withItem.name.names.size() - 1)
            : "";
        out.add(new NodeToken("CTE_ALIAS", alias, path));
        walk(withItem.query, path + ".QUERY", out);
    }

    /** 用 Calcite 自带反序列化输出规范化文本。
     *  P0 规范化（去引号 + 小写）—— 让 "A" > 0 和 a > 0 产出相同 canonicalText。 */
    private static String unparse(SqlNode node) {
        try {
            String sql = node.toSqlString(PostgresqlSqlDialect.DEFAULT).getSql();
            return normalize(sql);
        } catch (RuntimeException failure) {
            // Callers expose canonicalizationError or an HTTP error. An alternate
            // serialization must not silently become trusted canonical content.
            throw new IllegalStateException("AST serialization failed", failure);
        }
    }

    /**
     * P0-2: normalization must NOT touch string-literal content — 'a b' and
     * 'ab' are different values, and literal case is significant. Outside
     * literals: drop double quotes, drop whitespace, fold case.
     * Package-private so SqlPredicateNormalizer shares the exact same rules.
     */
    static String normalize(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        boolean inLiteral = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inLiteral) {
                result.append(c);
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        result.append('\'');
                        i++;
                    } else {
                        inLiteral = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inLiteral = true;
                result.append(c);
                continue;
            }
            if (c == '"' || Character.isWhitespace(c)) {
                continue;
            }
            result.append(Character.toLowerCase(c));
        }
        return result.toString();
    }
}
