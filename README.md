# SQL Diagnostic Workbench

English | [简体中文](README.zh-CN.md)

A desktop tool for diagnosing incorrect SQL results and investigating query performance. Break a complex query into smaller parts, inspect intermediate results, and narrow down where rows disappear or multiply.

**Free for personal and business use · AGPL-3.0 open source with a separate commercial licensing option.**

## Missing rows, unexpected results, or a slow query?

Debugging complex SQL often means commenting out JOINs, running subqueries separately, and checking intermediate row counts. SQL Diagnostic Workbench brings those steps into one workspace so you can follow the query structure and narrow down the problem.

- **Expected rows are missing:** inspect intermediate results to find the JOIN or filter that excludes them.
- **Rows are duplicated or multiply unexpectedly:** compare results before and after a JOIN to locate where row counts grow.
- **A rewrite may have changed the result:** compare the original and revised fragments side by side, including their results and performance.
- **A query runs slowly:** inspect execution plans and diagnostic findings to identify execution steps worth investigating first.

## Core features

### Break down complex SQL and inspect each part

Parse SQL into an explorable structure containing CTEs, subqueries, JOINs, and filters. Select a fragment to execute it and inspect its data and row count, reducing the need to split SQL manually and copy it between query windows.

Start with a fragment that returns data, then inspect subsequent JOINs and filters to see where the results change. Available decomposition operations depend on the SQL structure.

### Compare two approaches side by side

Choose two fragments for A/B comparison and inspect their results and performance using the same database connection. Compare fragments within one query or across tabs to investigate the effect of a JOIN adjustment, filter change, or query rewrite.

Editing a fragment opens a new tab and preserves the original SQL, making it easier to try alternatives and refer back to the original query.

### Investigate slow queries with execution plans

Use Performance mode to inspect estimated plans and diagnostic findings to understand how the database intends to execute a query. PostgreSQL also supports actual plans for examining runtime behavior.

**An actual plan (ANALYZE) executes the query** and can put load on the database. Choose an appropriate time to run it.

### Save your investigation and resume later

Keep connections, query workspaces, and history locally so you can resume after closing the application normally. Use history cleanup settings to manage retention and disk usage.

The interface supports English and Simplified Chinese, with light, dark, and high-contrast themes.

## Supported environment

The current supported desktop platform is **Windows x64**.

| Database | Query execution | Estimated plan | Actual plan | Tested version |
| --- | --- | --- | --- | --- |
| PostgreSQL | Yes | Yes | Yes | 16.14 |
| MySQL | Yes | Yes | Not currently available in this tool | 8.0.46 |

The tool focuses on diagnosing `SELECT` queries, including queries with `WITH` clauses. SQL parsing and decomposition have limits; support for the databases above does not guarantee support for every query or database version.

You can parse SQL without a database connection. Executing queries and obtaining plans require a connection to the corresponding database.

## Planned features

- **Deeper query performance analysis and optimization:** build on the existing plan viewer to investigate bottlenecks using runtime metrics from the original query and offer optimization suggestions you can verify. Check that rewrites preserve results and evaluate performance changes under comparable conditions.
- **More database support:** expand beyond PostgreSQL and MySQL so users of other databases can explore SQL fragments, inspect intermediate results, and analyze execution plans.

These are future directions and are not all available yet. See release notes for specific databases, features, and availability.

## Installation

Visit [GitHub Releases](https://github.com/bitdata/sql-diag-workbench/releases) for available versions and Windows installers.

1. Download the Windows x64 installer from a release.
2. Run the installer and launch **SQL Diagnostic Workbench**.
3. Choose your preferred language in **Settings → Language**.

The Windows installer bundles the Java runtime. No separate Java installation is required.

## First use

1. **Connect to a database.** Open the connection manager, create a PostgreSQL or MySQL connection, enter its host, port, database, username, and password, then test and save it. A read-only database account is recommended for query diagnosis.
2. **Paste your SQL.** Open a query tab and paste the query you want to investigate. Select the matching SQL dialect.
3. **Parse the query.** Click **Parse** or press `Ctrl+Shift+Enter` to explore its structure.
4. **Inspect intermediate results.** Select a fragment and execute it in Data mode. Work through the relevant JOINs, filters, or subqueries to see where the result changes. Use **Execute SQL** or `Ctrl+Enter` to run the SQL in the current editor.
5. **Compare alternatives.** Choose **Compare** for a fragment, then select another fragment from the tree or another tab. Both sides run against the selected connection. Editing a fragment into a different query opens a new tab and preserves the original.
6. **Investigate performance.** Switch to Performance mode and obtain an estimated plan. For PostgreSQL, you can also request an actual plan to inspect runtime behavior.

Set query timeouts and result row limits in **Settings**. A displayed result may be limited; diagnostic findings and partial results are clues to investigate, not proof that a query is correct or that a row does not exist.

## Keyboard shortcuts

| Shortcut | Action |
| --- | --- |
| `Ctrl+Shift+Enter` | Parse SQL |
| `Ctrl+Enter` | Execute SQL in the current editor |
| `F5` | Run the selected fragment in Data mode |
| `Ctrl+Shift+F` | Format SQL |
| `Ctrl+N` | Open a new query tab |

## Saved data and history

Connection settings, workspaces, and history are stored locally. Queries are sent to the database connection you select.

On Windows, saved passwords are encrypted for the current Windows user. If you move the application data to a different Windows account, re-enter the passwords.

Closing the application normally saves open workspaces; a forced shutdown or power loss may leave recent changes unsaved. Uninstalling preserves application data by default.

In **Settings → Data Retention**, configure retention periods and storage limits, preview cleanup, or manually remove old history. Apply your changes to activate the cleanup policy. Current workspaces and records needed for recovery are protected.

## Feedback

Report problems or suggest improvements through [GitHub Issues](https://github.com/bitdata/sql-diag-workbench/issues). Include the application version, database type and version, a minimal SQL example, and the expected and actual behavior. Remove passwords, connection secrets, and sensitive business data before sharing SQL or screenshots.

## License

The community edition uses the standard [GNU AGPL v3](LICENSE) (`AGPL-3.0-only`). It belongs to the GPL family, with an additional source-availability requirement for users interacting remotely with a modified version over a network.

Personal use, use within a business, and paid SQL consulting are allowed free of charge. Modification, redistribution, and charging fees are also allowed when you comply with the AGPL, including applicable corresponding-source and network-interaction obligations.

For closed-source integrations that cannot comply with the AGPL, contact [Bitdata](https://github.com/bitdata) to arrange a separate commercial license.

See [NOTICE](NOTICE) for copyright and scope, and [licensing details (Chinese)](LICENSING.md) for common usage scenarios.
