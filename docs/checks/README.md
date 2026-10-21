# Static and executable checks

Run everything: `python3 docs/checks/run_all.py`. No Gradle, Docker, MySQL or npm needed (Python 3, Java 21 and Node 22 only).

**These are not a build.** None of them compiles the Java, starts Spring, talks to MySQL or type-checks TypeScript. They remove whole classes of mistakes so the first real build is far less likely to fail on something trivial. Run `./gradlew test`, `./gradlew mysqlTest` and `cd frontend && npm run typecheck && npm test` as soon as a machine allows.

## How each check was validated

A check that has only ever said "pass" proves nothing. Every check here was (1) calibrated on the original, untouched code (it had to report nothing there, which exposed and fixed several bugs in the checkers themselves), and (2) mutation-tested: errors were planted in the real source on purpose and the check had to catch them, with the file restored each time.

| Check | What it proves | Planted errors caught |
|---|---|---|
| `run_junit.py` | The project's REAL pure JUnit classes (original and new), compiled with the JDK against the real sources and executed through a small JUnit stand-in; classes that need Spring are skipped and named | 5 of 5 |
| `run_vitest.py` | The project's REAL pure frontend test files, executed through a small vitest stand-in; files needing `vi` mocks or the DOM are skipped and named | 4 of 4 |
| `run_pure.py` | Rule classes executed for real: option pricing, order charges, staff permissions, popularity, request lifecycle, and the TypeScript helpers | n/a (executes the code) |
| `migrate_sqlite.py` | All 36 Flyway files are well formed and in a valid order (translated to SQLite; 20 MySQL-only statements are listed, not hidden) | n/a |
| `entity_schema_check.py` | Every JPA entity maps onto the migrated schema: the failure `ddl-auto=validate` causes at startup (table, column, type, unmapped NOT NULL) | 6 of 6 |
| `schema_behaviour_check.py` | New-table behaviour on real rows: duplicate guard, cascades, set-null, CHECK limits, defaults (33 assertions, V34 to V37) | n/a |
| `query_check.py` | Every `@Query` path and derived method name resolves (a failure here stops the app starting) | 5 of 5 |
| `api_contract_check.py` | Every URL the frontend and tests call has a controller | 4 of 4 |
| `dto_contract_check.py` | TypeScript interfaces match the Java responses: names and basic types | 5 of 5 |
| `json_key_check.py` | No typo in the JSON an integration test writes or reads | 4 of 4 |
| `call_check.py` | Cross-class calls by declared type, static calls, builder chains, lambda accessors | 11 of 11 |
| `unused_imports_check.py` | No frontend import is unused (tsconfig's `noUnusedLocals` would fail the whole build on one) | 3 of 3 |
| `mutation_lab.py` baseline guard | Before any sabotage the check must PASS on the untouched copy, so a broken test file can no longer make every sabotage look "caught" | n/a |
| `print_scope_check.py` | At most ONE unscoped `body *` print rule: a second one can silently blank another page's Print button (it did, once, for the QR dialog) | 2 of 2 |
| `locale_check.py` | The app is English only: no date, time or number is formatted by the device's language | 4 of 4 |
| `link_parity_check.py` | Rules written twice (Java is the authority, TypeScript is a courtesy to the screen) must agree: photo links on 45 addresses, tax registration numbers on 61, including non-breaking spaces and every punctuation mark | 14 of 14 |
| `mutation_lab.py` | Not a check: runs a deliberate sabotage on a TEMPORARY COPY of the project, never the real files | n/a |
| `static_shadow_check.py` | No method hides a static import of the same name (`status()` vs a helper called `status`): a compile error no other check sees | 3 of 3 |
| `jsx_props_check.py` | Components receive the props they declare | 6 of 6 |

The project's own checkers (`docs/*.py`: imports, accessors, arity, repository calls, paging, authorization, config guard) are run too. **Mutation testing showed they missed 6 of 9 errors planted in the new code** (they key on naming conventions and miss static calls, lambdas and builders); `call_check.py` was written to close that gap.

## Known limits

- SQLite is not MySQL. Foreign-key and CHECK `ADD CONSTRAINT`, and `MODIFY COLUMN`, cannot be applied there and are skipped (listed on every run).
- `entity_schema_check` does not judge enum-as-ordinal columns, embeddables, or column length.
- `call_check` resolves calls through class-level fields, static calls and builders; locals and parameters are not typed, so a wrong call on a local variable is not seen.
- `dto_contract_check` compares names and kinds, not nested element types.
- Nothing here checks behaviour that depends on Spring wiring (bean cycles, transactions, security filter order), Hibernate query execution, or the browser. That is what the integration tests and a manual run are for.
