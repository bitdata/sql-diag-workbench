# Changelog

## Unreleased — PERF-05 live mapping slice (2026-10-27)

- Add execution-scoped plan mappings to submitted SQL, AST relation spans, fragment candidates and diagnostic evidence, with explicit confidence and unknown results.
- Display mappings in plan node details and reject stale or mismatched execution/session/SQL responses.
- Cover GROUP BY and named WINDOW subqueries, response races and PostgreSQL/MySQL mapping boundaries. MySQL TREE currently lacks structured relation metadata and exposes root observation plus UNKNOWN edges.

Development acceptance only; application version remains 0.1.0. Mapping history across restarts, exact operator-to-predicate attribution and installer release remain outside this slice.

## Unreleased — M1 acceptance (2026-10-17)

- Add explicit source-relation checks and original-query source mapping for named JOIN, CTE, derived-table, readable-view, single-source-expression and set-operation investigations.
- Display independent source counts, typed key mappings, partial coverage and unknown reasons without inferring global absence or unique causality.
- Keep source checks tied to the current investigation, expose cancellation and timeouts, and make source evidence reachable in the results pane.
- Fix early Escape dismissal of the tab context menu.
- Freeze M1 acceptance to Windows x64, PostgreSQL 16.14, MySQL 8.0.46 and the documented business tasks; reconcile all 17 P0 capabilities with candidate and historical acceptance evidence.
- Add repeatable API, production DOM, desktop and credential-evidence validation carriers, plus candidate provenance and handoff records.

This entry records development acceptance. The accepted application remains version 0.1.0; no new installer, signed distribution, GitHub Release or automatic deployment is published by this merge.

- Reject unverified set-operation coercions during source mapping and protect complex-source read-only session setup with the preparation deadline; preserve explicit UNKNOWN results on failure.
