# Spring 7 upgrade plan (draft, 2026-10-20)

Purpose: clear the two remaining critical advisories on `spring-webmvc` 6.2.19 (CVE-2026-47884, CVE-2026-47890). Trivy reports the only fixed version as 7.0.9. This is a plan for review, not an approved change. It is a major upgrade, so per AGENTS.md it needs sign-off before implementation.

## Facts checked on Maven Central (2026-10-20)

- `spring-webmvc` 6.2.x tops out at 6.2.19; 7.0.9 is available.
- Spring Boot 3.5 tops out at 3.5.16 (current). Spring Boot 4.x versions exist (4.0.7, 4.0.8, 4.1.0, 4.1.1).
- `springdoc-openapi-starter-webmvc-ui` 3.x exists (3.0.2 to 3.1.1); we use 2.8.9.

Not verified: which Spring Framework version each Boot 4.x line manages (check the Boot BOM before choosing), and which Boot 4.x / springdoc 3.x pairing is compatible. Confirm both from release notes before starting.

## Codebase exposure (grep counts over `src`, 527 Java files, 440 main)

| Area | Finding | Risk |
|---|---|---|
| Jackson | 44 files import `com.fasterxml.jackson` (mostly `ObjectMapper`, `JsonNode`; one `SimpleModule`, one custom `JsonSerializer`/`JsonGenerator`/`SerializerProvider`). | **Highest.** Boot 4 moves toward Jackson 3 (different packages and API); the Jackson 2 compatibility story needs checking. `jjwt-jackson` 0.12.7 also depends on Jackson 2. We also pin `jackson-bom.version` in `build.gradle`. |
| springdoc | 2.8.9, config in `OpenApiConfig`. | Needs the 3.x line; annotation/config changes likely. Prod has Swagger disabled, so runtime exposure is small. |
| Hibernate | Only 3 files import `org.hibernate`. | Low to medium (newer Hibernate under Boot 4). |
| Spring Security | `SecurityConfig` plus custom STOMP interceptor and login tracker. No `WebSecurityConfigurerAdapter` / `antMatchers` use found. | Medium: Security major-version removals; re-run the 55 tenant-isolation attack tests. |
| Tests | No `@MockBean`/`@SpyBean`, no `TestRestTemplate`, no `RestTemplate`. | Low |
| Deprecated Spring APIs | No hits for `PathPatternParser`/`AntPathMatcher`/trailing-slash, `ListenableFuture`, `WebMvcConfigurerAdapter`. | Low |
| Other deps | Flyway and Micrometer/Prometheus (via Boot BOM), MapStruct 1.6.3, Lombok, logstash-logback-encoder 8.0, zxing, MySQL connector. | Check each against the new BOM; `logstash-logback-encoder` and Jackson compatibility matter most. |

Grep counts are a screening heuristic, not proof; the compiler and tests are the real check.

## Proposed steps (each reviewable on its own)

1. **Decision and prerequisites.** Approve the major upgrade; read the Boot 4 and Framework 7 migration guides; choose the Boot 4.x line; confirm the springdoc and `jjwt` Jackson story.
2. **Backup.** The project has no Git, so copy the project folder (excluding `build` and `node_modules`) first.
3. **Spike on a copy.** Bump Boot, springdoc and BOM-managed pins; remove the `tomcat.version` and `jackson-bom.version` overrides if the new BOM covers them. Compile and list the breakages.
4. **Fix compile errors.** Jackson imports and the custom serializer first, then Security, then Hibernate.
5. **Test.** Full backend suite (baseline: 879 tests, 0 failures, 4 skipped). Pay special attention to `TenantIsolationIT`, `AuthHardeningIT`, `LiveSocketDeliveryIT` and JSON-shape tests. Run the `mysqlTest` suite (not run so far), since Hibernate/Flyway changes are MySQL-sensitive.
6. **Compare API output.** Diff JSON responses for key endpoints before and after (dates, nulls, enums, numbers are classic Jackson-migration differences). The frontend's 342 tests can help.
7. **Build images and rescan with Trivy**; confirm the two criticals clear; deploy only with approval.

Rollback: keep the pre-upgrade image and the folder backup; Flyway migrations should not change as part of this upgrade.

## Estimate and interim mitigation

Likely a multi-day task, dominated by Jackson migration and verification rather than Spring itself. Interim: read the two advisories to see whether they are reachable in this setup (preconditions, affected features) and whether a reverse-proxy rule is enough meanwhile. This has not been assessed.
