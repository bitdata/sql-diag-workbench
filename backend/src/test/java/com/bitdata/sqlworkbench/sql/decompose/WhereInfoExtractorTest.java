package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import com.bitdata.sqlworkbench.sql.model.WhereInfo;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WhereInfoExtractor}: the read-only helper that
 * powers the Add-WHERE composition feature. Mirrors the shape of
 * {@link JoinInfoExtractorTest}.
 */
class WhereInfoExtractorTest extends RuleTestFixture {

    private final DialectProfile profile = RuleTestDeps.testResolver().getProfile("postgresql");

    @Test
    void extractsFlattenedAndConditions() throws Exception {
        SqlNode node = parse(
            "SELECT * FROM users WHERE status = 'active' AND email IS NOT NULL AND age > 18"
        );
        List<WhereInfo> conds = WhereInfoExtractor.extractWhereConditions(node, profile);

        assertEquals(3, conds.size());
        assertTrue(conds.get(0).conditionSql().toUpperCase().contains("STATUS"));
        assertTrue(conds.get(1).conditionSql().toUpperCase().contains("EMAIL"));
        assertTrue(conds.get(2).conditionSql().toUpperCase().contains("AGE"));
    }

    @Test
    void emptyForNoWhere() throws Exception {
        SqlNode node = parse("SELECT * FROM users");
        assertTrue(WhereInfoExtractor.extractWhereConditions(node, profile).isEmpty());
    }

    @Test
    void singleCondition_extracted() throws Exception {
        SqlNode node = parse("SELECT * FROM users WHERE id = 1");
        List<WhereInfo> conds = WhereInfoExtractor.extractWhereConditions(node, profile);
        assertEquals(1, conds.size());
        assertTrue(conds.get(0).conditionSql().toUpperCase().contains("ID"));
        assertEquals(0, conds.get(0).position());
    }

    @Test
    void nullSafeOnNullInput() {
        assertTrue(WhereInfoExtractor.extractWhereConditions(null, profile).isEmpty());
    }

    @Test
    void diffReturnsAddedConditions() throws Exception {
        // Parent: 3 conditions. Child: 1 condition.
        SqlNode parent = parse(
            "SELECT * FROM users WHERE a = 1 AND b = 2 AND c = 3"
        );
        SqlNode child = parse(
            "SELECT * FROM users WHERE a = 1"
        );
        List<WhereInfo> diff = WhereInfoExtractor.diffWhereConditions(
            WhereInfoExtractor.extractWhereConditions(parent, profile),
            WhereInfoExtractor.extractWhereConditions(child, profile)
        );
        assertEquals(2, diff.size(), "Expected two added conditions (b, c)");
        assertTrue(diff.stream().allMatch(w -> w.position() >= 1),
            "a = 1 must NOT be reported as added");
    }

    @Test
    void emptyForEqualParentChild() throws Exception {
        SqlNode node = parse(
            "SELECT * FROM users WHERE a = 1 AND b = 2"
        );
        List<WhereInfo> diff = WhereInfoExtractor.diffWhereConditions(
            WhereInfoExtractor.extractWhereConditions(node, profile),
            WhereInfoExtractor.extractWhereConditions(node, profile)
        );
        assertTrue(diff.isEmpty());
    }

    @Test
    void emptyForNullOrEmptyParent() {
        assertTrue(WhereInfoExtractor.diffWhereConditions(null, List.of()).isEmpty());
        assertTrue(WhereInfoExtractor.diffWhereConditions(List.of(), null).isEmpty());
    }

    @Test
    void identityKeyStable() {
        WhereInfo a = new WhereInfo("x = 1", 0);
        WhereInfo b = new WhereInfo("x = 1", 0);
        assertEquals(a.identityKey(), b.identityKey());
    }

    @Test
    void identityKeyIgnoresPosition() {
        WhereInfo a = new WhereInfo("x = 1", 0);
        WhereInfo b = new WhereInfo("x = 1", 1);
        assertEquals(a.identityKey(), b.identityKey());
    }

    @Test
    void diff_isolatingFirstPredicate_returnsOnlyThatPredicate() {
        // Parent WHERE a = 1 AND b = 2；隔离 a = 1 后 child 为 WHERE b = 2（position 1→0）。
        // identityKey 必须忽略 position，否则共享的 b = 2 也被报为新增。
        List<WhereInfo> parent = List.of(new WhereInfo("a = 1", 0), new WhereInfo("b = 2", 1));
        List<WhereInfo> child = List.of(new WhereInfo("b = 2", 0));
        List<WhereInfo> diff = WhereInfoExtractor.diffWhereConditions(parent, child);
        assertEquals(1, diff.size());
        assertEquals("a = 1", diff.get(0).conditionSql());
    }

    @Test
    void diff_parentSingleWhereVsChildStripped_returnsThatPredicate() throws Exception {
        // parent = ORIGINAL 单条件 WHERE，child = STRIPPED（WHERE 已去掉）。
        // extractWhereConditions 必须提取单条件，否则 diff 为空、filter 规则永不触发。
        SqlNode parent = parse("SELECT * FROM users WHERE id = 1");
        SqlNode child = parse("SELECT * FROM users");
        List<WhereInfo> diff = WhereInfoExtractor.diffWhereConditions(
            WhereInfoExtractor.extractWhereConditions(parent, profile),
            WhereInfoExtractor.extractWhereConditions(child, profile));
        assertEquals(1, diff.size());
        assertTrue(diff.get(0).conditionSql().toUpperCase().contains("ID"));
    }
}
