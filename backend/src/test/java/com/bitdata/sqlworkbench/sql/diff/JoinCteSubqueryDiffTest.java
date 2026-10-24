package com.bitdata.sqlworkbench.sql.diff;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §7.2 — JOIN / CTE / subquery diff coverage.
 *
 * <p>Verifies that structural changes inside these constructs surface as
 * inner ChangeOps rather than getting absorbed into a single opaque
 * EXPR_* token. Each test makes a focused change (one column / one
 * predicate / one subquery) and asserts the diff is visible.
 */
class JoinCteSubqueryDiffTest {

    @Nested
    class JoinCoverage {
        @Test
        void addingJoinCondition_producesAdd() throws Exception {
            // Single ON → ON AND extra predicate. After P0 normalization the
            // path gets renumbered (`.AND.1`), so we don't assert on a
            // specific path substring; instead we check that the diff
            // contains an EXPR_GREATER_THAN change (the new predicate).
            Set<ChangeOp> ops = ChangeAttribution.attributeChange(
                "SELECT * FROM t JOIN u ON t.id = u.id",
                "SELECT * FROM t JOIN u ON t.id = u.id AND t.x > 0",
                "postgresql");
            assertFalse(ops.isEmpty(), "expected a non-empty diff");
            assertTrue(ops.stream().anyMatch(o -> o.nodeType().equals("EXPR_GREATER_THAN")),
                "expected EXPR_GREATER_THAN change, got: " + ops);
        }

        @Test
        void changingJoinType_emitsDifferentJoinOp() throws Exception {
            // INNER → LEFT
            Set<ChangeOp> ops = ChangeAttribution.attributeChange(
                "SELECT * FROM t JOIN u ON t.id = u.id",
                "SELECT * FROM t LEFT JOIN u ON t.id = u.id",
                "postgresql");
            assertTrue(ops.stream().anyMatch(o -> o.kind() == ChangeOp.Kind.MOD
                    && o.nodeType().equals("JOIN_OP")),
                "expected JOIN_OP MOD, got: " + ops);
        }

        @Test
        void addingSecondTable_emitsJoinOp() throws Exception {
            // FROM t → FROM t JOIN u
            Set<ChangeOp> ops = ChangeAttribution.attributeChange(
                "SELECT * FROM t",
                "SELECT * FROM t JOIN u ON t.id = u.id",
                "postgresql");
            assertTrue(ops.stream().anyMatch(o -> o.nodeType().equals("JOIN_OP")),
                "expected JOIN_OP ADD, got: " + ops);
        }
    }

    @Nested
    class CteCoverage {
        @Test
        void addingCte_emitsCteToken() throws Exception {
            Set<ChangeOp> ops = ChangeAttribution.attributeChange(
                "SELECT * FROM t",
                "WITH c AS (SELECT * FROM t) SELECT * FROM c",
                "postgresql");
            assertTrue(ops.stream().anyMatch(o -> o.nodeType().equals("CTE")),
                "expected CTE ADD, got: " + ops);
        }

        @Test
        void changingCteBodyPredicate_propagatesInside() throws Exception {
            // CTE body WHERE added → change should appear inside CTE subtree,
            // not just at top level.
            Set<ChangeOp> ops = ChangeAttribution.attributeChange(
                "WITH c AS (SELECT * FROM t) SELECT * FROM c",
                "WITH c AS (SELECT * FROM t WHERE id > 0) SELECT * FROM c",
                "postgresql");
            assertTrue(ops.stream().anyMatch(o ->
                    o.path().contains(".CTE") && o.nodeType().startsWith("EXPR_")),
                "expected an EXPR_* change inside CTE subtree, got: " + ops);
        }

        @Test
        void addingSecondCte_emitsCteAlias() throws Exception {
            Set<ChangeOp> ops = ChangeAttribution.attributeChange(
                "WITH c AS (SELECT a FROM t) SELECT * FROM c",
                "WITH c AS (SELECT a FROM t), d AS (SELECT b FROM t) SELECT * FROM c",
                "postgresql");
            assertTrue(ops.stream().anyMatch(o -> o.nodeType().equals("CTE_ALIAS")),
                "expected CTE_ALIAS ADD, got: " + ops);
        }
    }

    @Nested
    class SubqueryCoverage {
        @Test
        void changingInSubqueryTarget_propagatesInside() throws Exception {
            // Subquery's WHERE column changes → diff should appear inside
            // the OP1 (subquery) subtree.
            Set<ChangeOp> ops = ChangeAttribution.attributeChange(
                "SELECT * FROM t WHERE id IN (SELECT id FROM u WHERE x > 0)",
                "SELECT * FROM t WHERE id IN (SELECT id FROM u WHERE x > 1)",
                "postgresql");
            assertTrue(ops.stream().anyMatch(o -> o.path().contains("OP1")),
                "expected a change inside IN subquery (OP1), got: " + ops);
        }

        @Test
        void changingExistsSubqueryBody_propagatesInside() throws Exception {
            Set<ChangeOp> ops = ChangeAttribution.attributeChange(
                "SELECT * FROM t WHERE EXISTS (SELECT 1 FROM u WHERE u.t_id = t.id)",
                "SELECT * FROM t WHERE EXISTS (SELECT 1 FROM u WHERE u.t_id = t.id AND u.x = 0)",
                "postgresql");
            // The new predicate surfaces as an ADD of EXPR_EQUALS.
            assertTrue(ops.stream().anyMatch(o -> o.nodeType().equals("EXPR_EQUALS")
                    && o.kind() == ChangeOp.Kind.ADD),
                "expected EXPR_EQUALS ADD inside EXISTS subquery, got: " + ops);
        }

