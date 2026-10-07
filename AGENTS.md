# AGENTS.md — Century Road Backend

Century Road backend: 3 independent Spring Boot services (`auth-service`, `gateway`, `history-service`) behind a gateway, no shared aggregator build. The frontend (React SPA) lives in the sibling repo [century-road-frontend](https://github.com/F3rren/century-road-frontend).

**Stack**: Spring Boot 3.5.16, Java 21, Maven wrapper per service, PostgreSQL + Flyway (one database per stateful service, same Postgres container — see `docs/architecture.md`), Testcontainers **pinned to 1.21.4** (don't bump back to Boot's managed 1.19.8 — see the pom.xml comment / `docs/architecture.md`'s ADR, it breaks against modern Docker Engine).

**Commands** (run from inside the relevant `service/<name>/`):
```
./mvnw test              # unit + integration tests for that service — auth-service and
                          # history-service need Docker running (Testcontainers)
./mvnw clean verify       # what CI runs
docker compose --env-file .env.dev -f compose-dev.yml up   # full stack, from repo root
```
Test counts as of this writing: auth-service 71, gateway 43 (no Docker needed), history-service 397.

**Conventions**: `ApiEnvelope<T>` response wrapper is intentionally duplicated per service, not a shared library — don't "fix" that without asking. Each service has its own `GlobalExceptionHandler`/`ApplicationException` hierarchy, same reasoning. `RequestCorrelationFilter` mints `REQ_<8hex>` — keep the format identical across services if you touch it, logs are grepped across services by this id. Flyway migrations are additive-only, never edit an applied one. history-service's nightly country index (`TimelineIndexer`) stays switched off in `application-test.yml` (`history.timeline.cron: "-"`): on, every `@SpringBootTest` starts a pass against the fake Wikipedia and trips the shared circuit breaker. Its `CountryLocator` uses `java.awt.geom`, so the runtime image must stay a full JRE (`java.desktop`), not a jlink'd one. Its hand-written content (paths, "Perché conta" insights) is JSON in `src/main/resources/editorial/`, checked at startup and by `EditorialContentTest`; never fill `provenance.reviewedAt` unless a person really reviewed that text against its sources — a missing date is the truth, an invented one is a false reliability label. Every path has a required `topic` (the `Topic` enum: its order is the display order, so adding a constant is deciding where it goes); the other content conventions (BCE slugs, counts written in words, path-neutral text, no time-relative phrases...) are checked by `EditorialContentTest` and listed in the README under "Writing content". Never rename or delete a published slug.

**Never touch**: already-applied Flyway migration files (write a new versioned one instead), `.env`/`.env.dev` secrets, `jwt.secret`, `compose-prod.yml` outside its guarded start flow.

**Ask before**: any DB schema change, a new dependency (especially anything touching `testcontainers.version`), a new microservice, CORS origin changes, login rate-limit threshold changes.

**References**: [architecture.md](docs/architecture.md) · [README.md](README.md) · frontend's [architecture.md](https://github.com/F3rren/century-road-frontend/blob/main/docs/architecture.md) / [DESIGN.md](https://github.com/F3rren/century-road-frontend/blob/main/DESIGN.md) / [PRODUCT.md](https://github.com/F3rren/century-road-frontend/blob/main/PRODUCT.md)
