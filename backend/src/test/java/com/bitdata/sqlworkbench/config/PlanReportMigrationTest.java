package com.bitdata.sqlworkbench.config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class PlanReportMigrationTest {
 @Test void migrationPreservesReportsAndReferencingTriggersAndIsRepeatable(@TempDir Path temp) {
  var ds=new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve("migration.db"));ds.setDriverClassName("org.sqlite.JDBC");
  var jdbc=new JdbcTemplate(ds);
  jdbc.execute("CREATE TABLE migration_parent (id TEXT)");
  jdbc.execute("CREATE TABLE diagnostic_reports (id TEXT PRIMARY KEY,session_id TEXT NOT NULL,fragment_id TEXT NOT NULL,findings_json TEXT NOT NULL,created_at TEXT NOT NULL,evaluation_json TEXT)");
  jdbc.execute("INSERT INTO diagnostic_reports VALUES ('old','session','fragment','[]','2026-10-24T00:00:00Z',NULL)");
  jdbc.execute("CREATE TRIGGER migration_delete AFTER DELETE ON migration_parent BEGIN DELETE FROM diagnostic_reports WHERE session_id=OLD.id; END");
  jdbc.execute("CREATE INDEX migration_report_session ON diagnostic_reports(session_id)");
  var config=new SqliteConfig();config.jdbcTemplate(ds);config.jdbcTemplate(ds);
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM sqlite_master WHERE name='migration_report_session'",Integer.class));
  assertEquals("fragment",jdbc.queryForObject("SELECT fragment_id FROM diagnostic_reports WHERE id='old'",String.class));
  jdbc.execute("INSERT INTO diagnostic_reports VALUES ('new','session',NULL,'[]','2026-10-24T00:00:00Z','{}')");
  assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM diagnostic_reports",Integer.class));
  jdbc.execute("INSERT INTO migration_parent VALUES ('session')");jdbc.execute("DELETE FROM migration_parent");
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM diagnostic_reports",Integer.class));
 }
}
