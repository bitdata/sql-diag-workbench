package com.bitdata.sqlworkbench.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticReportStoreTest {

    private DiagnosticReportStore store;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // SingleConnectionDataSource reuses one connection, which is required
        // for in-memory SQLite (each connection gets its own isolated database).
        var ds = new SingleConnectionDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite::memory:");
        ds.setSuppressClose(true);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
            CREATE TABLE diagnostic_reports (
                id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                fragment_id TEXT NOT NULL,
                findings_json TEXT NOT NULL,
                evaluation_json TEXT,
                created_at TEXT NOT NULL
            )
            """);
        store = new DiagnosticReportStore(jdbc);
    }

    @Test
    void saveAndFindBySessionId() {
        var report = new DiagnosticReport(
            "r1", "s1", "f1", "[{\"ruleId\":\"test\"}]", Instant.parse("2026-07-20T10:00:00Z")
        );
        store.save(report);

        List<DiagnosticReport> results = store.findBySessionId("s1");
        assertEquals(1, results.size());
        assertEquals("r1", results.get(0).id());
        assertEquals("s1", results.get(0).sessionId());
        assertEquals("f1", results.get(0).fragmentId());
    }

    @Test
    void findBySessionIdReturnsEmptyForUnknownSession() {
        List<DiagnosticReport> results = store.findBySessionId("unknown");
        assertTrue(results.isEmpty());
    }

    @Test
    void findLatestBySessionAndFragment() {
        store.save(new DiagnosticReport("r1", "s1", "f1", "[]", Instant.parse("2026-07-20T10:00:00Z")));
        store.save(new DiagnosticReport("r2", "s1", "f1", "[]", Instant.parse("2026-07-20T11:00:00Z")));

        Optional<DiagnosticReport> latest = store.findLatestBySessionAndFragment("s1", "f1");
        assertTrue(latest.isPresent());
        assertEquals("r2", latest.get().id());
    }

    @Test
    void findLatestBySessionAndFragmentReturnsEmpty() {
        Optional<DiagnosticReport> result = store.findLatestBySessionAndFragment("s1", "f1");
        assertTrue(result.isEmpty());
    }

    @Test
    void findBySessionIdOrdersByCreatedAtDesc() {
        store.save(new DiagnosticReport("r1", "s1", "f1", "[]", Instant.parse("2026-07-20T10:00:00Z")));
        store.save(new DiagnosticReport("r2", "s1", "f2", "[]", Instant.parse("2026-07-20T11:00:00Z")));
        store.save(new DiagnosticReport("r3", "s1", "f3", "[]", Instant.parse("2026-07-20T09:00:00Z")));

        List<DiagnosticReport> results = store.findBySessionId("s1");
        assertEquals(3, results.size());
        assertEquals("r2", results.get(0).id()); // newest first
        assertEquals("r1", results.get(1).id());
        assertEquals("r3", results.get(2).id());
    }
}
