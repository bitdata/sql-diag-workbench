package com.bitdata.sqlworkbench.testsupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sentinel for the surefire/failsafe split (Task 2).
 *
 * <p>This class carries {@code @Tag("integration")} and is named
 * {@code SurefireExcludesITTest}, so the surefire/failsafe split configured in
 * {@code pom.xml} determines whether it runs:
 * <ul>
 *   <li>{@code mvn test} → surefire matches {@code **\/*Test.java} and would
 *       pick this up, but {@code <excludedGroups>integration</excludedGroups>}
 *       skips it. Result: no failure.</li>
 *   <li>{@code mvn verify} → failsafe matches {@code **\/*ITTest.java} via the
 *       custom include pattern, and {@code <groups>integration</groups>} selects
 *       this test. Result: target/failsafe-reports/ is created and the assertion
 *       below passes.</li>
 * </ul>
 *
 * <p>Without the split, surefire runs the test and the assertion fails because
 * surefire does not create {@code target/failsafe-reports/}. The directory
 * existence is a reliable distinguisher: surefire writes to
 * {@code target/surefire-reports/}, failsafe writes to
 * {@code target/failsafe-reports/}.
 */
@Tag("integration")
class SurefireExcludesITTest {

    @Test
    void surefireShouldSkipIntegrationTag() {
        Path failsafeReports = Paths.get("target", "failsafe-reports");
        assertTrue(Files.exists(failsafeReports),
            "Expected to run under failsafe (target/failsafe-reports should exist), "
            + "but it does not. If surefire ran this @Tag(\"integration\") test, "
            + "the surefire/failsafe split in pom.xml is missing or incomplete.");
    }
}
