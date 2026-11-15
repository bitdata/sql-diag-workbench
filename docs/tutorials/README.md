# Learn SQL data diagnosis and performance investigation with sample databases

English | [简体中文](README.zh-CN.md)

Start with missing orders, duplicate rows, and incorrect totals. Then investigate employee history, real sales orders, and execution plans using the employees and AdventureWorks databases.

Each SQL file contains independent queries. **Copy one query at a time** into the workbench. Keep A and B in separate query tabs. All examples are read-only; they do not create tables, load data, or change indexes.

## Sample data sources and preparation

| Source | Cases | Database objects | Preparation |
| --- | --- | --- | --- |
| [datacharmer/test_db](https://github.com/datacharmer/test_db) | 4, 5, 6, 9 | MySQL: `employees.employees`, `employees.salaries`, `employees.titles`. PostgreSQL: the same tables under `public` | The upstream import scripts primarily target MySQL. PostgreSQL needs an adapted import; do not run the MySQL scripts unchanged |
| [microsoft/sql-server-samples: AdventureWorks](https://github.com/microsoft/sql-server-samples/tree/master/samples/databases/adventure-works) | 7 | SQL Server: `Sales.SalesOrderHeader`, `Sales.SalesOrderDetail` | Use the full AdventureWorks OLTP sample. AdventureWorksLT and DW have different schemas |
| [oracle-samples/db-sample-schemas](https://github.com/oracle-samples/db-sample-schemas) | No current case reads its business tables | Oracle cases 1–3 and 8 use inline rows and `dual` | This is the upstream sample-schema reference. Connecting to FREEPDB1 does not establish that HR or CO is installed; these cases do not require either |
| Inline orders, customers, and tags supplied by this tutorial | 1–3, 8 | SELECT literals executed by the connected database | Useful for checking every row; these small datasets do not demonstrate large-data performance gains |

These references identify the sample models and where to obtain them. They do not certify the exact import history, upstream revision, or PostgreSQL conversion used by a local installation. Check your schemas before running cases that read real tables.

Read-only counts observed on November 14, 2026:

| Database | Table | Rows |
| --- | --- | --- |
| PostgreSQL / MySQL employees | employees | 300024 |
| PostgreSQL / MySQL employees | salaries | 2844047 |
| PostgreSQL / MySQL employees | titles | 443308 |
| SQL Server AdventureWorks | SalesOrderHeader | 31465 |
| SQL Server AdventureWorks | SalesOrderDetail | 121317 |

Check your installation with `SELECT COUNT(*) FROM schema.table`. Changed data may produce different counts and answers; investigate the difference rather than assuming a tool failure.

## Choose a case

| Case | Question | What you learn |
| --- | --- | --- |
| 1. Missing orders | Why does a LEFT JOIN still lose an order? | Separate a join from its filter and trace a specific output key |
| 2. Duplicate orders | Why does one order appear twice? | Existence versus quantity, expected maximum, and compound keys |
| 3. Inflated totals | Why is the amount wrong when each customer appears once? | Inspect detail rows before aggregation |
| 4. Employee history | Why do five employees produce 88 rows? | Separate history joins and define the time grain |
| 5. Index predicates | Why do equivalent queries read different amounts of data? | Compare results and estimated/actual access paths |
| 6. Repeated work | Is preaggregation better than 200 subqueries? | Test an optimization hypothesis, including a small-range counterexample |
| 7. AdventureWorks totals | Why is one order counted 12 times? | Follow a real header/detail relationship |
| 8. Oracle plan reading | How should native plan metrics be interpreted? | Separate estimates from last-execution statistics |
| 9. Large salary table | Why does the same aggregate require very different work? | Compare a 2.84-million-row scan with an index range scan |

Try 1 → 2 → 3 first. For a visible performance demonstration, use case 9; cases 5 and 6 teach additional plan-reading and comparison skills. Oracle inline queries include `FROM dual`.

### SQL files

| Case | PostgreSQL | MySQL | SQL Server | Oracle |
| --- | --- | --- | --- | --- |
| 1 | [SQL](sql/postgresql/01-missing-order.sql) | [SQL](sql/mysql/01-missing-order.sql) | [SQL](sql/sqlserver/01-missing-order.sql) | [SQL](sql/oracle/01-missing-order.sql) |
| 2 | [SQL](sql/postgresql/02-duplicate-order.sql) | [SQL](sql/mysql/02-duplicate-order.sql) | [SQL](sql/sqlserver/02-duplicate-order.sql) | [SQL](sql/oracle/02-duplicate-order.sql) |
| 3 | [SQL](sql/postgresql/03-inflated-total.sql) | [SQL](sql/mysql/03-inflated-total.sql) | [SQL](sql/sqlserver/03-inflated-total.sql) | [SQL](sql/oracle/03-inflated-total.sql) |
| 4 | [SQL](sql/postgresql/04-employee-history.sql) | [SQL](sql/mysql/04-employee-history.sql) | — | — |
| 5 | [SQL](sql/postgresql/05-index-predicate.sql) | [SQL](sql/mysql/05-index-predicate.sql) | — | — |
| 6 | [SQL](sql/postgresql/06-repeated-work.sql) | [SQL](sql/mysql/06-repeated-work.sql) | — | — |
| 7 | — | — | [SQL](sql/sqlserver/07-adventureworks-total.sql) | — |
| 9 | [SQL](sql/postgresql/09-large-salary-scan.sql) | [SQL](sql/mysql/09-large-salary-scan.sql) | — | — |

SQL comments currently use Chinese labels A/B/C; the statements and those labels match both guides.

## Common controls and evidence checks

1. Open a query tab, select its connection and dialect, paste one query, and click **Parse** (`Ctrl+Shift+Enter`).
2. Select the root to inspect the complete query. Right-click a node, or click its `⋯` button, and select a rule such as **Strip Clause** or **Split JOIN**. The triangle only expands existing children; it does not generate fragments.
3. Select each generated fragment and inspect its SQL before executing it. In Data mode, use **Execute fragment**, **Execute SQL**, or `Ctrl+Enter` for the visible SQL. Check that the displayed result belongs to the execution you intended.
4. In the results area of **Data** mode, open **Row Trace**. Choose **Existence** or **Quantity**, enter an actual output column label, its type, and its value, then click **Trace**. Use `DECIMAL` for numeric keys. Oracle labels often use uppercase, such as `ID`; copy the actual header.
5. To change trace conditions or submit an expanded graph, finish the current trace and click **Clear**, then submit the new inputs. **Re-trace** reuses the previous submitted graph and conditions.
6. To rewrite a complete query, select its root, use **Edit SQL in new tab**, edit, and save. You can also open another tab and paste B directly. The source query remains available. The root represents the current tab's full SQL; the edit-origin link returns to the source tab.
7. To collect a plan, select the intended root, switch to **Performance**, open **Analyze**, and choose **Estimated Plan** or **Actual Plan (ANALYZE) — runs query**. Opening the menu alone does not collect anything. Use **Plan** to read the plan and **Diagnostics** to inspect findings.

Define the expected grain first. One order per row suggests an expected maximum of 1 for the order key. One row per order and tag requires a compound key. The tool does not decide this business rule for you.

Generated labels may include `Remove WHERE` or scope information. Identify a fragment by its SQL changes, not its position in the tree. If a rule is unavailable or a candidate fails, record the limitation and stop that check. A skipped, failed, timed-out, or unprojected-key step is not evidence of zero rows.

Fragments are independent logical experiments, not measurements of the original query's physical operators. Separate observations do not guarantee a shared data snapshot.

## Case 1: Why does a LEFT JOIN still lose order 4?

**Source:** inline rows in `01-missing-order.sql`; no business tables.

**Business requirement:** retain every order and attach customer information only for east-region customers. Orders 1 and 2 belong to east customers, order 3 to a west customer, and order 4 has no matching customer. A puts `c.region = 'east'` in WHERE.

### Phase 1: Decompose and locate the difference

| Step | Action | Expected check |
| --- | --- | --- |
| 1 | Paste A in a new tab and choose the matching connection/dialect | SQL contains LEFT JOIN, `WHERE c.region = 'east'`, and `ORDER BY o.id` |
| 2 | Parse, select the root, and execute in Data mode | `(1,east), (2,east)`; order 4 is absent |
| 3 | Right-click the root → **Strip Clause**, then expand its children | Removing WHERE and removing ORDER BY are separate candidates, not successive deletions |
| 4 | Select the `Remove WHERE` candidate and inspect its SQL | WHERE is removed; LEFT JOIN and its original ON condition remain |
| 5 | Execute that fragment | `(1,east), (2,east), (3,west), (4,NULL)` |
| 6 | Open Row Trace → Existence; enter output `id` / `ID`, `DECIMAL`, `4` → Trace | More than one fragment is submitted; root lacks 4 while the no-WHERE experiment contains it. Read any other skipped-step reasons |
| 7, optional | Clear the finished trace; right-click the no-WHERE fragment → Split JOIN, then inspect and execute the candidate retaining orders without customers | It still projects the order key and contains 1–4. Removed customer columns need not survive. If unavailable, step 5 still supplies the filter evidence |

The difference identifies a logical filtering boundary. It does not establish the physical execution order of database operators.

### Phase 2: Rewrite and verify the business requirement

| Step | Action | Expected check |
| --- | --- | --- |
| 8 | From A's root, edit into a new tab and move the region condition into ON, or paste B separately | `ON o.customer_id = c.cid AND c.region = 'east'`; the original WHERE condition is gone; A remains |
| 9 | Parse and execute B's root | `(1,east), (2,east), (3,NULL), (4,NULL)` |
| 10 | Compare A, the no-WHERE experiment, and B | B retains every order but only attaches east customer information. Order 3 is west in the experiment and NULL in B; those queries are not interchangeable |

**Checklist:** `[ ] A has two rows` · `[ ] no-WHERE has four and west for order 3` · `[ ] trace evidence agrees` · `[ ] B has four and NULL for order 3` · `[ ] A remains`.

If the business only wanted east orders, A was already appropriate. Missing rows alone do not prove a defect.

## Case 2: Existence succeeds, but why is the order duplicated?

**Source:** inline `02-duplicate-order.sql`. **Requirement:** one row per order having any tag. Order 1 has red and blue; order 3 has green.

### Phase 1: Decompose and investigate quantity

| Step | Action | Expected check |
| --- | --- | --- |
| 1 | Paste A, parse, select the root, and execute in Data mode | `1, 1, 3` |
| 2 | Root → Split JOIN; inspect and execute the candidate retaining orders and removing tags | Order 1 appears once in that independent orders fragment; record unavailable candidates |
| 3 | Row Trace → Existence; key `id` / `ID`, DECIMAL, 1 → Trace | Root finds the key; this does not exclude duplicates |
| 4 | Clear; choose Quantity, same key, expected maximum 1 → Trace | Root matches 2 rows |

### Phase 2: Rewrite and compare grains

| Step | Action | Expected check |
| --- | --- | --- |
| 5 | Save B into a separate tab, parse and execute | EXISTS returns `1, 3`; A remains |
| 6 | Open C separately, parse and execute | `(1,blue), (1,red), (3,green)` |
| 7 | On C, trace Quantity with `id=1` (DECIMAL), Add key `tag=red` (TEXT), expected maximum 1 | Compound key matches one row |

**Checklist:** `[ ] A has three rows` · `[ ] existence finds 1` · `[ ] quantity is 2` · `[ ] B has two rows` · `[ ] C compound key matches one`.

The JOIN generates one row per tag. That is correct for C's grain. Do not add DISTINCT before deciding the required grain.

## Case 3: Each customer appears once, so why is the total doubled?

**Source:** inline `03-inflated-total.sql`. Order 1 has amount 100 and two tags; order 3 has amount 70 and one tag.

**Requirement:** sum amounts of tagged orders by customer, counting each order once. Untagged orders 2 and 4 are excluded.

### Phase 1: Check the aggregate and its detail

| Step | Action | Expected check |
| --- | --- | --- |
| 1 | Parse and execute A's root | `(10,200), (20,70)` |
| 2 | Quantity trace on output `customer_id`, DECIMAL, 10, expected maximum 1 | One matching aggregate row; quantity does not validate total |
| 3 | Copy A into a separate detail-check tab. Manually replace SELECT with `o.id, o.customer_id, o.amount, t.tag`, remove GROUP BY, and order by `o.id, t.tag` | This is an explicit manual rewrite, not an automatic remove-aggregation operation; retain JOIN conditions |
| 4 | Parse and execute the detail check | Order 1 has two amount-100 rows, blue and red; order 3 has amount 70 |

### Phase 2: Preserve order grain and verify

| Step | Action | Expected check |
| --- | --- | --- |
| 5 | Open B separately, parse and execute | EXISTS preserves order grain; `(10,100), (20,70)` |
| 6 | Review A, detail check, and B | Two copies of 100 explain 200; B meets the count-once requirement |

**Checklist:** `[ ] A total 200` · `[ ] customer quantity still passes` · `[ ] two detail copies of 100` · `[ ] B total 100`.

`SUM(DISTINCT amount)` is not a general repair: different orders can have the same amount.

## Case 4: Why do five employees produce 88 rows?

**Source:** real employees, salaries, and titles tables following datacharmer/test_db; use `04-employee-history.sql`. PostgreSQL uses `public`, MySQL uses `employees`.

**Requirement:** current salary and title for employees 10001–10005. A joins both history tables by employee number alone.

### Phase 1: Decompose the historical joins

| Step | Action | Expected check |
| --- | --- | --- |
| 1 | Parse and execute A's root in Data mode | The observed sample returns 88 rows with combinations of salary and title start dates |
| 2 | Root → Split JOIN; inspect a candidate retaining employee key and salary history but removing title history, then execute | Multiple salary records per employee; further splitting may be needed. Identify by SQL, not node number |
| 3 | Likewise inspect title history and count the target employee's records | N salary records and M title records produce N×M combinations without a temporal condition |
| 4 | If candidates are unavailable, manually create separate employees–salaries and employees–titles check queries with the same employee range; remove other-table projections and sort references | Record these as manual checks, not successful automatic decompositions |
| 5 | Back on A: Quantity trace `emp_no` as displayed, DECIMAL, 10001, maximum 1 | Multiple matches; compare with history counts. Clear before resubmitting changed conditions or graph |

### Phase 2: Rewrite for current state

| Step | Action | Expected check |
| --- | --- | --- |
| 6 | Edit A's root into a new tab using B, or paste B separately | Both history tables have `to_date = '9999-01-01'`; A remains |
| 7 | Parse and execute B | Five rows in the observed data; employee 10001 has salary 88958 and title Senior Engineer |
| 8 | Check the business definition | B answers current state only; historical analysis requires a defined interval-overlap condition |

**Checklist:** `[ ] A 88 rows` · `[ ] N×M combinations explained` · `[ ] both effective-date predicates present` · `[ ] B five rows`.

INNER JOIN still excludes employees without a current matching record. Retaining all employees needs a separately designed LEFT JOIN.

## Case 5: Same employee, different amount of work

**Source:** actual employees table, 300024 rows in the observed data. Use `05-index-predicate.sql`. Case 9 gives a larger performance demonstration.

**Requirement:** employee 10001. For this integer key, `emp_no + 0 = 10001` and `emp_no = 10001` return the same data.

### Phase 1: Establish results and access-path evidence

| Step | Action | Expected check |
| --- | --- | --- |
| 1 | Parse A, select its root, execute in Data mode | One employee: 10001, Georgi Facello |
| 2 | Root → Performance → Analyze → Estimated Plan → Plan | Record access path, predicate, and estimates; estimates are not actual read counts |
| 3 | Collect Actual Plan (ANALYZE) — runs query | Record native evidence; UI elapsed time is not operator time |

### Phase 2: Rewrite the predicate and compare

| Step | Action | Expected check |
| --- | --- | --- |
| 4 | Edit A's root into a new tab using B; parse, select root, execute in Data mode | Same columns, values, and multiplicity; A remains |
| 5 | Collect B's estimated and actual plans | Observed PostgreSQL: parallel sequential scan for A, index scan for B. MySQL: A scans 300024 rows; B says `Rows fetched before execution` |
| 6 | Follow the repeated-measurement procedure below | Separate warm-up effects and unstable timing differences |

**Checklist:** `[ ] same employee` · `[ ] A access path recorded` · `[ ] B access path recorded` · `[ ] estimates distinguished from actuals`.

The optimizer may complete a constant primary-key lookup before normal execution. Do not require a particular index-node label or assume identical behavior across versions.

## Case 6: Does preaggregation beat 200 subqueries?

**Source:** actual employees and salaries tables; `06-repeated-work.sql`. This is a small-range counterexample exercise, not a guaranteed speedup demonstration.

**Requirement:** historical maximum salary for employees 10001–10200, retaining employees without salary records with NULL.

### Phase 1: Observe repeated work

| Step | Action | Expected check |
| --- | --- | --- |
| 1 | Parse A, select root, execute in Data mode | 200 rows in the observed sample; retain complete employee/maximum-salary results |
| 2 | Collect A's actual plan in Performance mode | Correlated aggregation executed 200 times on both observed databases; another optimizer version may rewrite it |
| 3 | Inspect salary lookup access path and rows per lookup | Indexed lookups can be cheap; 200 loops alone do not prove a bottleneck |

### Phase 2: Preaggregate and test the hypothesis

| Step | Action | Expected check |
| --- | --- | --- |
| 4 | Open B separately, parse, select root, execute | All 200 rows agree, not just the count or first page |
| 5 | Inspect B's SQL | Same employee range inside preaggregation; LEFT JOIN preserves employees without salaries; no accidental whole-table aggregate |
| 6 | Collect B's actual plan | Inspect aggregation, joining, and materialization. MySQL may still perform 200 lookups into the materialized result |
| 7 | Repeat measurements as described below | Saved PostgreSQL single samples were A 0.566 ms, B 0.546 ms: too close to establish a stable winner |

**Checklist:** `[ ] complete results equal` · `[ ] A repeated work observed` · `[ ] B added work inspected` · `[ ] no unsupported speedup claim`.

Reducing one operator's loops does not necessarily reduce total cost. Keeping A is reasonable when B has no stable benefit.

## Case 7: Why is an AdventureWorks order counted 12 times?

**Source:** actual AdventureWorks OLTP `Sales.SalesOrderHeader` and `Sales.SalesOrderDetail` tables; `07-adventureworks-total.sql`.

**Requirement:** sum TotalDue for orders having details, counting each header once. Restrict the investigation to order 43659.

### Phase 1: Follow the aggregate back to detail rows

| Step | Action | Expected check |
| --- | --- | --- |
| 1 | Parse A using the AdventureWorks connection and SQL Server dialect; execute root | detail_count 12, header_total 23153.2339, repeated_total 277838.8068 |
| 2 | Open B in a detail-check tab, parse and execute | 12 detail rows repeating the same TotalDue |
| 3 | On **B**, Row Trace → Quantity; output SalesOrderID, DECIMAL, 43659, maximum 1 | 12 matches. Aggregated A would only match one output row |
| 4 | Clear; re-enter the order key and Add key SalesOrderDetailID, DECIMAL, using one detail ID from B; maximum 1 | Compound key matches one row |

### Phase 2: Preserve header grain

| Step | Action | Expected check |
| --- | --- | --- |
| 5 | Open C separately, parse and execute root | EXISTS tests for details without multiplying headers; total_due 23153.2339 |
| 6 | Compare A's header_total and repeated_total with C | C equals the header amount; the erroneous sum is 12 times that amount |
| 7 | Check field meanings | LineTotal is detail merchandise value; TotalDue includes other components such as tax and freight. They are not interchangeable |

**Checklist:** `[ ] 12 details` · `[ ] order key matches 12` · `[ ] compound key matches one` · `[ ] C amount 23153.2339`.

A uses MAX only to display this one order's original amount; it is not a general repair for multi-order totals.

## Case 8: Read an Oracle native plan with a small query

**Source:** inline rows and dual, using A from Oracle `02-duplicate-order.sql`. No oracle-samples business tables are read.

**Goal:** learn estimates and last-execution statistics, not large-table optimization.

### Phase 1: Check results and collect both plans

| Step | Action | Expected check |
| --- | --- | --- |
| 1 | Select Oracle connection/dialect, parse A, select root, execute in Data mode | `1, 1, 3` |
| 2 | Performance → Analyze → Estimated Plan → Plan | Read the operations actually present: joins, UNION ALL, sorting, or optimizer alternatives |
| 3 | Analyze → Actual Plan (ANALYZE) — runs query | Check capture status. Read capability/permission explanations if unavailable; an estimated plan is not an actual plan |

### Phase 2: Interpret metrics within their scope

| Step | Action | Expected check |
| --- | --- | --- |
| 4 | Distinguish estimated rows from available last-execution counters | LAST_STARTS, LAST_OUTPUT_ROWS, LAST_CR_BUFFER_GETS, LAST_ELAPSED_TIME describe different dimensions |
| 5 | Check elapsed-time units and scope | Native LAST_ELAPSED_TIME is in microseconds and includes child work; do not sum parent/child times or treat it as generic per-loop time |
| 6 | Open Diagnostics and examine the evidence behind each clue | Scanning and sorting a few rows can be reasonable; a suspected finding does not prove slowness |

**Checklist:** `[ ] three result rows` · `[ ] estimated/actual distinguished` · `[ ] metric units and scope checked` · `[ ] no small-data benchmark claim`.

Missing statistics do not mean zero cost. Oracle synchronous finding verification, full plan-to-SQL mapping, and native automatic optimization are not currently provided.

## Case 9: Different work for the same aggregate over 2.84 million salary records

**Source:** actual salaries table, 2844047 records on each observed employees installation; `09-large-salary-scan.sql`. No extra data or indexes are created.

**Requirement:** count historical salary records and sum their amounts for employees 10001–10200. A adds zero to emp_no before filtering; B filters the key directly. Both select the same records in this integer range. The sum of historical salary records is not current salary or a company's payroll expenditure.

### Phase 1: Establish large-table work

| Step | Action | Expected check |
| --- | --- | --- |
| 1 | Parse A, select root, execute in Data mode | One row: salary_rows 1909, salary_sum 120198562 |
| 2 | Root → Performance → Analyze → Estimated Plan | Record scan path, predicate, and estimates |
| 3 | Collect Actual Plan (ANALYZE) — runs query | Observed MySQL scans about 2.84 million rows and retains 1909; PostgreSQL uses a parallel sequential scan and rejects most rows |
| 4 | Inspect native scan rows, loops, and buffer metrics where available | PostgreSQL parallel per-loop row counts involve averages and rounding. The root's one row does not describe input size; do not sum parent/child buffer totals |

### Phase 2: Rewrite and verify the access-path improvement

| Step | Action | Expected check |
| --- | --- | --- |
| 5 | Edit A's root into a new tab and remove `+ 0` as in B, or open B separately; parse, select root, execute | Same single row `(1909,120198562)`; A remains |
| 6 | Collect B's estimated and actual plans | Observed PostgreSQL index scan and MySQL PRIMARY index range scan access the 1909-record salary range |
| 7 | Compare work and available buffer counters, then perform repeated measurements | Compare database evidence as well as UI elapsed time; record plan stability |
| 8 | Recheck business semantics | Same record set and employee range; narrowing the requested range is not an equivalent optimization |

One saved native-plan sample reported PostgreSQL execution time A 57.834 ms / B 0.255 ms. MySQL aggregate-root last-row times were approximately A 351 ms / B 0.575 ms. These fields have different meanings and collection overhead: do not compare database speeds across engines or advertise single-sample ratios as a product guarantee. Caches, hardware, statistics, and optimizer versions affect reproduction.

**Checklist:** `[ ] table size checked` · `[ ] identical aggregate result` · `[ ] A scan evidence` · `[ ] B range-access evidence` · `[ ] repeated measurements and conditions recorded`.

Large input makes unnecessary work easier to see. Returning one aggregate row reduces transfer and grid-rendering noise. Actual plans and measurements still determine the conclusion.

### Repeated performance measurements for cases 5, 6, and 9

1. Keep database, connection, data range, timeout, row limit, and data stable. Record database version, indexes, and plans. Actual plans execute SELECT.
2. Warm up each version once and record that separately. Then run `A → B → B → A` for three rounds. Retain all values; calculate each version's median and minimum/maximum rather than choosing its fastest execution.
3. Record database-native time separately from workbench elapsed time. Compare the same metric scope; plan instrumentation adds overhead and is not ordinary execution time.
4. Do not flush shared database caches. Record changed statistics, concurrent load, timeout, cancellation, or capture failure; failures are not normal timing samples.
5. If the difference is tiny or reverses with execution order, report an unstable benefit under the current conditions. The saved tutorial observations are individual plan samples, not a completed repeated benchmark.

| Version | Warm-up | Round 1, two values | Round 2, two values | Round 3, two values | Median | Min / max | Access path and input rows |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | Record | Record | Record | Record | Record | Record | Record |
| B | Record | Record | Record | Record | Record | Record | Record |

## Read diagnostic conclusions carefully

- **Suspected** (待确认): a rule found a clue worth investigating. It is not a proven root cause or a background task still running. HIGH/MEDIUM/LOW indicates priority; confidence is not the probability of the root cause.
- **Verify:** use it when the finding offers that action and the database supports it. Confirmation or refutation concerns the specific claim and observation. Many plan-structure observations have no verification button.
- **Incomplete, skipped, or failed:** read the evidence gap. No findings does not prove query correctness.
- **A/B:** side-by-side results support inspection; they do not automatically constitute a controlled performance experiment. Independent runs do not guarantee one shared snapshot.

For each case, record business grain, expected result, observed evidence, rewritten result, and remaining uncertainty. Establish correctness before judging performance.

## Validation scope

On November 14, 2026, 43 original case SELECTs were validated through JDBC on PostgreSQL/MySQL employees, SQL Server AdventureWorks, and Oracle FREEPDB1. The 28 executions of the first three cases matched their independently specified results row by row. Cases 5 and 6 supplied eight native actual plans, and both rewrite pairs returned identical complete results on both databases.

Case 9 added four validated SELECT executions and four actual plans, with equal A/B aggregates on both databases. Three additional count queries checked database sizes. Totals: **47 case SELECTs, 12 actual plans, and three size queries**.

This does not certify every parse, decomposition, trace, or plan-rendering path for all 21 SQL files in the desktop UI. Available candidates depend on query shape and capabilities. Use the explicit independent check queries when a decomposition is unavailable; inability to decompose is not a business conclusion. Dataset changes may alter counts, and optimizer changes may alter plan shapes.

See the project [README](../../README.md) for database support boundaries. This directory contains user tutorials and SQL, without internal validation artifacts or database credentials.
