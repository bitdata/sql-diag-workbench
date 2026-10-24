# JDBC Integration Tests

End-to-end tests that connect to a real PostgreSQL / MySQL / Oracle /
SqlServer server. By default they are **skipped** — they require a live
database reachable from the JVM, configured via the project's single
credential source: `test-databases.json` at the repository root.

## Why these tests exist

The 4 dialect strategies (`PostgreSqlStrategy`, `MySqlStrategy`,
`OracleStrategy`, `SqlServerStrategy`) are validated by ~170 mock-based
unit tests in the rest of the suite, but none of those tests
**actually connect to a database**. Unit tests verify that we wrote the
JDBC URL string correctly, but only a live connection can verify that
the driver accepts it, the user/password combo works, the schema is
reachable, the sample query parses and executes, and the EXPLAIN
prefix returns metadata.

These tests close that gap.

## Running them

All JDBC ITs are gated by `@RequiresTestDatabase(Dialect.X)` (see
`backend/src/test/java/com/bitdata/sqlworkbench/testsupport/`). The gate
surfaces the resolver's detailed cause verbatim in the JUnit skip
report when no usable entry exists.

To run them locally:

1. Populate `test-databases.json` at the repository root with a usable
   entry for the dialect you want to exercise (see
   `docs/test-databases.md` for the file shape and an example).
2. Invoke the suite under `mvn verify` (the failsafe phase runs the
   `@Tag("integration")` classes):

   ```bash
   cd backend
   mvn verify                                  # full suite
   mvn verify -Dtest=PostgresJdbcIntegrationTest  # one class
   ```

   Note: `mvn test` (the surefire unit phase) excludes
   `@Tag("integration")` — use `mvn verify` (or `mvn verify
   -Dtest=...` / `-Dit.test=...`) so failsafe picks the IT classes up.
3. Per-dialect skip behaviour (when the corresponding
   `test-databases.json` entry is missing / has placeholder password):

   | Class                          | Dialect    |
   |--------------------------------|------------|
   | `JdbcIntegrationTest`          | PostgreSQL |
   | `PostgresJdbcIntegrationTest`  | PostgreSQL |
   | `MySqlJdbcIntegrationTest`     | MySQL      |
   | `OracleJdbcIntegrationTest`    | Oracle     |
   | `SqlServerJdbcIntegrationTest` | SqlServer  |
   | `ExecutionEngineAnalyzeIntegrationTest`        | PostgreSQL |
   | `ExecutionEnginePerRequestTimeoutIntegrationTest` | PostgreSQL |

   Each IT class's `@RequiresTestDatabase` annotation produces an
   observable skip with the resolver's detailed cause — never a silent
   skip (CLAUDE.md rule #2).

## Migration notice for legacy env vars

Before task B (chore/debt-convergence-2026-09) the IT classes were
gated by `TEST_JDBC_PG_URL` / `TEST_JDBC_MYSQL_URL` /
`TEST_JDBC_ORACLE_URL` / `TEST_JDBC_SQLSERVER_URL` and
`JDBC_INTEGRATION_URL`. Those variables are **no longer read** as
credential/URL sources — `test-databases.json` is the only credential
source.

If any of those legacy env vars is still set in the JVM environment
when an IT runs, `LegacyEnvVarCompatNotice.warnIfLegacyEnvVarsStillSet()`
emits a single `WARNING` from the class's `@BeforeAll` listing the
present vars and pointing at `test-databases.json`. To silence the
notice, unset the legacy vars in your shell / CI configuration.

## Test credentials (local dev)

Populate `test-databases.json` (gitignored) at the repo root with the
schema documented in `docs/test-databases.md`. Example entries are in
`test-databases.example.json` (committed; carries the `REPLACE_ME`
placeholder so it never resolves to a usable entry).

## What's tested

Each per-dialect class runs 4–5 checks:

| Test | Verifies |
|------|----------|
| `connectsAndRunsEmployeesQuery` (PG/MySQL) | Strategy's JDBC URL connects; the 3-table INNER JOIN parses and returns the expected column count |
| `opensConnection_toOrclpdb` / `opensConnection_toAdventureWorks` (Oracle/SqlServer) | Driver accepts the resolver-built URL and the schema is reachable |
| `cteQueryParses` (PG only) | CTE / WITH syntax parses and returns metadata |
| `runsOracleQuery_selectDual` / `runsAdventureWorksQuery_simplePerson` | Simple round-trip on each vendor's native sample table |
| `runsOracleQuery_withSidUrl` | SID-form URL conversion (listener-refused errors are tolerated as server-config, not strategy bugs) |
| `reservedKeywordsDontShadowColumnNames` | The strategy's reserved-keyword set doesn't accidentally contain common employees-table column names — a regression here would make real SQL like `SELECT first_name FROM ...` fail |
| `explainPlanExecutes` / `getExplainSql_runsQueryWithoutParseError` / `explainPlanFor_executesWithoutParseError` | `getExplainSql(...)` runs without parse error and returns metadata |
| `readOnlyModeApplied` (PG/Oracle) | `configureReadOnly(conn)` actually flips `conn.isReadOnly()` |
| `readOnlyModeNoOp` (MySQL) / `readOnlyMode_applied` (SqlServer) | `configureReadOnly` doesn't throw on dialects that don't support a post-connection toggle |
| `dialectProfile_uses{Oracle,Mssql}SqlDialect` (Oracle/SqlServer) | The strategy's Calcite dialect profile matches the vendor |
| `jdbcUrl_serviceNameAndSidForms` (Oracle only, DB-free) | The strategy's SERVICE_NAME / SID / null-connectionType URL builders all match product output |

## What's NOT tested (yet)

- **Result correctness** — we check column count and row-count
  bounds, not row content. Adding seeded-data assertions is a future
  task.
- **Transactional behaviour** — read-only mode + concurrent writes,
  connection pool exhaustion, etc.

## Live verification log

- 2026-09-06: first live-PG/MySQL run with `test-databases.json`; gate
  wiring confirmed via `FullChainPostgresIntegrationTest` /
  `FullChainMysqlIntegrationTest`.
- 2026-09-09 (task B): 11 legacy-env-gated ITs migrated to
  `@RequiresTestDatabase`; `test-databases.json` is the single
  credential source; Oracle / SqlServer entries are now gated
  uniformly (previously no gate existed for them); compatibility WARN
  emitted when old env vars are still set.
