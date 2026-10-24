package com.bitdata.sqlworkbench.sql.decompose;

import com.bitdata.sqlworkbench.sql.DialectProfile;
import com.bitdata.sqlworkbench.sql.model.AggregateInfo;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AggregateInfoExtractor}: the read-only helper that
 * powers the Add-Aggregate composition feature (Add GROUP BY / Add
 * HAVING).
 */
class AggregateInfoExtractorTest extends RuleTestFixture {

    private final DialectProfile profile = RuleTestDeps.testResolver().getProfile("postgresql");

    @Test
    void extractsGroupByOnly() throws Exception {
        SqlNode node = parse(
            "SELECT dept, COUNT(*) FROM users GROUP BY dept"
        );
        List<AggregateInfo> aggs = AggregateInfoExtractor.extractAggregates(node, profile);

        assertEquals(1, aggs.size());
        assertEquals("GROUP BY", aggs.get(0).kind());
    }

    @Test
    void extractsHavingOnly() throws Exception {
        SqlNode node = parse(
            "SELECT dept FROM users HAVING COUNT(*) > 5"
        );
        List<AggregateInfo> aggs = AggregateInfoExtractor.extractAggregates(node, profile);

        assertEquals(1, aggs.size());
        assertEquals("HAVING", aggs.get(0).kind());
    }

    @Test
    void extractsGroupByAndHaving() throws Exception {
        SqlNode node = parse(
            "SELECT dept, COUNT(*) FROM users GROUP BY dept HAVING COUNT(*) > 5"
        );
        List<AggregateInfo> aggs = AggregateInfoExtractor.extractAggregates(node, profile);

        assertEquals(2, aggs.size());
        assertEquals("GROUP BY", aggs.get(0).kind());
        assertEquals("HAVING", aggs.get(1).kind());
    }

    @Test
    void emptyForNoAggregates() throws Exception {
        SqlNode node = parse("SELECT * FROM users");
        assertTrue(AggregateInfoExtractor.extractAggregates(node, profile).isEmpty());
    }

    @Test
    void nullSafeOnNullInput() {
        assertTrue(AggregateInfoExtractor.extractAggregates(null, profile).isEmpty());
    }

    @Test
    void diffReturnsAddedAggregates() throws Exception {
        // Parent has GROUP BY. Child has none.
        SqlNode parent = parse(
            "SELECT dept, COUNT(*) FROM users GROUP BY dept"
        );
        SqlNode child = parse(
            "SELECT dept, COUNT(*) FROM users"
        );
        List<AggregateInfo> diff = AggregateInfoExtractor.diffAggregates(
            AggregateInfoExtractor.extractAggregates(parent, profile),
            AggregateInfoExtractor.extractAggregates(child, profile)
        );
        assertEquals(1, diff.size());
        assertEquals("GROUP BY", diff.get(0).kind());
    }

    @Test
    void diffEmptyForEqualParentChild() throws Exception {
        SqlNode node = parse(
            "SELECT dept FROM users GROUP BY dept"
        );
        List<AggregateInfo> diff = AggregateInfoExtractor.diffAggregates(
            AggregateInfoExtractor.extractAggregates(node, profile),
            AggregateInfoExtractor.extractAggregates(node, profile)
        );
        assertTrue(diff.isEmpty());
    }

    @Test
    void identityKeyStable() {
        AggregateInfo a = new AggregateInfo("GROUP BY", "dept", 0);
        AggregateInfo b = new AggregateInfo("GROUP BY", "dept", 0);
        assertEquals(a.identityKey(), b.identityKey());
    }

    @Test
    void identityKeyDisambiguatesKind() {
        // Same SQL text but different kind — must be different keys.
        AggregateInfo gb = new AggregateInfo("GROUP BY", "dept", 0);
        AggregateInfo hv = new AggregateInfo("HAVING", "dept", 0);
        assertNotEquals(gb.identityKey(), hv.identityKey());
    }
}
