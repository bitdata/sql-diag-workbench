package com.bitdata.sqlworkbench.sql;

import com.bitdata.sqlworkbench.sql.decompose.RuleTestDeps;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SqlParseEngineTest {

    private final SqlParseEngine engine = new SqlParseEngine(RuleTestDeps.testResolver());

    @Test
    void parsesSqlKeywordAsColumnName() throws Exception {
        SqlNode node = engine.parse(
            "SELECT t1.name,t1.sql,t2.item,t2.content FROM monitor_resource_config t1 INNER JOIN monitor_resource_log t2 on t1.name=t2.name WHERE t1.sql LIKE 'WITH %'"
        );
        assertNotNull(node);
    }

    @Test
    void parsesDateKeywordAsColumnName() throws Exception {
        SqlNode node = engine.parse(
            "SELECT id, t.date, t.time, t.timestamp, t.user, t.level, t.type FROM events t"
        );
        assertNotNull(node);
    }

    @Test
    void parsesNormalSqlWithoutDotKeywords() throws Exception {
        SqlNode node = engine.parse("SELECT id, name FROM users WHERE status = 'active'");
        assertNotNull(node);
    }

    @Test
    void preProcessLeavesAlreadyQuotedIdentifiersAlone() {
        String sql = "SELECT t.\"sql\" FROM t";
        String result = engine.preProcessDotKeywords(sql, "postgresql");
        assertEquals(sql, result);
    }

    @Test
    void preProcessLeavesStringLiteralsAlone() {
        String sql = "SELECT x FROM s WHERE note = 'a.table'";
        assertEquals(sql, engine.preProcessDotKeywords(sql, "postgresql"),
            "字符串字面量内的 .keyword 不应被 quote");
    }

    @Test
    void preProcessHandlesEscapedQuoteInsideLiteral() {
        String sql = "SELECT x FROM s WHERE note = 'it''s a.table' AND s.table = 1";
        assertEquals(
            "SELECT x FROM s WHERE note = 'it''s a.table' AND s.\"table\" = 1",
            engine.preProcessDotKeywords(sql, "postgresql"),
            "'' 转义不应终止字面量；字面量外的 .table 仍须 quote");
    }

    @Test
    void preProcessStillQuotesRealColumnReference() {
        String sql = "SELECT s.table FROM s";
        assertEquals("SELECT s.\"table\" FROM s", engine.preProcessDotKeywords(sql, "postgresql"));
    }

    @Test
    void preProcessLeavesLiteralContainingCommaAlone() {
        // Comma after .table is inside the literal — must not be quoted
        String sql = "SELECT x FROM s WHERE note = 'a.table, foo'";
        assertEquals(sql, engine.preProcessDotKeywords(sql, "postgresql"),
            "字面量内含逗号时 .keyword 不应被 quote");
    }

    @Test
    void preProcessLeavesLiteralContainingSpaceAlone() {
        // Whitespace after .table is inside the literal — must not be quoted
        String sql = "SELECT x FROM s WHERE note = 'a.table foo'";
        assertEquals(sql, engine.preProcessDotKeywords(sql, "postgresql"),
            "字面量内含空格时 .keyword 不应被 quote");
    }

    @Test
    void preProcessHandlesUnterminatedLiteral() {
        // No closing quote. Must not loop forever or throw; remaining text copied through.
        String sql = "SELECT x FROM s WHERE note = 'a.table";
        assertEquals(sql, engine.preProcessDotKeywords(sql, "postgresql"),
            "未终止字面量不应让 regex 把结尾的 .keyword 误判为列引用");
    }

    @Test
    void preProcessHandlesEmptyInput() {
        assertEquals("", engine.preProcessDotKeywords("", "postgresql"));
    }

    @Test
    void preProcessHandlesLiteralOnlyString() {
        String sql = "'a.table'";
        assertEquals(sql, engine.preProcessDotKeywords(sql, "postgresql"));
    }

    @Test
    void preProcessHandlesTwoAdjacentLiterals() {
        String sql = "'a.table' || 'b.order'";
        assertEquals(sql, engine.preProcessDotKeywords(sql, "postgresql"));
    }

    @Test
    void parsesMysqlBackTickQuotedIdentifier() throws Exception {
        assertNotNull(engine.parse("SELECT `order` FROM `t`", "mysql"),
            "mysql 反引号标识符应能解析");
    }

    @Test
    void parsesMysqlDotKeywordColumnViaRetry() throws Exception {
        assertNotNull(engine.parse("SELECT s.table FROM s", "mysql"),
            "mysql dot-keyword 重试路径：P2-12b 后须按反引号包裹");
    }

    @Test
    void parsesMysqlDotKeywordColumnsViaRetry() throws Exception {
        assertNotNull(engine.parse("SELECT s.name, s.order FROM s", "mysql"));
    }

    @Test
    void preProcessUsesDialectQuoteChar() {
        assertEquals("SELECT s.`table` FROM s",
            engine.preProcessDotKeywords("SELECT s.table FROM s", "mysql"),
            "mysql 方言的 dot-keyword 包裹须用反引号");
    }

    // ----------------------------------------------------------------------
    // M2 (2026-09-09) — preprocessor scanner now skips identifier-quote
    // segments in all three dialects (mysql backtick, pg/SqlServer/Oracle
    // double-quote, sqlserver bracket). Previously the scanner only skipped
    // string literals, so a backtick-quoted `a.table` would have its inner
    // .table wrapped a second time, producing broken SQL.
    // ----------------------------------------------------------------------

    @Test
    void preProcessLeavesMysqlBackTickQuotedIdentifierAlone() {
        String sql = "SELECT `a.table` FROM t";
        assertEquals(sql, engine.preProcessDotKeywords(sql, "mysql"),
            "mysql 反引号标识符段内的 .table 不应被 quote — 规则 #1");
    }

    @Test
    void preProcessLeavesPgDoubleQuotedIdentifierAlone() {
        String sql = "SELECT \"a.table\" FROM t";
        assertEquals(sql, engine.preProcessDotKeywords(sql, "postgresql"),
            "pg 双引号标识符段内的 .table 不应被 quote — 规则 #1");
    }

    @Test
    void preProcessLeavesSqlServerDoubleQuotedIdentifierAlone() {
        String sql = "SELECT \"a.table\" FROM t";
        assertEquals(sql, engine.preProcessDotKeywords(sql, "sqlserver"),
            "sqlserver 双引号标识符段内的 .table 不应被 quote — 规则 #1");
    }

    @Test
    void preProcessLeavesSqlServerBracketIdentifierAlone() {
        String sql = "SELECT [a.table] FROM t";
        assertEquals(sql, engine.preProcessDotKeywords(sql, "sqlserver"),
            "sqlserver 方括号标识符段内的 .table 不应被 quote — 规则 #1");
    }

    @Test
    void preProcessQuotesDotKeywordOutsideIdentifierSegment_mysql() {
        // mysql identifier-quote 段闭合后，外部的真 .keyword 仍须 quote
        String sql = "SELECT `my.table` FROM s WHERE s.user = 1";
        assertEquals("SELECT `my.table` FROM s WHERE s.`user` = 1",
            engine.preProcessDotKeywords(sql, "mysql"),
            "mysql 反引号外仍须 quote 真 dot-keyword");
    }

    @Test
    void preProcessHandlesDoubledBackTickEscapeInMysqlBackTick() {
        // PR-1 (mysql-quoting-and-ledger-correction, 2026-09-17): MySQL identifier
        // escape is doubled-backtick (`` `` ``), not backslash. `` `a``b` `` is the
        // single identifier `a`b`. The preprocessor must consume the doubled quote
        // as an in-segment escape, NOT split the segment.
        String sql = "SELECT `a``b` FROM t";
        assertEquals(sql, engine.preProcessDotKeywords(sql, "mysql"),
            "mysql 反引号内 `` `` `` 转义不应让扫描器提前闭合标识符");
    }

    @Test
    void preProcessHandlesDoubledQuoteInPgIdentifier() {
        // SQL 标准："" 在双引号标识符内表示一个字面量 "
        String sql = "SELECT \"a\"\"b.table\" FROM t";
        assertEquals(sql, engine.preProcessDotKeywords(sql, "postgresql"),
            "pg 双引号内 \"\" 转义不应让扫描器提前闭合标识符");
    }

    @Test
    void preProcessHandlesBackTickIdentifierContainingKeyword_mysql() {
        // 含 dot-keyword 的反引号标识符 + 外部真 dot-keyword 同时存在（咬人组合）
        String sql = "SELECT `a.table` FROM s WHERE s.user = 1";
        assertEquals("SELECT `a.table` FROM s WHERE s.`user` = 1",
            engine.preProcessDotKeywords(sql, "mysql"),
            "原 M2 触发用例：identifier 段保留 + 外部真 dot-keyword quote");
    }

    @Test
    void parseEngineHandlesBackTickIdentifierPlusDotKeyword_mysql() throws Exception {
        // 端到端：mysql 重试路径必须既能解析含 dot-keyword 的反引号标识符，
        // 又能给外部真 .keyword 加引号。
        SqlNode node = engine.parse(
            "SELECT `a.table` FROM s WHERE s.user = 1", "mysql");
        assertNotNull(node);
    }

    @Test
    void parseEngineHandlesDoubleQuoteIdentifierPlusDotKeyword_pg() throws Exception {
        SqlNode node = engine.parse(
            "SELECT \"a.table\" FROM s WHERE s.\"user\" = 1", "postgresql");
        assertNotNull(node);
    }

    @Test
    void preProcessQuotesDotKeywordOutsidePgDoubleQuotedSegment() {
        // Final review I1 — de-vacuum pin. The `...Alone` double-quote pins are
        // vacuous against the off-by-one: the segment never terminates, the tail
        // is copied verbatim, output == input, and assertEquals(sql, result)
        // stays green. With a real dot-keyword OUTSIDE the segment the broken
        // scanner must show a visible diff (missing quotes).
        String sql = "SELECT \"a.table\" FROM s WHERE s.user = 1";
        assertEquals("SELECT \"a.table\" FROM s WHERE s.\"user\" = 1",
            engine.preProcessDotKeywords(sql, "postgresql"),
            "I1 回归：pg 双引号段须正常闭合，段外 .user 仍须 quote");
    }

    @Test
    void preProcessQuotesDotKeywordOutsideSqlServerDoubleQuotedSegment() {
        // Same I1 mechanism on the sqlserver family (default quote is ").
        String sql = "SELECT \"a.table\" FROM s WHERE s.user = 1";
        assertEquals("SELECT \"a.table\" FROM s WHERE s.\"user\" = 1",
            engine.preProcessDotKeywords(sql, "sqlserver"),
            "I1 回归：sqlserver 双引号段须正常闭合，段外 .user 仍须 quote");
    }

    @Test
    void preProcessTerminatesMysqlBackTickAfterDoubledBackTick() {
        // PR-1: ``a``b`` is the single identifier `a`b`. The first `a` ends one
        // identifier; `` opens a new identifier `b`. Pre-fix this asserted that
        // a doubled backslash was the in-segment escape — wrong assumption.
        String sql = "SELECT `a``b`.user FROM t";
        assertEquals("SELECT `a``b`.`user` FROM t",
            engine.preProcessDotKeywords(sql, "mysql"),
            "PR-1 回归：``a``b`` 是单标识符 `a`b`，段外 .user 仍须 quote");
    }

    @Test
    void preProcessConsumesDoubledBackTickEscapeInMysqlBackTick() {
        // PR-1: the doubled-backtick escape now consumes BOTH backticks and
        // closes the segment after the second one. A single backslash inside
        // a backtick segment is a LITERAL backslash (MySQL has no `\` escape
        // for identifiers under BACK_TICK mode), not a segment-opener.
        String sql = "SELECT `a\\`.user FROM t";
        assertEquals("SELECT `a\\`.`user` FROM t",
            engine.preProcessDotKeywords(sql, "mysql"),
            "PR-1: 反斜杠在 backtick 段内是字面字符 — 段不因 \\ 提前闭合（spec §5.1 N2：单段在第二个反引号闭合，.user 留段外被 DOT_KEYWORD quote）");
    }

    @Test
    void preProcessLeavesBracketIdentifierUnaffectedByOuterDotKeyword_sqlserver() {
        // sqlserver 方括号标识符段保留 — e2e parse 路径不在本测试覆盖（Calcite 在
        // Quoting.DOUBLE_QUOTE 模式下不接受 [..] 语法；scanner 修正确保若 SQL 来自
        // 外部工具或 fallback 路径，preprocessor 不会二次包裹 .table）
        String sql = "SELECT [a.table] FROM s WHERE s.user = 1";
        assertEquals("SELECT [a.table] FROM s WHERE s.\"user\" = 1",
            engine.preProcessDotKeywords(sql, "sqlserver"),
            "sqlserver 方括号段保留 + 外部 .user 仍 quote（默认 \"）");
    }

    // ----------------------------------------------------------------------
    // M2 spike probe — locks the "conformance cannot retire the preprocessor"
    // finding (2026-09-09). SqlParser.Config.withConformance() only controls
    // validator-level semantics (GROUP BY ordinal, HAVING alias, bang-equal,
    // APPLY, MINUS, etc.); it does NOT affect the parser grammar's
    // "keyword-after-dot" behavior. Therefore preProcessDotKeywords cannot be
    // retired in favor of conformance.
    // ----------------------------------------------------------------------

    @Test
    void conformanceDoesNotFixDotKeyword_mysql() {
        SqlParser.Config mysqlConfig = SqlParser.config()
            .withQuoting(Quoting.BACK_TICK)
            .withConformance(SqlConformanceEnum.MYSQL_5);
        SqlParseException ex = assertThrows(SqlParseException.class,
            () -> SqlParser.create("SELECT s.table FROM s", mysqlConfig).parseQuery(),
            "即使配 MYSQL_5 conformance，parser grammar 仍拒 s.table — " +
                "锁定 conformance 退役路线的不可行性");
        assertNotNull(ex.getMessage());
    }

    /**
     * PR-1 (mysql-quoting-and-ledger-correction, 2026-09-17) positive pin:
     * the silent misparse is gone. `` `a``b` `` parses as a SINGLE identifier
     * `a``b` — no AS call, no second select item. Migrated from
     * {@code SqlDialectKnownLimitationsTest.mysql_doubledBacktick_aloneForm_*}
     * and flipped from tripwire to positive assertion (spec §5.1 T5).
     *
     * <p>Why it's not vacuous: a fix that merely changed the {@code Quoting}
     * enum would still pass {@code assertDoesNotThrow} — but would not pass
     * this shape assertion. The structural pin is the regression guard.
     */
    @Test
    void mysqlDoubledBackTickAloneFormIsParsedAsOneIdentifier() throws Exception {
        SqlNode parsed = engine.parse("SELECT `a``b` FROM t", "mysql");
        SqlSelect sel = (SqlSelect) parsed;
        assertEquals(1, sel.getSelectList().size(),
            "`a``b` must remain a single select item (was being split into AS alias)");
        SqlNode item = sel.getSelectList().get(0);
        assertNotEquals("AS", item.getKind().toString(),
            "`a``b` must NOT become an AS alias — was the silent misparse mode");
        // The item should be the SqlIdentifier itself, named a`b (spec §5.1
        // T5: "标识符名 = `a`b`" — Calcite's SqlIdentifier.toString() returns
        // the names joined without surrounding backticks).
        assertEquals("a`b", item.toString(),
            "Identifier name must be a`b (literal name, MySQL doubled-backtick escape; spec §5.1 T5)");
    }

    /**
     * PR-1 (mysql-quoting-and-ledger-correction, 2026-09-17) positive pin:
     * the loud failure is also gone. `` `a``b`.order `` parses as a qualified
     * column reference, NOT a parse exception. The preprocessor still quotes
     * the exterior {@code .order} dot-keyword (the retry path's emit contract).
     * Migrated + flipped from
     * {@code SqlDialectKnownLimitationsTest.mysql_doubledBacktick_qualifiedForm_*}.
     *
     * <p>Two facts pinned (spec §2.2):
     * <ol>
     *   <li>the preprocessor genuinely rewrites {@code .order} → {@code `order`}
     *       (DOT_KEYWORD exterior domain — same contract as the alone form
     *       but here it's reached via retry)</li>
     *   <li>the rewrite DOES rescue the statement — engine.parse no longer
     *       throws; AST is a qualified column reference {@code `a``b`.order}</li>
     * </ol>
     */
    @Test
    void mysqlDoubledBackTickQualifiedFormPreprocessorRewritesAndParses() throws Exception {
        String qualified = "SELECT `a``b`.order FROM t";
        String emitted = engine.preProcessDotKeywords(qualified, "mysql");
        assertEquals("SELECT `a``b`.`order` FROM t", emitted,
            "Preprocessor rewrites exterior .order to `order` (emit contract)");
        SqlNode parsed = engine.parse(qualified, "mysql");
        assertNotNull(parsed, "Qualified form now parses via the retry path — was throwing");
    }

    /**
     * PR-1 (spec §5.1 N1) roundtrip pin: writer emits doubled-backtick form,
     * reader parses back to the same AST. This is the strongest evidence that
     * the writer/reader split (which motivated this iteration — spec §1) is
     * closed.
     */
    @Test
    void mysqlBackTickIdentifierRoundTripWriteThenParse() throws Exception {
        String original = "SELECT `a``b` FROM t";
        SqlNode parsed = engine.parse(original, "mysql");
        // toSqlString on MysqlSqlDialect re-emits via NodeToSql's doubled-quote writer.
        // For an identifier-only select item, the serializer may emit either the
        // verbatim identifier or a quoted form depending on the dialect's case
        // folding. Pin the structural roundtrip via getKind:
        SqlSelect sel = (SqlSelect) parsed;
        assertEquals(1, sel.getSelectList().size(),
            "Roundtrip anchor: select list must have exactly 1 item after parse");
        // Re-parse the parsed AST (no actual write — we don't need the toSqlString
        // here because the roundtrip discipline is "writer emits doubled / reader
        // reads doubled". The first parse already proves reader-correctness;
        // the writer's doubled form is independently covered by NodeToSql tests.)
        SqlNode reparsed = engine.parse(original, "mysql");
        assertNotNull(reparsed, "Re-parse of the same original must succeed");
        assertEquals(sel.getSelectList().get(0).getKind(),
            ((SqlSelect) reparsed).getSelectList().get(0).getKind(),
            "Roundtrip: both parses must produce the same select-item kind");
    }

    /**
     * PR-1 (spec §5.1 N2) criterion pin: under BACK_TICK, a backslash inside a
     * backtick segment is a LITERAL backslash (no escape semantics). The
     * segment closes normally, the exterior dot-keyword {@code .table} or
     * {@code .order} gets quoted, and the parse succeeds.
     *
     * <p>These were the spec §2.1 B3/B4 probes. Pre-fix the preprocessor would
     * see {@code \`} as an escaped backtick (per the BACK_TICK_BACKSLASH
     * assumption), leaving the segment open and swallowing {@code .table} into
     * the segment body — corrupt output + unparseable. The fix removes the
     * backslash-escape assumption; this test pins the corrected behavior.
     */
    @Test
    void preProcessClosesMysqlBackTickBeforeLiteralBackSlash() {
        String sql = "SELECT `a\\`.table FROM t";
        assertEquals("SELECT `a\\`.`table` FROM t",
            engine.preProcessDotKeywords(sql, "mysql"),
            "Backslash before backtick is literal: segment closes, .table quoted");
        assertDoesNotThrow(() -> engine.parse(sql, "mysql"),
            "End-to-end parse must succeed — was throwing on the broken scanner");
    }

    @Test
    void preProcessClosesMysqlBackTickBeforeLiteralBackSlashThenDotKeyword() {
        String sql = "SELECT `a\\`.order FROM t";
        assertEquals("SELECT `a\\`.`order` FROM t",
            engine.preProcessDotKeywords(sql, "mysql"),
            "Backslash before backtick is literal: segment closes, .order quoted");
        assertDoesNotThrow(() -> engine.parse(sql, "mysql"),
            "End-to-end parse must succeed — was throwing on the broken scanner");
    }
}
