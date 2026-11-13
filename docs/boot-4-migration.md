# Moving to Spring Boot 4

Why this is the first thing to schedule: every Spring Boot 3.x line is past its open-source end of
life (3.5, the last, ended 2026-06-30), so a vulnerability found in Tomcat, Spring Security or Jackson from
now on is fixed in 4.x and not here. `architecture.md` records why the jump was deferred
(Spring Cloud, springdoc and Jackson 3 each need their own step); this is the plan for taking it, in the
order that makes each step checkable, and what was done ahead of it so the jump itself is small.

Written on Spring Boot 3.5.16. What Boot 4 brings is stated as expected: check it on the migration branch.

## Done ahead of it (all on 3.5, all behaviour-preserving)

| Preparation | Why it matters for Boot 4 |
|---|---|
| `docs/openapi/*.json` + `OpenApiSnapshotTest` | The published contract is a committed file. After the upgrade, regenerate it: **an empty diff means the API did not change**; anything else is what Jackson 3 or springdoc 3 changed, reviewed line by line. |
| Dates pinned at the wire (`indexedAt`, `reviewedAt`, `lastReviewedAt`, `receivedAt`) | The OpenAPI document says `date-time`, not how it is written. Controller tests assert the exact string, so a change of default date serialization (the one `architecture.md` warns about) fails a test, not a browser. |
| `AlertRulesIntegrationTest` | Micrometer and the Prometheus client move with Boot: the alert rules are checked against the metrics the service really exposes, so a renamed series fails the build. |
| `@MockBean` → `@MockitoBean` (7 test classes) | Boot's `@MockBean` is deprecated for removal. |
| `JsonNode.fields()` → `properties()` (the two `OpenApiDocsTest`s) | Deprecated in Jackson 2.19, gone in 3. |
| gateway: `spring-cloud-starter-gateway-server-webflux` and `spring.cloud.gateway.server.webflux.*` | The old artifact and prefix are deprecated in the 2025.0 train and removed after it. |
| `jpa.database-platform` removed | Hibernate warned it is redundant (HHH90000025). |
| `ArchitectureTest` | The layers and the one door to Wikipedia are rules, so a mechanical change across hundreds of files (imports for Jackson 3) cannot quietly break them. |

(`OpenApiSnapshotTest` and `ArchitectureTest` arrive with their own branches, `feature/openapi-snapshot` and
`feature/quality-gates`; the migration should start after both are merged.)

`./mvnw -Dmaven.compiler.showDeprecation=true clean test-compile` is clean in all three services: no
deprecated API is left in use. Run it again on the branch, after each bump.

## What the upgrade itself touches

1. **Parent and BOMs.** `spring-boot-starter-parent` 4.1.x; `spring-cloud.version` to the train built on it
   (2025.1.x); springdoc to its 3.x line (it moves with Boot, which is why Dependabot ignores its minors).
   `testcontainers-bom`: Boot 4 manages a newer Testcontainers; try dropping the 1.21.4 pin and the BOM import
   (the pin exists only because Boot 3.3's managed version fails against current Docker Engine).
2. **Jackson 3.** Code that uses Jackson's own API changes package (`tools.jackson...`); the annotations
   (`@JsonInclude`, `@JsonProperty`) stay where they are. Where it is used directly: `EditorialLoader`
   (`JsonMapper.builder()`, strict mapper), `WikimediaFeedParser` and `WikimediaClientConfig` (an injected
   `ObjectMapper`), and the tests that read JSON. `RequiredFieldsCustomizer` and springdoc depend on the
   swagger-core version springdoc 3 brings: it is the first thing to re-check.
3. **Spring Security** (auth-service). `SecurityConfig` already uses the lambda DSL; `JwtAuthFilter` is a
   plain `OncePerRequestFilter`. Expect little, and run `AuthControllerTest` and `AdminUserControllerTest`
   first.
4. **Spring Cloud Gateway.** Renamed already; the route `metadata` keys (`response-timeout`) and
   `GatewayProperties` are what to re-check, with `GatewayTimeoutsTest` and `GatewayResponseTimeoutTest`.
5. **Hibernate and Flyway.** `ddl-auto: validate` makes a mapping change loud at startup; the three
   `history-service` migrations and `auth-service`'s baseline must still apply to an empty database.

## Order, and what says "done" at each step

1. A branch from `develop`, the parent bumped, the code compiling. (Compiler errors are the list.)
2. `./mvnw clean verify` in each service. The **architecture rules and the coverage floor are part of it**.
3. **Regenerate `docs/openapi/*.json` and read the diff.** The target is empty. If it is not, each line is
   either a decision (accept it, and change the frontend with it: `npm run api:sync`) or a regression.
4. Run the stack (`docker compose -f compose-dev.yml up`) and the frontend against it: the generated types
   (`npm run api:check`, `npm run build`) fail at the field that moved.
5. Scrape `/actuator/prometheus` and compare the series the alerts and the dashboard use
   (`AlertRulesIntegrationTest` does it for the alerts).
6. Release behind the usual `v*` tag; the images are built and scanned by the same CI.

## What is not in this plan

Replacing the hand-rolled JWT with Spring's resource-server support, or moving the identity to a managed
provider, is a separate decision (symmetric key, nothing but `auth-service` signs: see the review in the
repository's history), and so is whether three services should stay three. Neither blocks Boot 4.
