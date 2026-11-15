package com.bitdata.sqlworkbench.sql.diff;

import com.bitdata.sqlworkbench.sql.tsql.TsqlExecutableDialect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Frozen G1 inputs/expectations, exercised against production classes without probes. */
class StructuralSqlCodecTest {
    @TestFactory
    Stream<DynamicTest> frozenSemanticContracts() throws Exception {
        JsonNode cases;
        try (var input = getClass().getResourceAsStream("/sqlserver/structural-contract.json")) {
            cases = new ObjectMapper().readTree(input);
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (var c : cases) {
            tests.add(DynamicTest.dynamicTest(c.path("stage").asText() + "/" + c.path("id").asText(), () -> {
                if (c.has("expectedSame")) {
                    String dialect = c.path("id").asText().replaceAll(".*/", "");
                    var left = StructuralSqlCodec.canonicalize(c.path("left").asText(), dialect);
                    var right = StructuralSqlCodec.canonicalize(c.path("right").asText(), dialect);
                    assertFalse(left.degraded(), left.errorReason());
                    assertFalse(right.degraded(), right.errorReason());
                    boolean equal = c.path("expectedSame").asBoolean();
                    assertEquals(equal, left.canonical().equals(right.canonical()));
                    assertEquals(equal, left.id().equals(right.id()));
                    for (var delta : List.of(StructuralSqlCodec.diff(left, right), StructuralSqlCodec.diff(right, left))) {
                        assertFalse(delta.degraded(), delta.errorReason());
                        assertEquals(equal, delta.ops().isEmpty());
                    }
                } else {
                    String sql = c.path("sql").asText();
                    var left = StructuralSqlCodec.canonicalize(sql, "sqlserver");
                    assertFalse(left.degraded(), left.errorReason());
                    String emitted = StructuralSqlCodec.parse(sql, "sqlserver")
                        .toSqlString(new TsqlExecutableDialect(17)).getSql();
                    var right = StructuralSqlCodec.canonicalize(emitted, "sqlserver");
                    assertFalse(right.degraded(), right.errorReason());
                    assertEquals(left.id(), right.id(), emitted);
                    assertTrue(StructuralSqlCodec.diff(left, right).ops().isEmpty(), emitted);
                }
            }));
        }
        return tests.stream();
    }

    @Test
    void onlyBalancedVersionedStructuralTokensAreAcceptedAndNormalizationIsIdempotent() {
        var form = StructuralSqlCodec.canonicalize("SELECT a FROM t WHERE NOT (a=1 OR b=2)","postgresql");
        assertFalse(form.degraded());
        var once = StructuralSqlCodec.canonicalizeTokens(form.tokens());
        assertEquals(once,StructuralSqlCodec.canonicalizeTokens(once));
        assertEquals(form.tokens(),once);
        assertThrows(IllegalArgumentException.class, () -> StructuralSqlCodec.canonicalizeTokens(
            List.of(new NodeToken("SELECT","SELECT","root"))));
        assertThrows(IllegalArgumentException.class, () -> StructuralSqlCodec.canonicalizeTokens(
            List.of(new NodeToken("FORMAT",StructuralSqlCodec.POLICY,"format"),new NodeToken("VALUE_UNKNOWN","x","root"))));
        assertThrows(IllegalArgumentException.class, () -> StructuralSqlCodec.canonicalizeTokens(
            form.tokens().subList(0,form.tokens().size()-1)));
    }

    @Test
    void unsupportedNodesCannotReceiveTrustedIdentityOrHealthyEmptyDiff() {
        for (String dialect : List.of("sqlserver", "postgresql", "mysql", "unknown")) {
            var form = StructuralSqlCodec.canonicalize("SELECT ?", dialect);
            assertTrue(form.degraded());
            assertNotNull(form.errorReason());
            assertNull(form.id());
            assertTrue(StructuralSqlCodec.diff(form, form).degraded());
        }
    }

    @Test
    void predicateModificationIsAtomicAndItsDisplayIsSql() throws Exception {
        for (String dialect : List.of("sqlserver", "postgresql", "mysql")) {
            String before = "SELECT a FROM t WHERE b<20", after = "SELECT a FROM t WHERE b<=20";
            var delta = StructuralSqlCodec.diff(StructuralSqlCodec.canonicalize(before, dialect),
                StructuralSqlCodec.canonicalize(after, dialect));
            assertFalse(delta.degraded());
            assertEquals(1, delta.ops().size());
            assertEquals(ChangeOp.Kind.MOD, delta.ops().iterator().next().kind());
            var display = StructuralSqlCodec.display(delta, before, after, dialect);
            assertEquals("AST_NODE", display.get(0).displayScope());
            assertTrue(display.get(0).oldSql().contains("20"));
            assertFalse(display.get(0).oldSql().contains("CALL_"));
        }
    }
}
