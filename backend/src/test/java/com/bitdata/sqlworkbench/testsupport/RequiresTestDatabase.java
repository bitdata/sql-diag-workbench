// backend/src/test/java/com/bitdata/sqlworkbench/testsupport/RequiresTestDatabase.java
package com.bitdata.sqlworkbench.testsupport;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ExtendWith(RequiresTestDatabase.RequiresTestDatabaseCondition.class)
public @interface RequiresTestDatabase {
    Dialect[] value();

    class RequiresTestDatabaseCondition implements org.junit.jupiter.api.extension.ExecutionCondition {
        @Override
        public org.junit.jupiter.api.extension.ConditionEvaluationResult evaluateExecutionCondition(
                org.junit.jupiter.api.extension.ExtensionContext context) {
            var annotation = context.getElement()
                .map(e -> e.getAnnotation(RequiresTestDatabase.class))
                .orElse(null);
            if (annotation == null) {
                return org.junit.jupiter.api.extension.ConditionEvaluationResult.enabled("no annotation");
            }
            StringBuilder reasons = new StringBuilder();
            for (Dialect d : annotation.value()) {
                // Surface the resolver's DETAILED cause verbatim (file-missing-with-searched-root
                // / placeholder-password / no-dialect-entry / parse-error) in the JUnit skip
                // report — CLAUDE.md rule #2; unavailableReason never yields empty text, so the
                // reason can never silently degrade to null/"".
                var unavailable = TestDatabaseResolver.unavailableReason(d);
                if (unavailable.isPresent()) {
                    if (reasons.length() > 0) reasons.append("; ");
                    reasons.append(d.name().toLowerCase()).append(": ").append(unavailable.get());
                }
            }
            if (reasons.length() > 0) {
                return org.junit.jupiter.api.extension.ConditionEvaluationResult
                    .disabled("skipped: " + reasons);
            }
            return org.junit.jupiter.api.extension.ConditionEvaluationResult.enabled("all dialects available");
        }
    }
}