        @Test
        void switchingFromInToExists_changesOpType() throws Exception {
            Set<ChangeOp> ops = ChangeAttribution.attributeChange(
                "SELECT * FROM t WHERE id IN (SELECT id FROM u)",
                "SELECT * FROM t WHERE EXISTS (SELECT 1 FROM u)",
                "postgresql");
            assertTrue(ops.stream().anyMatch(o -> o.nodeType().startsWith("EXPR_")),
                "expected EXPR_* change, got: " + ops);
        }
    }

    @Nested
    class NoUnknownTokens {
        @Test
        void join_cte_subquery_produceNoUnknownTokens() throws Exception {
            // Spec §3.5: "兜底发 UNKNOWN token" is a承重墙薄弱点标记.
            // Verify JOIN / CTE / subquery are now all recognized.
            String[] queries = {
                "SELECT * FROM t JOIN u ON t.id = u.id",
                "WITH c AS (SELECT * FROM t) SELECT * FROM c",
                "SELECT * FROM t WHERE id IN (SELECT id FROM u)",
                "SELECT * FROM t WHERE EXISTS (SELECT 1 FROM u WHERE u.t_id = t.id)",
                "WITH cte1 AS (SELECT a FROM t), cte2 AS (SELECT b FROM t) SELECT * FROM cte1 JOIN cte2 ON cte1.a = cte2.b",
            };
            for (String sql : queries) {
                var tokens = AstSerializer.serialize(sql, "postgresql");
                assertTrue(tokens.stream().noneMatch(t -> t.nodeType().equals("UNKNOWN")),
                    "SQL should not produce UNKNOWN tokens: " + sql
                    + "\n  tokens: " + tokens);
            }
        }
    }

    @Nested
    class JoinSubqueryCoverage {
        @Test
        void joinSubquery_addsColumn() throws Exception {
            // `JOIN (SELECT id FROM u) su` → `JOIN (SELECT id, x FROM u) su`
            // The added column X should surface as an EXPR/IDENT add in the
            // diff (not as an opaque OP_AS text change).
            Set<ChangeOp> ops = ChangeAttribution.attributeChange(
                "SELECT * FROM t JOIN (SELECT id FROM u) su ON t.id = su.id",
                "SELECT * FROM t JOIN (SELECT id, x FROM u) su ON t.id = su.id",
                "postgresql");
            assertTrue(ops.stream().anyMatch(o ->
                    o.kind() == ChangeOp.Kind.ADD
                    && (o.nodeType().equals("IDENT") || o.nodeType().equals("LITERAL"))),
                "expected ADD of IDENT/LITERAL for new column X, got: " + ops);
        }

        @Test
        void joinSubquery_changesWhereClause() throws Exception {
            Set<ChangeOp> ops = ChangeAttribution.attributeChange(
                "SELECT * FROM t JOIN (SELECT id FROM u WHERE u.x > 0) su ON t.id = su.id",
                "SELECT * FROM t JOIN (SELECT id FROM u WHERE u.x > 1) su ON t.id = su.id",
                "postgresql");
            assertTrue(ops.stream().anyMatch(o -> o.nodeType().equals("EXPR_GREATER_THAN")
                    && o.kind() == ChangeOp.Kind.MOD),
                "expected EXPR_GREATER_THAN MOD inside JOIN subquery, got: " + ops);
        }

        @Test
        void fromSubquery_isRecognized() throws Exception {
            // `SELECT * FROM (SELECT * FROM t) sub` — subquery as FROM source
            // (not in a JOIN). The inner SELECT tokens should be present.
            var tokens = AstSerializer.serialize(
                "SELECT * FROM (SELECT * FROM t) sub", "postgresql");
            assertTrue(tokens.stream().anyMatch(t -> t.nodeType().equals("ALIAS")
                    && t.canonicalText().equals("sub")),
                "expected ALIAS token for 'sub', got: " + tokens);
            // Inner subquery's SELECT must be walked, not collapsed.
            assertTrue(tokens.stream().filter(t -> t.path().contains("SUBQUERY"))
                    .anyMatch(t -> t.nodeType().equals("SELECT")),
                "expected inner SELECT inside SUBQUERY subtree, got: " + tokens);
        }

        @Test
        void joinUnionSubquery_isRecognized() throws Exception {
            // `JOIN (a UNION b) su` — Calcite wraps UNION as SqlBasicCall(UNION,
            // a, b); AS then wraps that. We recurse through both layers.
            var tokens = AstSerializer.serialize(
                "SELECT * FROM t JOIN (SELECT a FROM u UNION SELECT a FROM v) su ON t.a = su.a",
                "postgresql");
            assertTrue(tokens.stream().anyMatch(t -> t.nodeType().equals("OP_UNION")),
                "expected OP_UNION token inside JOIN subquery, got: " + tokens);
            assertTrue(tokens.stream().anyMatch(t -> t.nodeType().equals("ALIAS")
                    && t.canonicalText().equals("su")),
                "expected ALIAS 'su', got: " + tokens);
            assertTrue(tokens.stream().noneMatch(t -> t.nodeType().equals("UNKNOWN")),
                "no UNKNOWN tokens should appear: " + tokens);
        }
    }
}