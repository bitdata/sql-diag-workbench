// backend/src/test/java/com/bitdata/sqlworkbench/testsupport/RequiresTestDatabaseConditionTest.java
package com.bitdata.sqlworkbench.testsupport;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FIX 3 (final-review M-4): the condition must surface the resolver's DETAILED reason
 * (TestDatabaseResolver.unavailableReason) in the JUnit skip report — the old code dropped
 * it for a hardcoded generic string, so the acceptance-gate §2 exception-message claim
 * never reached test reports. Stubbed via a proxied ExtensionContext — no real run needed,
 * mirroring how existing tests in this package use Assumptions rather than mocking fs.
 */
class RequiresTestDatabaseConditionTest {

    @RequiresTestDatabase(Dialect.POSTGRESQL)
    private static class PgGated { }

    private static ExtensionContext contextFor(AnnotatedElement element) {
        // evaluateExecutionCondition only ever touches getElement() — everything else throws.
        return (ExtensionContext) Proxy.newProxyInstance(
            RequiresTestDatabaseConditionTest.class.getClassLoader(),
            new Class<?>[] { ExtensionContext.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getElement" -> Optional.of(element);
                case "toString" -> "ExtensionContextStub";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static ConditionEvaluationResult evaluate(AnnotatedElement element) {
        return new RequiresTestDatabase.RequiresTestDatabaseCondition()
            .evaluateExecutionCondition(contextFor(element));
    }

    @Test
    void enabledWhenAnnotationAbsent() {
        ConditionEvaluationResult result = evaluate(RequiresTestDatabaseConditionTest.class);
        assertFalse(result.isDisabled());
        assertEquals("no annotation", result.getReason().orElse("<null>"));
    }

    @Test
    void disabledReasonCarriesResolverDetailVerbatim() {
        Assumptions.assumeTrue(!TestDatabaseResolver.isAvailable(Dialect.POSTGRESQL),
            "requires PG unavailable (no usable credentials on this machine)");
        ConditionEvaluationResult result = evaluate(PgGated.class);
        assertTrue(result.isDisabled());
        String reason = result.getReason().orElse("<null>");
        String detail = TestDatabaseResolver.unavailableReason(Dialect.POSTGRESQL).orElse("");
        assertFalse(detail.isEmpty(), "resolver must yield a detailed reason when unavailable");
        assertTrue(reason.contains(detail),
            () -> "skip reason must embed the resolver's detailed cause; got: " + reason);
        // The old generic string must no longer be the WHOLE story (FIX 3 regression pin):
        assertNotEquals("skipped: postgresql: test-databases.json missing or placeholder", reason);
    }

    @Test
    void reasonIsNeverSilentlyEmpty() {
        Assumptions.assumeTrue(!TestDatabaseResolver.isAvailable(Dialect.ORACLE),
            "requires ORACLE unavailable (no usable credentials on this machine)");
        ConditionEvaluationResult result = evaluate(OracleGated.class);
        String reason = result.getReason().orElse("");
        assertTrue(result.isDisabled() && !reason.isBlank(),
            "disabled() must always carry a non-empty reason");
        assertTrue(reason.startsWith("skipped: oracle: "), reason);
    }

    @RequiresTestDatabase(Dialect.ORACLE)
    private static class OracleGated { }
}
