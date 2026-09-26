# Architecture — Century Road Backend

> System context: this is the backend half of Century Road — 3 independent Spring Boot services behind a gateway. The frontend (a React/Vite SPA) lives in a sibling repository, [github.com/F3rren/century-road-frontend](https://github.com/F3rren/century-road-frontend), which has its own `architecture.md`, `DESIGN.md`, and `PRODUCT.md`. This file is the "why is it built this way" companion to [README.md](../README.md), which stays the onboarding/ops doc (setup, environment variables, TLS walkthrough, Railway deployment) — read that first if you're trying to run the stack, read this if you're trying to understand its shape.

## Tech stack

| | Version | Notes |
|---|---|---|
| Spring Boot | 3.3.4 | All 3 services, same parent |
| Java | 21 | |
| Maven | wrapper (`./mvnw`) per service | **No root aggregator `pom.xml`** — this is not a Maven reactor multi-module build. Each of the 3 services is a fully independent Maven project; every command is run from inside `service/<name>/`. |
| PostgreSQL | via `postgis/postgis:16-3.4-alpine` | `auth-service` and `history-service` only — `gateway` is stateless |
| Flyway | flyway-database-postgresql | Per-service migrations, per-service schema (see Data model) |
| Testcontainers | **1.21.4**, pinned | Overrides Spring Boot 3.3.4's managed 1.19.8 — see Key technical decisions |
| springdoc-openapi | 2.6.0, pinned | Last release built on Spring Boot 3.3; moves with Spring Boot, not independently |

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

Each service that has state owns its own Postgres **schema** in the same database instance — not a separate physical database, not a shared schema. This is the pattern to extend for any future stateful service.

**`auth-service`**, schema `public` (`V1__baseline_schema.sql`):
- `users` — `id BIGSERIAL PK`, `email VARCHAR(255) UNIQUE NOT NULL`, `password VARCHAR(255) NOT NULL` (BCrypt hash), `role VARCHAR(20) NOT NULL DEFAULT 'USER' CHECK (role IN ('ADMIN','USER'))`, `enabled BOOLEAN NOT NULL DEFAULT TRUE`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`.
- `refresh_tokens` — `id BIGSERIAL PK`, `user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE`, `token_hash VARCHAR(64) UNIQUE NOT NULL` (SHA-256 hex digest — the raw token is never stored, same reasoning as a hashed password), `created_at`, `expires_at TIMESTAMPTZ NOT NULL`, `revoked_at TIMESTAMPTZ` (nullable). Indexed on `user_id`.

**`history-service`**, its own schema `history` (`V1__create_view_counters.sql`), isolated from `auth-service`'s `public` schema **and** its Flyway history table via `spring.flyway.schemas=history` — so the two services' migration histories can never collide in the same database:
- `day_views` — `month SMALLINT CHECK (1-12)`, `day SMALLINT CHECK (1-31)`, `view_count BIGINT DEFAULT 0`, `PRIMARY KEY (month, day)`.
- `country_views` — `country_code VARCHAR(2) PRIMARY KEY CHECK (country_code ~ '^[A-Z]{2}$')` (ISO 3166-1 alpha-2), `view_count BIGINT DEFAULT 0`.

Both counter tables are purely anonymous and aggregate — no visitor identifier of any kind, incremented via an atomic Postgres upsert (`INSERT ... ON CONFLICT DO UPDATE SET view_count = view_count + 1`), never a read-modify-write, so concurrent requests can't lose an increment.

`gateway` has no `db/migration` — it is entirely stateless.

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
├── grafana/              provisioning (datasource + dashboards)
├── postgres/             init-postgis.sql
└── prometheus/           prometheus.yml.tpl (env-substituted at container start)
compose-dev.yml           local dev — plain HTTP, hot reload, all ports published on loopback
compose-prod.yml          production — only Caddy is internet-facing
```

## Key technical decisions

**Testcontainers pinned to 1.21.4** (`service/{auth-service,history-service}/pom.xml`, explicit `testcontainers-bom` import in `<dependencyManagement>` so Dependabot can see and bump the property — an override with no `<version>` tag reference would be invisible to it):

> Ahead of the 1.19.8 Spring Boot 3.3.4 manages. That release ships docker-java 3.3.6, which asks the daemon for Docker API 1.32; recent Docker Engine and Docker Desktop builds refuse anything that old and answer 400, which Testcontainers reports as the misleading "Could not find a valid Docker environment" — so the integration tests fail on an up-to-date local Docker while older CI runners still accept 1.32 and pass. Verified: 1.20.4 is still refused, 1.21.4 negotiates fine.

**`ApiEnvelope<T>` and the exception hierarchy are duplicated per service, not extracted to a shared library.** Each service also has its own `RequestCorrelationFilter` (identical implementation, minting `REQ_<8hex>` and setting `X-Request-Id`) rather than a shared one. This trades DRY for each service being independently deployable/buildable with zero shared-library version-skew risk — a real cost (identical bug fixes must be applied 2-3 times) accepted deliberately, not an oversight.

**Resilience4j is hand-wired, not annotation-based, in `history-service` only** (`WikimediaClientConfig`) — bulkhead → retry → circuit breaker composition is explicit in code so the wrapping order is visible in one place, rather than implied by annotation-processing order. Only `UpstreamUnavailableException`/`UpstreamBadResponseException` count as circuit-breaker failures; `UpstreamRateLimitedException` (a 429) is handled separately by `UpstreamCooldown`, since "asked to slow down" isn't the same fault class as "broken."

**`gateway` is reactive (WebFlux/Spring Cloud Gateway)**, the other two are servlet-based (`spring-boot-starter-web`) — the gateway's only job is routing/proxying, where a reactive, non-blocking model fits its I/O-bound nature; the two backing services do real work (DB queries, upstream HTTP calls with retries) where the blocking servlet model is simpler to reason about.

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

**CI/CD** (`.github/workflows/`): `ci.yml` runs `./mvnw clean verify` per service (matrix over all 3), then on a push publishes images to `ghcr.io/f3rren/century-road-backend-{auth-service,gateway,history-service}`, tagged with the short commit SHA (immutable, the one to pin) plus a moving branch tag (`latest`/`develop`). `release.yml` (triggered by a `vMAJOR.MINOR.PATCH` tag on `main`) retags the already-tested images with the semver — it rebuilds nothing, so a release is byte-for-byte what CI already tested. Also: `codeql.yml` (weekly + push/PR), `dependency-review.yml` (PR-only), and `dependabot.yml` (weekly, PRs target `develop`; explicitly ignores springdoc major/minor bumps since it's tied to the Spring Boot line, a PostGIS major bump since it needs a manual dump/restore, and a Java major bump since it's "a decision, not a bump" spanning `java.version` + Dockerfiles + CI together).
