# Architecture — Century Road Backend

> System context: this is the backend half of Century Road — 3 independent Spring Boot services behind a gateway. The frontend (a React/Vite SPA) lives in a sibling repository, [github.com/F3rren/century-road-frontend](https://github.com/F3rren/century-road-frontend), which has its own `architecture.md`, `DESIGN.md`, and `PRODUCT.md`. This file is the "why is it built this way" companion to [README.md](../README.md), which stays the onboarding/ops doc (setup, environment variables, TLS walkthrough, Railway deployment) — read that first if you're trying to run the stack, read this if you're trying to understand its shape.

## Tech stack

| | Version | Notes |
|---|---|---|
| Spring Boot | 3.5.16 | All 3 services, same parent. Last released 3.x patch — every Boot 3.x line is now past OSS end-of-life; the move to a supported line (Boot 4.1.x) is a separate, bigger, deliberately-deferred migration (Spring Cloud two trains over, springdoc's major now locked to Boot's, Jackson 3's default date serialization changes the API's wire format) — see Key technical decisions |
| Java | 21 | |
| Maven | wrapper (`./mvnw`) per service | **No root aggregator `pom.xml`** — this is not a Maven reactor multi-module build. Each of the 3 services is a fully independent Maven project; every command is run from inside `service/<name>/`. |
| PostgreSQL | via `postgis/postgis:16-3.5-alpine` | `auth-service` and `history-service` only — `gateway` is stateless |
| Flyway | flyway-database-postgresql | Per-service migrations, per-service database (see Data model) |
| Testcontainers | **1.21.4**, pinned | Overrides Spring Boot's managed version — see Key technical decisions |
| springdoc-openapi | 2.9.1, pinned | Built against Spring Boot 3.5.14; moves with Spring Boot, not independently |

## General architecture

```
browser ──HTTPS──▶ proxy (Caddy) ──HTTP──▶ gateway ─┬─HTTP─▶ auth-service ────▶ postgres
                   :443                    :8080    │        :8081               :5432
                   TLS, HSTS               routing, │        identity
                                           CORS     │
                                                    └─HTTP─▶ history-service ─▶ Wikipedia
                                                             :8082              (HTTPS, cached)
```

Only the proxy is public in production. The gateway, the services, and Postgres talk over the internal Compose network and are never reachable from outside. Prometheus/Grafana are loopback-only even in production (SSH tunnel to reach them).

- **`auth-service`** — owns identity: users, login, the JWTs every other service will eventually verify offline.
- **`history-service`** — answers "what happened on this date", proxying and caching Wikipedia's On This Day feed; also owns the anonymous, aggregate day/country view counters.
- **`gateway`** (Spring Cloud Gateway, reactive/WebFlux) — the single entry point; pure routing + CORS + OpenAPI-doc relay, no business logic and no database of its own.

## Data model

Each service that has state owns its own Postgres **database** within the one shared Postgres container/resource — not the same database, not just a separate schema within it. This is the pattern to extend for any future stateful service. (Revised from an earlier same-database-different-schema design — see Key technical decisions for why.)

**`auth-service`**, database `${POSTGRES_DB}` (e.g. `centuryroad`), schema `public` (`V1__baseline_schema.sql`):
- `users` — `id BIGSERIAL PK`, `email VARCHAR(255) UNIQUE NOT NULL`, `password VARCHAR(255) NOT NULL` (BCrypt hash), `role VARCHAR(20) NOT NULL DEFAULT 'USER' CHECK (role IN ('ADMIN','USER'))`, `enabled BOOLEAN NOT NULL DEFAULT TRUE`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`.
- `refresh_tokens` — `id BIGSERIAL PK`, `user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE`, `token_hash VARCHAR(64) UNIQUE NOT NULL` (SHA-256 hex digest — the raw token is never stored, same reasoning as a hashed password), `created_at`, `expires_at TIMESTAMPTZ NOT NULL`, `revoked_at TIMESTAMPTZ` (nullable). Indexed on `user_id`.

**`history-service`**, its own database `${POSTGRES_DB}_history` (created by `infra/postgres/init-history-db.sh` on first container init), schema `history` within it (`V1__create_view_counters.sql`, `spring.flyway.schemas=history`) — the schema is redundant now that the database itself is the isolation boundary, but V1 already created it that way and rewriting an applied migration just to flatten it isn't worth the risk:
- `day_views` — `month SMALLINT CHECK (1-12)`, `day SMALLINT CHECK (1-31)`, `view_count BIGINT DEFAULT 0`, `PRIMARY KEY (month, day)`.
- `country_views` — `country_code VARCHAR(2) PRIMARY KEY CHECK (country_code ~ '^[A-Z]{2}$')` (ISO 3166-1 alpha-2), `view_count BIGINT DEFAULT 0`.

Both counter tables are purely anonymous and aggregate — no visitor identifier of any kind, incremented via an atomic Postgres upsert (`INSERT ... ON CONFLICT DO UPDATE SET view_count = view_count + 1`), never a read-modify-write, so concurrent requests can't lose an increment.

`gateway` has no `db/migration` — it is entirely stateless.

On Railway, which doesn't run custom Postgres init scripts, the `${POSTGRES_DB}_history` database has to be created once by hand (`CREATE DATABASE ...` via Railway's own Postgres connect UI or `psql`) before `history-service` can start — see the README's Railway section.

## API design

**Response envelope** — `ApiEnvelope<T>`, duplicated byte-for-byte in each service (`centuryroad.auth.dto.ApiEnvelope`, `centuryroad.history.dto.ApiEnvelope`), not a shared library. This is a deliberate independence-over-DRY trade-off (see Key technical decisions), not tech debt to silently consolidate. Fields: `success` (bool), `error` (machine-readable code, only on failure), `message` (developer-facing), `userMessage` (Italian, safe to show a user), `data`, `timestamp` (ISO-8601 offset), `sessionId` (= the `X-Request-Id` response header). `@JsonInclude(NON_NULL)` — absent fields are omitted from the JSON, never sent as `null`.

**Auth / JWT flow**, end to end:
1. **Login** (`POST /api/auth/login`) — rate-limited per `ip|email` (5 attempts/60s, in-memory, per-instance, no Redis). Credentials checked via BCrypt; a wrong email, wrong password, and a disabled account all answer identically (`401 INVALID_CREDENTIALS`) — deliberately indistinguishable. On success: a JWT (HS256, subject = email, claims `id`/`role`) plus an opaque 32-byte refresh token, 30 days, single-use — only its SHA-256 hash is stored.
2. **Every subsequent request** — `JwtAuthFilter` reads `Authorization: Bearer <token>`, `JwtVerifier` validates signature + expiry (returns `null` on any failure, never throws), populates `SecurityContextHolder` on success with one `ROLE_*` authority.
3. **Refresh** (`POST /api/auth/refresh`) — looks the raw token up by hash, rejects if revoked/expired, always rotates (marks the old one revoked, issues a new pair) — a refresh token is single-use by construction.
4. **Logout** (`POST /api/auth/logout`) — revokes the refresh token. The already-issued access JWT is stateless and cannot be recalled; it simply expires per `jwt.expiration-ms`.
5. **Authorization** — stateless sessions, CSRF disabled (bearer-token auth, not cookies). `/api/auth/**` and health/prometheus actuator endpoints are `permitAll()`; everything else `authenticated()`. `AdminUserController` additionally requires `@PreAuthorize("hasRole('ADMIN')")`.

**Routes** (see `README.md` for the full parameter/response contract of each):

| Service | Method | Path | Auth |
|---|---|---|---|
| auth-service | POST | `/api/auth/{login,refresh,logout}` | public |
| auth-service | GET | `/api/me` | bearer, any role |
| auth-service | POST/GET/PUT/DELETE | `/api/admin/users[/{id}]` | bearer, `ROLE_ADMIN` |
| history-service | GET | `/api/history/on-this-day/{month}/{day}` | public |
| history-service | GET | `/api/history/stats/{days,countries}` | public |
| history-service | POST | `/api/history/track/country/{code}` | public |

## Folder structure

```
service/
├── auth-service/       controller/ dto/ exception/ model/ repository/ security/ service/ config/ util/
├── gateway/             flat package — routing/CORS config only, no controllers of its own
└── history-service/     controller/ dto/ exception/ model/ query/ repository/ service/ wikipedia/ config/
infra/
├── caddy/               Caddyfile (prod TLS/HSTS reverse proxy)
├── grafana/              provisioning: datasource, one dashboard, alerting/ (rules, contact
│                         point, notification policy - see Key technical decisions)
├── postgres/             init-postgis.sql, init-history-db.sh (creates history-service's
│                         database), backup.sh + backup.cron.example (daily dump, off-box via
│                         rclone - see README's Backups section for the restore runbook)
└── prometheus/           prometheus.yml.tpl (env-substituted at container start)
compose-dev.yml           local dev — plain HTTP, hot reload, all ports published on loopback
compose-prod.yml          production — only Caddy is internet-facing
```

## Key technical decisions

**Testcontainers pinned to 1.21.4** (`service/{auth-service,history-service}/pom.xml`, explicit `testcontainers-bom` import in `<dependencyManagement>` so Dependabot can see and bump the property — an override with no `<version>` tag reference would be invisible to it):

> Ahead of the 1.19.8 Spring Boot 3.3.4 used to manage. That release ships docker-java 3.3.6, which asks the daemon for Docker API 1.32; recent Docker Engine and Docker Desktop builds refuse anything that old and answer 400, which Testcontainers reports as the misleading "Could not find a valid Docker environment" — so the integration tests fail on an up-to-date local Docker while older CI runners still accept 1.32 and pass. Verified: 1.20.4 is still refused, 1.21.4 negotiates fine. Unrelated to the Spring Boot 3.5.16 bump below, so left untouched by it.

**Spring Boot bumped 3.3.4 → 3.5.16, deliberately not straight to 4.1.x.** A Trivy scan added to CI found 153 HIGH/CRITICAL vulnerabilities, entirely in libraries Spring Boot's own BOM manages (Tomcat, Netty, Spring Framework, Spring Security, Jackson, Logback, the Postgres driver) — 3.3.4 had gone stale, and in fact every Boot 3.x line is now past its own OSS end-of-life (3.5, the last one, ended 2026-06-30). 3.5.16 is the last Boot 3.x patch: a same-major bump that resets those managed versions to current without any breaking changes, and should clear the large majority of those 153 findings. The real fix, Boot 4.1.x (the currently-supported line), is a major-version jump — a two-train Spring Cloud move (`spring-cloud.version` 2023.0.3 → 2025.0.3 here, but 4.x needs the *next* train, 2025.1.x), springdoc's major now locked to Boot's (2.6.0 → 2.9.1 here; Boot 4 needs springdoc 3.x), and Jackson 3's default date serialization changing the API's wire format (epoch timestamps → ISO-8601 strings) in a way that could affect the frontend — deliberately deferred to its own planning pass rather than bundled in under the pressure of a red CI gate.

**`ApiEnvelope<T>` and the exception hierarchy are duplicated per service, not extracted to a shared library.** Each service also has its own `RequestCorrelationFilter` (identical implementation, minting `REQ_<8hex>` and setting `X-Request-Id`) rather than a shared one. This trades DRY for each service being independently deployable/buildable with zero shared-library version-skew risk — a real cost (identical bug fixes must be applied 2-3 times) accepted deliberately, not an oversight.

**Resilience4j is hand-wired, not annotation-based, in `history-service` only** (`WikimediaClientConfig`) — bulkhead → retry → circuit breaker composition is explicit in code so the wrapping order is visible in one place, rather than implied by annotation-processing order. Only `UpstreamUnavailableException`/`UpstreamBadResponseException` count as circuit-breaker failures; `UpstreamRateLimitedException` (a 429) is handled separately by `UpstreamCooldown`, since "asked to slow down" isn't the same fault class as "broken."

**`gateway` is reactive (WebFlux/Spring Cloud Gateway)**, the other two are servlet-based (`spring-boot-starter-web`) — the gateway's only job is routing/proxying, where a reactive, non-blocking model fits its I/O-bound nature; the two backing services do real work (DB queries, upstream HTTP calls with retries) where the blocking servlet model is simpler to reason about.

**Postgres backups are a host cron script (`infra/postgres/backup.sh`) plus `rclone`, not a
sidecar container or a managed backup service.** A sidecar would be a fourth long-lived
production container (another image to keep patched and scanned) whose lifecycle is tied to
`docker compose up`/`down` — routine maintenance would silently stop backups along with
everything else. A host script is decoupled from the app stack entirely and is the simplest
thing that works for one operator. `rclone` over `rsync`/`scp`: one config supports S3, B2,
SFTP and more, so the actual off-box provider is a runtime choice, not a code one. Railway's
own managed Postgres backups, where available, are preferred over a second pipeline on a
database Railway already owns — see the README's Backups section.

**Alerting is Grafana's own built-in unified alerting, not a standalone Prometheus
Alertmanager.** Both Prometheus and Grafana are already loopback-only in production; a fourth
container would add nothing an alerting engine already colocated with the datasource does not
provide, and Grafana's contact points reach Discord/Slack over an outbound webhook, which fits
that loopback-only constraint without opening anything new. See
`infra/grafana/provisioning/alerting/`.

**Published container images are scanned with Trivy in CI** (`publish-images` in `ci.yml`),
gating the moving tag (`latest`/branch) but never the immutable SHA tag, with results uploaded
as SARIF to the same Security tab CodeQL already uses. Chosen over Snyk Container (needs an
account/token) or Docker Scout (tied to Docker Hub's auth model) for needing neither against a
GHCR-only setup. `ignore-unfixed: true` is deliberate: a HIGH/CRITICAL CVE in a base image with
no available fix should not keep the job red indefinitely.

**`history-service` got its own database, not just its own schema, revising the original design.** When its persistence layer was first added, it shared `auth-service`'s database under a separate `history` schema — real logical separation, but not real SQL-level isolation: both services connected as the same Postgres role, so nothing at the database layer actually stopped one from querying the other's tables, and the two share fate if that one Postgres container has a problem. Reconsidered on the reasonable objection that "database per service" is the actual microservices convention for a reason. A fully separate Postgres instance per service was the other option on the table; rejected for now as more infrastructure (and, on Railway, more paid resources) than this project's current scale justifies. A separate *database* in the same container is the middle ground: Postgres refuses cross-database queries outright regardless of role permissions, at zero extra infrastructure cost. See `infra/postgres/init-history-db.sh`.

## Deployment and environments

Two fully isolated environments, deliberately sharing nothing at runtime (own project name, own volumes, own settings file) — `compose-dev.yml` refuses to start without `--env-file .env.dev` (guarded by a required `CENTURY_ROAD_ENV` marker that only exists in `.env.dev`), so it can never accidentally run against production secrets.

| | Development | Production |
|---|---|---|
| Compose file | `compose-dev.yml` | `compose-prod.yml` |
| Settings | `.env.dev` | `.env` |
| Start | `docker compose --env-file .env.dev -f compose-dev.yml up` | `docker compose -f compose-prod.yml up -d` |
| Reverse proxy | none, plain HTTP | Caddy, TLS + HSTS |
| Ports published | every service, on loopback | **only** Caddy (80/443); nothing else |

**Railway** is the actual production target: one Railway project holds Postgres + the 3 backend services (pulled as pre-built GHCR images, not built on Railway) + the frontend (from its own repo, built there). Since Railway terminates TLS itself, there is no Caddy in front there — the gateway instead runs with `SPRING_PROFILES_INCLUDE=railway`, which hides everything but `/actuator/health` and adds the same security headers (`Strict-Transport-Security`, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`) Caddy would otherwise add.

**CI/CD** (`.github/workflows/`): `ci.yml` runs `./mvnw clean verify` per service (matrix over all 3), then on a push publishes images to `ghcr.io/f3rren/century-road-backend-{auth-service,gateway,history-service}`, tagged with the short commit SHA (immutable, the one to pin) plus a moving branch tag (`latest`/`develop`) — each image is scanned with Trivy first, gating the moving tag (see Key technical decisions). `release.yml` (triggered by a `vMAJOR.MINOR.PATCH` tag on `main`) retags the already-tested images with the semver — it rebuilds nothing, so a release is byte-for-byte what CI already tested. Also: `codeql.yml` (weekly + push/PR), `dependency-review.yml` (PR-only), and `dependabot.yml` (weekly, PRs target `develop`; explicitly ignores springdoc major/minor bumps since it's tied to the Spring Boot line, a PostGIS major bump since it needs a manual dump/restore, and a Java major bump since it's "a decision, not a bump" spanning `java.version` + Dockerfiles + CI together).
