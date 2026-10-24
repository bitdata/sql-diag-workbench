package com.bitdata.sqlworkbench.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FindingStatusTest {

    @Test
    void allStatusValuesExist() {
        assertNotNull(FindingStatus.SUSPECTED);
        assertNotNull(FindingStatus.CONFIRMED);
        assertNotNull(FindingStatus.REFUTED);
    }

    @Test
    void defaultStatusInBuilderIsSuspected() {
        DiagnosticFinding finding = DiagnosticFinding.builder()
            .id("test-1")
            .ruleId("rule-1")
            .title("Test finding")
            .build();

        assertEquals(FindingStatus.SUSPECTED, finding.status());
        assertNull(finding.verificationResult());
    }

    @Test
    void builderCanSetStatusAndResult() {
        DiagnosticFinding finding = DiagnosticFinding.builder()
            .id("test-2")
            .ruleId("rule-1")
            .title("Confirmed issue")
            .status(FindingStatus.CONFIRMED)
            .verificationResult("3 rows found — issue confirmed")
            .build();

        assertEquals(FindingStatus.CONFIRMED, finding.status());
        assertEquals("3 rows found — issue confirmed", finding.verificationResult());
    }
}
