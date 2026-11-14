# Moving to Spring Boot 4

Every Spring Boot 3.x line is past its open-source end of life (3.5, the last, ended 2026-06-30), so a
vulnerability found in Tomcat, Spring Security or Jackson from then on is fixed in 4.x and not here.
This is the record of taking the jump: what was prepared on 3.5, what the jump itself touched, what
the plan got wrong, and what is still open.

**State:** all three services run on Spring Boot **4.1.2** (Spring Framework 7.0, Spring Security 7.1,
Jackson 3.1). `./mvnw clean verify` is green for each, the architecture rules and the coverage floors
included, and `docs/openapi/*.json` regenerated **byte for byte unchanged** on 4.1.2: the published
contract did not move, so neither did the frontend's generated types. What is not yet confirmed is in
[Still open](#still-open).

## Done ahead of it (all on 3.5, all behaviour-preserving)

| Preparation | Why it mattered for Boot 4 |
|---|---|
| `docs/openapi/*.json` + `OpenApiSnapshotTest` | The published contract is a committed file. After the upgrade it was regenerated: **an empty diff means the API did not change**, and that is what happened. |
| Dates pinned at the wire (`indexedAt`, `reviewedAt`, `lastReviewedAt`, `receivedAt`) | The OpenAPI document says `date-time`, not how it is written. Controller tests assert the exact string, so a change of default date serialization would have failed a test, not a browser. None did. |
| `AlertRulesIntegrationTest` | Micrometer and the Prometheus client move with Boot: the alert rules are checked against the metrics the service really exposes. It passed unchanged. |
| `@MockBean` → `@MockitoBean`, `JsonNode.fields()` → `properties()` | Deprecated on 3.5, gone in Boot 4 / Jackson 3. |
| gateway: `spring-cloud-starter-gateway-server-webflux` and `spring.cloud.gateway.server.webflux.*` | The old artifact and prefix are removed after the 2025.0 train. |
| `jpa.database-platform` removed | Hibernate warned it was redundant. |
| `ArchitectureTest` | The layers and the one door to Wikipedia are rules, so a mechanical change across hundreds of files (imports for Jackson 3) could not quietly break them. |

## What the jump touched

1. **Parent and BOMs.** `spring-boot-starter-parent` 4.1.2; springdoc 3.2.0 (the line built on 4.1.2).
   Every Boot 3-era override was **removed**, because the BOM now manages current versions: the
   Testcontainers pin and its BOM import, commons-lang3, Tomcat, the Postgres driver, log4j, the
   Jackson BOM, Netty. What stays pinned on purpose: springdoc, jjwt, and `bcprov-jdk18on` in the
   gateway (transitive via spring-cloud-commons, CVE-2026-8763).
2. **Modular starters.** Boot 4 split its auto-configuration into modules, and several things that were
   simply *there* in 3.x now need their starter: `spring-boot-starter-webmvc` (was `-web`),
   **`spring-boot-starter-flyway`** (without it the migrations silently do not run: the Flyway
   library is on the classpath, but nothing in Boot starts it), and for tests `spring-boot-starter-webmvc-test`, `spring-boot-starter-data-jpa-test`,
   `spring-boot-resttestclient` (`TestRestTemplate`, with `@AutoConfigureTestRestTemplate` on the test
   class) and `spring-boot-restclient`.
3. **Jackson 3.** Jackson's own API moved to `tools.jackson.*` (annotations stay in
   `com.fasterxml.jackson.annotation`), and the differences are semantic as well as in package names:
   exceptions are unchecked (`JacksonException`, so a `catch (IOException)` no longer sees a parse
   error: `EditorialLoader` had to catch it explicitly, and four editorial tests found it), mappers are
   immutable (`JsonMapper.builder().enable(...).build()`; `FAIL_ON_NUMBERS_FOR_ENUMS` is now
   `EnumFeature`), and `asText()`/`isTextual()` became `asString()`/`isString()`. **jjwt 0.13 and
   springdoc still bring Jackson 2** onto the classpath for themselves (`com.fasterxml.jackson.core`
   2.21.x next to `tools.jackson` 3.1.x): both are managed by Boot's BOMs, and they do not share a
   mapper with the application, but a Jackson CVE now has to be checked against both lines.
