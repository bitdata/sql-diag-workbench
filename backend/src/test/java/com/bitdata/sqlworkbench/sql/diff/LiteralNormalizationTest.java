package com.bitdata.sqlworkbench.sql.diff;

import com.bitdata.sqlworkbench.fragment.FragmentIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LiteralNormalizationTest {

    @Test
    void literalsWithDifferentContentGetDifferentCanonicalText() throws Exception {
        String a = SqlCanonicalizer.canonicalizeSql("SELECT x FROM t WHERE x = 'a b'", "postgresql");
        String b = SqlCanonicalizer.canonicalizeSql("SELECT x FROM t WHERE x = 'ab'", "postgresql");
        assertNotEquals(a, b, "Literal content must survive canonicalization");
        assertNotEquals(
            FragmentIdentity.computeId(a, "postgresql"),
            FragmentIdentity.computeId(b, "postgresql"),
            "Different literals must not collide on fragment identity");
    }

    @Test
    void literalsWithDifferentCaseGetDifferentCanonicalText() throws Exception {
        String a = SqlCanonicalizer.canonicalizeSql("SELECT x FROM t WHERE x = 'ABC'", "postgresql");
        String b = SqlCanonicalizer.canonicalizeSql("SELECT x FROM t WHERE x = 'abc'", "postgresql");
        assertNotEquals(a, b, "Literal case is significant");
    }

    @Test
    void nonLiteralTokensAreStillNormalized() throws Exception {
        String a = SqlCanonicalizer.canonicalizeSql("SELECT x FROM t WHERE X > 0", "postgresql");
        String b = SqlCanonicalizer.canonicalizeSql("select x from t where x>0", "postgresql");
        assertEquals(a, b, "Whitespace/case outside literals must still normalize");
    }
}
