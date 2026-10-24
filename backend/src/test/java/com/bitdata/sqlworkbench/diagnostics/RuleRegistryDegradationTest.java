package com.bitdata.sqlworkbench.diagnostics;

import com.bitdata.sqlworkbench.diagnostics.trace.TraceContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuleRegistryDegradationTest {
    @Test void bothEntrypointsExposeFailureAndContinueOtherRules() {
        var app = mock(ApplicationContext.class);
        var broken = mock(DiagnosticRule.class);
        var healthy = mock(DiagnosticRule.class);
        when(broken.id()).thenReturn("broken");
        when(broken.displayName()).thenReturn("Broken rule");
        when(broken.severity()).thenReturn(DiagnosticRule.Severity.LOW);
        when(healthy.severity()).thenReturn(DiagnosticRule.Severity.LOW);
        when(broken.analyze(null, null, null)).thenThrow(new IllegalStateException("fault"));
        var context = new TraceContext("c", null, Map.of(), null, List.of());
        when(broken.analyzeTrace(context)).thenThrow(new IllegalStateException("fault"));
        var good = DiagnosticFinding.builder().id("good").ruleId("good").title("good").build();
        when(healthy.analyze(null, null, null)).thenReturn(List.of(good));
        when(healthy.analyzeTrace(context)).thenReturn(List.of(good));
        when(app.getBeansOfType(DiagnosticRule.class)).thenReturn(Map.of("bad", broken, "good", healthy));
        var registry = new RuleRegistry(app); registry.init();
        for (var findings : List.of(registry.analyzeAll(null, null, null), registry.analyzeAllTrace(context))) {
            assertTrue(findings.contains(good));
            var failure = findings.stream().filter(DiagnosticFinding::degraded).findFirst().orElseThrow();
            assertEquals("RULE_FAILED", failure.errorReason());
            assertEquals("broken", failure.ruleId());
            assertNull(failure.verificationSql());
        }
    }
}
