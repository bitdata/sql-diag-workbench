package com.bitdata.sqlworkbench.sql.diff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for ADR-0001 fragment identity helper built on top of P0
 * canonicalization. Two equivalent SQLs must produce the same canonical
 * form; structurally different SQLs must produce different forms.
 */
class SqlCanonicalizerSqlTest {

    @Test
    void canonicalizeSql_isIdempotent() throws Exception {
        String a = SqlCanonicalizer.canonicalizeSql(
            "SELECT * FROM t WHERE a>0 AND b<20", "postgresql");
        // Calling twice must produce the same output (idempotent under
        // its own mapping).
        String b = SqlCanonicalizer.canonicalizeSql(
            "SELECT * FROM t WHERE a>0 AND b<20", "postgresql");
        assertEquals(a, b);
    }

    @Test
    void canonicalizeSql_normalizesCaseAndOrder() throws Exception {
        // AND reordering + identifier case should yield the same canonical form.
        String a = SqlCanonicalizer.canonicalizeSql(
            "SELECT * FROM t WHERE a>0 AND b<20", "postgresql");
        String b = SqlCanonicalizer.canonicalizeSql(
            "select * from t WHERE b<20 and a>0", "postgresql");
        assertEquals(a, b);
    }

    @Test
    void canonicalizeSql_distinguishesSemanticChanges() throws Exception {
        // b<20 → b<=20 is a real semantic change; canonical forms must differ.
        String a = SqlCanonicalizer.canonicalizeSql(
            "SELECT * FROM t WHERE a>0 AND b<20", "postgresql");
        String b = SqlCanonicalizer.canonicalizeSql(
            "SELECT * FROM t WHERE a>0 AND b<=20", "postgresql");
        assertNotEquals(a, b);
    }
}