4. **Spring Framework 7.** `org.springframework.lang.NonNull/Nullable` are deprecated for JSpecify's
   (`org.jspecify.annotations`), and `HttpHeaders` is no longer a `MultiValueMap`, so an AssertJ
   `assertThat(headers).doesNotContainKey(...)` stops compiling (`containsHeader` instead).
5. **Spring Security 7** (auth-service) needed nothing beyond the above: `SecurityConfig` already used
   the lambda DSL and `JwtAuthFilter` is a plain `OncePerRequestFilter`.
6. **Testcontainers 2.** `PostgreSQLContainer` moved to `org.testcontainers.postgresql` and lost its
   type parameter; the module is `testcontainers-postgresql`.
7. **Hibernate and Flyway.** `ddl-auto: validate` stayed quiet and every migration still applies to an
   empty database.

## The gateway is on a pairing the Spring team does not list

Spring Cloud has no train built on Boot 4.1: **2025.1.x targets Boot 4.0.x**, and 2026.0 (for Boot 4.2)
is at RC2. The gateway therefore runs **Spring Cloud 2025.1.4 (Gateway 5.0.4) on Boot 4.1.2**. It compiles,
its 47 tests pass (routing, timeouts, forwarded headers, the Railway profile, the docs relay) and its
coverage gate holds. It can work because Boot 4.1.2 still ships Spring Framework 7.0.x, the line that
train is built on; the gateway's own code needed no change (only its pom and one test assertion). That is
**evidence, not a guarantee**: nothing upstream tests this combination.

The options, in the order they were weighed:

1. **Boot 4.1.2 + Cloud 2025.1.4 (taken).** One Boot version everywhere, so one place to bump. Risk:
   a Gateway defect that only shows on 4.1.
2. **Gateway alone on Boot 4.0.x.** Supported pairing, but two Boot lines to patch, and Boot 4.0 ends its support before 4.1 does.
3. **Wait for Cloud 2026.0 GA and Boot 4.2**, leaving the gateway on the unsupported Boot 3
   until then. Rejected: the reason for the move is that end of life.
4. **Replace the gateway** with routes in the Caddy proxy that already fronts it. It does path routing,
   CORS, per-route timeouts and forwarded headers, but the docs relay and the tests that pin those
   behaviours would have to be rebuilt. A decision about architecture, not about a version.

Revisit this the day Cloud 2026.0 is released: move `spring-cloud.version` and Boot together
(Dependabot is told not to propose that train on its own).

## Still open

- **CI on the branch.** Everything above was verified here with a hand-started Postgres, because this
  environment has no Docker. A few things only CI can confirm: that Testcontainers 2.x (now managed by
  Boot, no pin) negotiates with CI's Docker Engine, and the Trivy scan (the Boot 3 findings should be
  gone; anything left is new information).
- **A run of the whole stack** (`docker compose -f compose-dev.yml up`) with the frontend against it:
  the generated types (`npm run api:check`, `npm run build`) are the check, and the contract file did
  not change, so none is expected.
- **Compare `/actuator/prometheus`** with what the dashboard uses. `AlertRulesIntegrationTest` covers
  the alert series, not the dashboard panels.
- **Release** behind the usual `v*` tag; the images are built and scanned by the same CI.

## What is not in this plan

Replacing the hand-rolled JWT with Spring's resource-server support, or moving the identity to a managed
provider, is a separate decision (symmetric key, nothing but `auth-service` signs), and so is whether
three services should stay three. Neither blocks Boot 4, and neither got easier or harder with it.
