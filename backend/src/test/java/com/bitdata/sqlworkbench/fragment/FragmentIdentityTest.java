package com.bitdata.sqlworkbench.fragment;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FragmentIdentityTest {

    @Test
    void computeId_deterministic() {
        String a = FragmentIdentity.computeId("SELECT@0:select * from t", "postgresql");
        String b = FragmentIdentity.computeId("SELECT@0:select * from t", "postgresql");
        assertEquals(a, b);
    }

    @Test
    void computeId_returns64CharHex() {
        String id = FragmentIdentity.computeId("select 1", "postgresql");
        assertTrue(id.matches("[a-f0-9]{64}"), "expected 64-char hex, got: " + id);
    }

    @Test
    void computeId_dialectSensitive() {
        String pg = FragmentIdentity.computeId("select 1", "postgresql");
        String my = FragmentIdentity.computeId("select 1", "mysql");
        assertNotEquals(pg, my);
    }

    @Test
    void computeId_rejectsNullCanonicalSql() {
        assertThrows(NullPointerException.class,
            () -> FragmentIdentity.computeId(null, "postgresql"));
    }

    @Test
    void computeId_rejectsNullDialect() {
        assertThrows(NullPointerException.class,
            () -> FragmentIdentity.computeId("select 1", null));
    }
}
