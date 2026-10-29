# DBeaver → JustinBeaver feature map

Source: REA extraction of DBeaver CE 25.0.2 (`/usr/share/dbeaver-ce/plugins`), `plugin.xml` command
declarations of `ui.editors.sql` (61 commands), `ui.editors.data` (62), `ui.navigator` (45) and the
`data.transfer` processors (10 formats). Features are re-implemented from scratch; no DBeaver code is
copied (DBeaver CE is Apache 2.0).

Status: ✅ done · 🟡 partial · ⏳ planned · ➖ not applicable (Eclipse/RCP-specific)

## Connections & drivers (registry)

| DBeaver | Status | JustinBeaver |
|---|---|---|
| Driver descriptors in `plugin.xml`, Maven-resolved JARs, per-driver class loader | ✅ | `drivers/*.yml`, `MavenResolver`, `DriverClassLoader` |
| Custom/user drivers | ✅ | `~/.justinbeaver/drivers/*.yml` |
| Saved connections, secure password storage | ✅ | `connections.json` + AES-GCM (`secret.key`) |
| Test connection | ✅ | connection dialog |
| Auto-reconnect after connection loss | ✅ | `SessionManager.require` |
| SSH tunnel (`networkHandler` ssh) | ✅ | sshj local forward, password/key auth, known_hosts with trust-on-first-use |
| SSL (`networkHandler` ssl) | ✅ | PostgreSQL, MySQL, MariaDB, SQL Server property mapping |
| SOCKS / HTTP proxy, SSH jump hosts | ⏳ | — |
| Auth models (pgpass, Kerberos…) | ⏳ | user/password only |
| Connection types (dev/prod colors, confirm execute) | ⏳ | — |

## SQL editor (`ui.editors.sql`)

| Command | Status | Notes |
|---|---|---|
| New SQL script / Open / Rename / Delete this script | 🟡 | new + close tabs, persisted in browser; no rename/files |
| Execute SQL query (Ctrl+Enter) | ✅ | statement under cursor or selection |
| Execute SQL script (Alt+X) | ✅ | per-statement result tabs, stop on error |
| Execute SQL script from the position | ✅ | "From cursor" |
| Execute queries in separate tabs / in new tab | 🟡 | script results get one tab per statement |
| Cancel active query | ✅ | `Statement.cancel()` |
| Explain Execution Plan | ✅ | PostgreSQL, MySQL/MariaDB, SQLite, H2, DuckDB, ClickHouse, HSQLDB |
| Load Execution Plan | ➖ | — |
| Select row count / Select all rows | 🟡 | table viewer Count / Data tab |
| Content Format | ✅ | `sql-formatter`, dialect-aware |
| Toggle Line / Block Comment | ✅ | CodeMirror (Ctrl+/) |
| Go to matching bracket / Select to bracket | ✅ | CodeMirror |
| Toggle Word Wrap | ✅ | "Wrap" |
| Complete / templates (autocomplete) | 🟡 | keywords + tables + columns from metadata; no templates |
| Show execution log | ✅ | per-connection History |
| Export from Query | ✅ | Export menu (8 formats) |
| Auto-commit / Commit / Rollback | ✅ | shared per connection |
| Refresh current schema / all schemas | ✅ | navigator refresh; auto after DDL |
| Next/Previous query, Foldings, Outline, Trim spaces | 🟡 | folding via CodeMirror; others ⏳ |
| SQL variables / parameters (`:name`, `${var}`) | ⏳ | — |
| Show server output (DBMS_OUTPUT, RAISE NOTICE) | ⏳ | — |
| Execute stored procedure | ⏳ | — |
| Search in web, toggle layout, pin tab, presentations | ➖ | — |

## Data editor (`ui.editors.data`)

| Command | Status | Notes |
|---|---|---|
| Edit cell / Inline edit | ✅ | double-click, Enter, F2 |
| Set to NULL | ✅ | NULL button in the cell editor |
| Set to default | 🟡 | new rows: unset columns use DEFAULT |
| Add row / Delete current row / Duplicate row | ✅ | |
| Apply changes / Apply and commit / Reject changes | ✅ | Save (with SQL preview) / Discard |
| Show confirmation before save | ✅ | always previews the SQL |
| Reset cell changes | 🟡 | setting the original value back drops the change |
| Row Count | ✅ | Count (respects the filter) |
| Fetch Next Page / Fetch All | 🟡 | paging; no "fetch all" |
| Filter (WHERE) / Remove all filters | ✅ | |
| Toggle results sort order | ✅ | click headers |
| Toggle Grid/Record view | ✅ | Record (read-only) |
| First/Last/Next/Previous row, cell navigation | ✅ | arrow keys |
| Copy / Copy as | ✅ | Ctrl+C cell; Export → Copy in any format |
| Export data | ✅ | Export menu |
| Filter by value (distinct), saved filters | ⏳ | |
| Hide/move/fit columns, row colors, zoom | ⏳ | |
| Navigate link (FK) / References | 🟡 | ER diagram opens related tables |
| Generate script (SELECT/INSERT/UPDATE/DELETE for rows) | 🟡 | "SQL INSERT statements" export |
| Generate UUID, copy from row above/below | ⏳ | |
| Value viewers (JSON/XML/image/hex) | ✅ | View value: text, JSON, XML, hex, image; full content by primary key; save to file |

## Navigator (`ui.navigator`)

| Command | Status | Notes |
|---|---|---|
| Connection → schema → tables/views → columns tree | ✅ | lazy |
| Edit Object (open table editor) | ✅ | double-click |
| Refresh | ✅ | |
| Open Source tab (DDL) | ✅ | DDL tab: native for SQLite/MySQL/PG views, generated otherwise |
| Create / Delete object, New Constraint / Index from selection | ✅ | create table, add/edit/drop column, index, foreign key, rename, truncate, drop — DDL per dialect, always previewed |
| Open Database Object (quick search) | ✅ | Ctrl+Shift+D across all open connections |
| Navigator filters | ⏳ | |
| Projects, resources, link files/folders, preferences | ➖ | |

## Table editor tabs

| DBeaver | Status |
|---|---|
| Properties: columns, constraints, foreign keys, indexes | ✅ |
| References (incoming foreign keys) | ✅ ER diagram |
| DDL / Source | ✅ |
| ER diagram (`erd.ui`) | 🟡 focused diagram of one table and its neighbours |
| Triggers, partitions, statistics, permissions | ⏳ |

## Data transfer (`data.transfer`)

| Processor | Export | Import |
|---|---|---|
| CSV | ✅ | ✅ header → column matching, batches of 500 |
| JSON | ✅ | ⏳ |
| Markdown | ✅ | ➖ |
| SQL (INSERT) | ✅ | 🟡 run the file in the SQL editor |
| HTML | ✅ | ➖ |
| XML | ✅ | ⏳ |
| TXT | ✅ | ➖ |
| TSV | ✅ | 🟡 (CSV importer auto-detects tab) |
| DbUnit, Source code | ⏳ | ➖ |
| Table → table (database copy) | ⏳ | — |

## Tools & administration (other bundles)

| DBeaver | Status |
|---|---|
| Session manager, locks (`ui.editors.session`, `ui.editors.locks`) | ⏳ |
| Native backup/restore (`tasks.native`: pg_dump, mysqldump) | ⏳ |
| Tasks & scheduler | ⏳ |
| Dashboards (`ui.dashboard`) | ⏳ |
| Schema compare (`cmp.simple`) | ⏳ |
| GIS viewer | ⏳ |
