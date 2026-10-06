# Century Road — Backend

[![CI](https://github.com/F3rren/century-road-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/F3rren/century-road-backend/actions/workflows/ci.yml)
[![CodeQL](https://github.com/F3rren/century-road-backend/actions/workflows/codeql.yml/badge.svg)](https://github.com/F3rren/century-road-backend/actions/workflows/codeql.yml)
[![Latest release](https://img.shields.io/github/v/release/F3rren/century-road-backend?label=release)](https://github.com/F3rren/century-road-backend/releases)
[![License: MIT](https://img.shields.io/github/license/F3rren/century-road-backend)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.3.4](https://img.shields.io/badge/Spring%20Boot-3.3.4-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)

Three Spring Boot services behind an API gateway. `auth-service` owns identity: users,
login, and the JWTs every other service will eventually verify. `history-service` answers
"what happened on this date", from Wikipedia. `gateway` is the single entry point and
routes to both. In production a reverse proxy sits in front and terminates TLS.

```
browser ──HTTPS──▶ proxy (Caddy) ──HTTP──▶ gateway ─┬─HTTP─▶ auth-service ────▶ postgres
                   :443                    :8080    │        :8081               :5432
                   TLS, HSTS               routing, │        identity
                                           CORS     │
                                                    └─HTTP─▶ history-service ─▶ Wikipedia
                                                             :8082              (HTTPS, cached)
```

Only the proxy is public in production. The gateway, the services and Postgres talk
over the internal Compose network and are not reachable from outside. Prometheus and
Grafana are published on the host's loopback only, so the way to them is an SSH tunnel.

| Path | Served by |
|---|---|
| `/api/auth/**` | login, token refresh, logout |
| `/api/me/**` | the caller's own profile |
| `/api/admin/users/**` | user administration, admin only |
| `/api/history/**` | [historical events for a date](#history-api), [guided paths and insights](#guided-paths-and-insights), [discovery](#discovery-a-random-event-and-the-same-years-elsewhere), [sources and error reports](#sources-and-reporting-a-mistake), public |
| `/actuator/health` | liveness, public. Every other `/actuator/*` path is a 404 at the proxy |

## Contents

- [Prerequisites](#prerequisites)
- [Two environments, two files each](#two-environments-two-files-each)
- [Local development](#local-development)
- [Production deployment](#production-deployment)
- [First administrator](#first-administrator)
- [Backups](#backups)
- [Container images](#container-images) · [Releasing a version](#releasing-a-version)
- [Running it on Railway](#running-it-on-railway)
- [API documentation](#api-documentation)
- [History API](#history-api)
- [Tests](#tests)
- [Observability](#observability)
- [Security notes](#security-notes)

## Prerequisites

- **Docker** and **Docker Compose** — everything runs in containers.
- **Java 21**, only if you want to build or run the test suite outside Docker. The Maven
  wrapper (`./mvnw`) is committed, so no separate Maven install is needed.

## Two environments, two files each

| | Development | Production |
|---|---|---|
| Compose file | `compose-dev.yml` | `compose-prod.yml` |
| Settings and secrets | `.env.dev` | `.env` |
| Start | `docker compose --env-file .env.dev -f compose-dev.yml up` | `docker compose -f compose-prod.yml up -d` |
| The API is at | `http://localhost:8080` | `https://<PUBLIC_DOMAIN>` |
| Ports | fixed in the file | from `.env` |
| Reverse proxy | none, plain HTTP | Caddy, TLS |

The two share nothing at run time: each has its own settings file, its own project name and
its own volumes, so a value set for one can never leak into the other. There is no default
file on purpose. A bare `docker compose up` does nothing but complain, so you always say
which environment you mean.

### Setting up

Development, once:

```bash
cp .env.dev.example .env.dev
```

Fill in the three `change-me` values (`.env.dev.example` says how). These are throwaway
secrets for your own machine; do not reuse the production ones.

Production, on the server:

```bash
cp .env.example .env
```

Fill in at least these before the first start. `.env` and `.env.dev` are gitignored and must
never be committed.

| Variable | Why it matters |
|---|---|
| `POSTGRES_PASSWORD` | also used by the services to connect |
| `JWT_SECRET` | see below — the service will not start with a bad one |
| `GRAFANA_PASSWORD` | the Grafana admin login; sign-up is disabled, so this is the only way in |
| `GATEWAY_PORT`, `AUTH_PORT` | production only: where the two services listen (see step 3 below) |
| `FRONTEND_ORIGIN` | exact origin of the frontend, or the browser blocks every call |
| `WIKIMEDIA_CONTACT` | a URL or address Wikimedia can reach about this client; `history-service` will not start without it |
| `PUBLIC_DOMAIN` | production only — must already resolve to the host |

`.env.example` and `.env.dev.example` document the rest inline.

### Generating `JWT_SECRET`

```bash
openssl rand -base64 48
```

The value is **base64-decoded** before use, and the decoded result must be at least 32
bytes for HS256. Both rules bite at startup rather than at the first login: a value that
is not valid base64 fails to decode, and one that decodes to fewer than 32 bytes is
rejected as too weak. "32 characters" is not the bar — 32 bytes *after decoding* is,
which is why the command above asks for 48.

## Local development

```bash
docker compose --env-file .env.dev -f compose-dev.yml up
```

Forget `--env-file .env.dev` and Compose stops with a message instead of running on the
production `.env`: `.env.dev` holds a marker without which `compose-dev.yml` will not start.

Source is mounted into the containers and run with `mvnw`, so edits reload. The first start
compiles everything and takes a minute or two. Nothing restarts on its own: `mvnw` exits on
a compile error, and a restart policy would loop it forever instead of leaving the error on
screen. Plain HTTP, no proxy.

The ports are fixed by `compose-dev.yml`, so no `.env` can move them:

| | URL |
|---|---|
| **Gateway** - the one a frontend calls | http://localhost:8080 |
| **API documentation** (Swagger UI) | http://localhost:8080/swagger-ui.html |
| auth-service (direct) | http://localhost:8081 |
| history-service (direct) | http://localhost:8082 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |
| Postgres | `localhost:5433` |
| Remote debug | `5006` auth-service, `5007` gateway, `5008` history-service |

Everything is published on the loopback only. The debug ports accept an unauthenticated
debugger, which is remote code execution for anyone who can reach them, and this file is
meant for laptops on any network. Postgres is on 5433 rather than 5432 because a Postgres
already installed on the machine is common: on Windows Docker does not refuse the port, both
end up listening, and a client such as psql or DBeaver may reach the wrong database without
telling you. The services themselves reach the database as `db:5432` inside the Compose
network, so only tools on your machine care.

`docker compose up` opens nothing by itself: it starts the containers and publishes their
ports on this machine, and that is all. These are APIs answering JSON, not web pages - the
frontend is a separate app, run on its own (Vite's dev server, on 5173). To see the backend
answer, open one of these in a browser:

- http://localhost:8080/actuator/health - is it up
- http://localhost:8080/api/history/on-this-day/10/16?lang=it - real data
- http://localhost:8080/swagger-ui.html - every endpoint, documented, with a form to try it

`docker compose --env-file .env.dev -f compose-dev.yml ps` lists what is published, and in
Docker Desktop each published port in the container list is a link that opens the browser on
it. Grafana and Swagger UI are the two things here that are pages.

**A frontend calls the gateway**, `http://localhost:8080`, never a service directly. The
gateway answers the browser's CORS preflight itself, for the one origin in `FRONTEND_ORIGIN`
(default `http://localhost:5173`, Vite's port). If the frontend runs anywhere else, set that
variable in `.env.dev` and restart the gateway: an origin that is not listed is refused by
the browser before the request ever reaches the API, and the symptom looks like a network
error.

To stop it, `docker compose --env-file .env.dev -f compose-dev.yml down`; add `-v` to wipe
the development database too. Development and production can run side by side on one
machine, except that both want Prometheus on 9090 and Grafana on 3000: stop one first.

## Production deployment

### 1. Point DNS at the host first

`PUBLIC_DOMAIN` has to resolve to the machine **before** the first start. Caddy obtains
the certificate on startup by answering a challenge on port 80, and a domain that does
not resolve yet simply fails it. Ports **80 and 443 must be reachable from the internet**.

### 2. Fill in the production values

```
PUBLIC_DOMAIN=api.yourdomain.example
FRONTEND_ORIGIN=https://app.yourdomain.example
HSTS_MAX_AGE=300
```

`FRONTEND_ORIGIN` must match exactly — scheme, host and port, no trailing slash.
`https://app.example` and `https://app.example/` are different origins to a browser, and
the symptom of getting it wrong is a blocked request that looks like a network error.

### 3. Choose the ports

Two kinds, both read from `.env`.

**The ports the gateway and `auth-service` listen on.** Inside the Compose network only:
nothing publishes them in production. Set them on the production host:

```
GATEWAY_PORT=12129
AUTH_PORT=12130
HISTORY_PORT=12131
```

Those three variables are all there is to change. The proxy, the gateway (which routes to
both `auth-service` and `history-service`), Prometheus and every healthcheck read them, so
nothing else needs editing.

**The ports published on the host.** Only these, and they move on the host side alone:

```
PROMETHEUS_PORT=9090
GRAFANA_PORT=3000
PROXY_HTTP_PORT=80
PROXY_HTTPS_PORT=443
```

`PROXY_HTTP_PORT` and `PROXY_HTTPS_PORT` are the exception to "pick anything": the
certificate challenge always arrives on the public 80/443, so leave them alone unless a
router or firewall forwards those two to the ports you chose.

Neither the gateway nor `auth-service` is reachable from outside the machine in production,
whatever port they use - on purpose. See the security notes for why the gateway must never be
exposed directly: publishing 12129 would put it there.

### 4. Start the stack

```bash
docker compose -f compose-prod.yml up -d
```

That is the whole command. The reverse proxy is part of this file, and there is no override
that could silently swap in the development stack: `compose-dev.yml` is a different file,
with different settings, that this one never reads.

### 5. Create the first administrator

See the section below. Do this before handing the API to anyone.

### 6. Verify TLS and HSTS

```bash
curl -sI https://$PUBLIC_DOMAIN/actuator/health | grep -i strict-transport
```

Expected: `strict-transport-security: max-age=300; includeSubDomains`.

If the header is missing, the request did not reach the proxy over HTTPS, or the proxy is
not running. HSTS is only ever emitted on an HTTPS request — that is deliberate, not a
bug.

`bash infra/caddy/headers_test.sh` checks the same thing without a domain: it starts Caddy with
this Caddyfile in front of a stand-in that sends a one-year HSTS of its own, as `auth-service`
does, and requires that the browser gets ours once and nothing of the service's. CI runs it.

### 7. Only later, raise the HSTS window

Once HTTPS has been stable for a few days, set `HSTS_MAX_AGE=31536000` (one year) and
restart the proxy.

Do not skip straight to a year. A browser that has seen this header refuses plain HTTP
for the whole window, so a certificate that breaks during the first rollout becomes a
lockout users cannot click past. At 300 seconds the same accident is a five minute
nuisance.

`preload` is deliberately absent from the Caddyfile. It is not simply a longer max-age:
it asks to be compiled into the browsers themselves, and getting back off that list takes
months. Add it only when every subdomain is permanently HTTPS.

## First administrator

Creating a user requires an admin token, so an empty database has no way to produce its
first administrator on its own. `FirstAdminBootstrap` exists for exactly that gap.

1. Set **both** variables in `.env`:

   ```
   BOOTSTRAP_ADMIN_EMAIL=you@yourdomain.example
   BOOTSTRAP_ADMIN_PASSWORD=<a long random password>
   ```

2. Start the stack. The administrator is created **only** if both values are set and the
   users table is empty — on any other database the mechanism stays inert and logs that
   it did nothing.

3. Log in once to confirm it worked:

   ```bash
   curl -s -X POST https://$PUBLIC_DOMAIN/api/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"email":"you@yourdomain.example","password":"..."}'
   ```

4. **Clear both variables and restart.** They are passed into the container environment,
   so leaving them set keeps a password readable by anyone who can inspect the container,
   for no further benefit — the mechanism will not fire again anyway.

## Backups

Everything either service knows — every user, every password hash, every view counter — lives
in Postgres's one volume. `infra/postgres/backup.sh`, run daily from cron, dumps both databases
(`${POSTGRES_DB}` and `${POSTGRES_DB}_history`), gzips them, and copies them off this host with
[rclone](https://rclone.org) — so the copy that matters does not depend on this VPS's own disk
surviving. See `.env.example`'s `# ── Backups ──` section for the variables it reads, and
`infra/postgres/backup.cron.example` for the crontab line.

```bash
bash infra/postgres/backup.sh
```

It needs `rclone` installed and configured (`rclone config`, once, on the host — the remote's
own credentials live in `~/.config/rclone/rclone.conf`, never in `.env` or this repository) and
`RCLONE_REMOTE` pointing at wherever backups should land: Backblaze B2, a Hetzner Storage Box,
a second host over SFTP, anything rclone supports. A failed dump posts to `ALERT_WEBHOOK_URL`
(the same one used for [alerting](#observability)) instead of failing silently in a cron log
nobody reads.

**Railway** may offer its own managed Postgres backups (check the plan's *Settings → Backups*
tab) — prefer that over building a second pipeline on top of a database Railway already
manages. This script is for the VPS/Compose path.

### Restoring

An untested backup is not a backup, so this is a runbook, not a second script — the same
reasoning as [First administrator](#first-administrator) above.

1. If restoring onto a **brand-new** Postgres instance (not the one already running), first
   recreate the second database once: `CREATE DATABASE "${POSTGRES_DB}_history";` — the same
   one-time step `infra/postgres/init-history-db.sh` does automatically on a fresh container,
   or the Railway section above does by hand. Restoring onto the *same, still-running* instance
   needs no such step: both databases already exist.
2. Import the dump:

   ```bash
   gunzip -c centuryroad_<timestamp>.sql.gz | \
     docker compose -f compose-prod.yml exec -T db psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"
   ```

   and the same for `centuryroad_history_<timestamp>.sql.gz` against `${POSTGRES_DB}_history`.
3. Spot-check it: `SELECT count(*) FROM users;`, `SELECT count(*) FROM day_views;` against what
   you expect.
4. Restart `auth-service` and `history-service` so their connection pools pick up a clean state.

Worth rehearsing this on `compose-dev.yml` once in a while — it is a fully disposable Postgres,
so a real prod dump can be restored into it at zero risk, which is the only way to know the
backup actually works before the day it has to.

## Container images

For a platform that runs ready-made images rather than a Compose file, CI publishes the three
application services to GitHub Container Registry, on every push to `main` or `develop`, and only
once the tests of all three have passed:

```
ghcr.io/f3rren/century-road-backend-auth-service
ghcr.io/f3rren/century-road-backend-gateway
ghcr.io/f3rren/century-road-backend-history-service
```

Every image carries two tags: the short commit id (`:1a2b3c4`), which never moves and is the one
to pin, and a moving one, `:latest` for what is on `main` and `:develop` for `develop`. A release adds
`:1.2.3` and `:1.2` (see below). They are built from `compose-prod.yml` (`target: prod`, the non-root runtime stage), so a published image
is the one the production stack would have built itself.

The packages inherit the repository's visibility, so on a public repository they can be pulled
without credentials. Check the package's visibility after the first run: a private one needs a
token to pull. Nothing else is published. Postgres, Caddy, Prometheus and Grafana are stock
images that `compose-prod.yml` pulls as they are.

To build the same images by hand:

```bash
docker buildx bake -f compose-prod.yml --load     # tagged ...-gateway:local, and so on
```

### Releasing a version

A version is a tag on a commit of `main`. The images already exist by then: CI built and tested them
when the commit reached `main`. Releasing gives them their version tags and creates the GitHub
release. It builds nothing, so `:1.2.3` is byte for byte what was tested.

```bash
git checkout main && git pull
git tag -a v1.2.3 -m "v1.2.3"
git push origin v1.2.3
```

The **Release** workflow (`.github/workflows/release.yml`) then:

- checks that the commit is on `main` and that its CI run passed, and waits for that run if it is
  still going, so tagging right after the merge is fine;
- tags each of the three images `:1.2.3` and `:1.2`. `:1.2` follows the newest patch: releasing an
  older one later does not pull it back;
- creates the GitHub release, with the image names and the notes GitHub generates from the pull
  requests merged since the previous release.

It refuses, and changes nothing, when the tag is not `vMAJOR.MINOR.PATCH` (no pre-releases yet), the
commit is not on `main`, its CI run failed, a service has no image for the commit, or `:1.2.3` is
already published for a different image. Running it again for the same tag is harmless. In
production pin the full version (`:1.2.3`) or the commit tag: `:latest` and `:1.2` move.

**A tag that already exists**, made before this workflow, such as `v0.1.0`: Actions, Release, Run
workflow, and give the tag. *Dry run* is ticked by default: it does every check and prints what it
would do, and writes nothing. Untick it to do it.

The logic is `.github/scripts/release.sh`. `bash .github/scripts/release_test.sh` tests it with `gh`
and `docker` replaced by stand-ins, so it needs no network, and CI runs it on every push.

### Running the gateway with no proxy of ours in front

On the VPS Caddy hides the actuator and adds the security headers. On a platform that terminates
TLS itself (Railway) nothing of ours does, so the gateway has a `railway` profile that does both.
Turn it on with this variable on the gateway service:

```
SPRING_PROFILES_INCLUDE=railway
```

It has to be `INCLUDE`: the images start with `-Dspring.profiles.active=prod`, which outranks
`SPRING_PROFILES_ACTIVE`, so setting that one changes nothing. Without the variable the gateway
behaves exactly as before.

- **Actuator**: only `/actuator/health` is exposed on the public port, which is what the
  platform's healthcheck needs. Metrics and info are not, and there is no Prometheus to scrape
  them.
- **Headers**: `Strict-Transport-Security`, `X-Content-Type-Options`, `X-Frame-Options` and
  `Referrer-Policy` on every proxied response, the same set as the Caddyfile. HSTS follows
  `HSTS_MAX_AGE`, five minutes if unset: raise it once HTTPS has been stable for a while.
  They replace whatever a service sets itself, as Caddy does: `auth-service` sends an HSTS of
  its own with a one-year max-age, and that value never reaches the browser. The gateway's own
  answers (the actuator, a path with no route) do not carry them.
- **Not covered**: the caller's address. The login rate limiter keys on it, and it depends on
  how the platform's edge sets `X-Forwarded-For`, which has to be checked on a real deployment
  before it is trusted: send a login with a forged `X-Forwarded-For` and read the address in
  `auth-service`'s `Login refused | <address>|<email>` log line.

## Running it on Railway

One Railway project holds the whole stack: Postgres, the three services from the images CI
publishes (see [Container images](#container-images)), and the frontend, which lives in its own
repository. The backend is not built on Railway, and no application secret passes through GitHub.

**Plan.** Hobby or above. On a Trial account, adding services stopped with "Free plan resource
provision limit exceeded", and an unverified Trial restricts outbound network, which
`history-service` needs to reach Wikipedia. Set a usage limit before deploying anything: in the
workspace's Usage page, *Set Usage Limits*. A hard limit takes every service offline when reached.

### The services

Create Postgres first (*Add → Database → PostgreSQL*, keep the name `Postgres`) and wait until it is
up. Then add each of the others with *Add → Docker Image* and **rename it at once** to the name in
the table: the name is the service's address on the private network, `<name>.railway.internal`,
and the gateway is told those addresses by hand. The images are public, so no registry credentials
are needed.

| Service | Image |
|---|---|
| `auth-service` | `ghcr.io/f3rren/century-road-backend-auth-service:<version>` |
| `history-service` | `ghcr.io/f3rren/century-road-backend-history-service:<version>` |
| `gateway` | `ghcr.io/f3rren/century-road-backend-gateway:<version>` |

`<version>` is a released version, such as `0.2.0` (see [Releasing a version](#releasing-a-version)).
Prefer it to `latest`: a version never moves, so a redeploy cannot change what runs. The short commit
id from the package's list of tags does the same for a commit that has not been released.

**Following the releases automatically.** Railway can move a service to the newer versions of its
image by itself: *Settings → Source → Configure Auto Updates*. With a full version tag such as
`:0.2.0` it offers **patches only** or **minor updates and patches**, and a major version is never
automatic. Choose *patches only* (the interface may label it *Security and bugfix patches*: it is the
same option, x.y.**Z**), with a maintenance window (the night one, 02:00-06:00 UTC): a `v0.2.1`
release then reaches production by itself, and `v0.3.0` waits until you change the tag. What gets
deployed is what you release, not what is merged to `main`.

**Railway follows the version number, not what is inside it.** Nothing checks that a patch only
carries fixes: it is a promise kept by whoever tags. So a patch (`v0.2.1`) is for fixes, security
ones included, and anything new is a minor (`v0.3.0`). Tag a feature as a patch and every service
on *patches only* installs it by itself.

What to know before switching it on:

- **It is not immediate.** Railway checks the registry periodically and caches what it finds for up
  to a few hours, and applies the update in the window you chose.
- **Each service updates on its own**, so for a while the three can run different releases. Between
  consecutive releases that is harmless; for a release that changes what the gateway and the
  services say to each other, update by hand.
- **`auth-service` runs its database migrations when it starts, and nothing here backs Postgres up**
  (see *Not covered*). Leave auto updates off for it, and change its tag yourself, until there is a
  backup.
- **Use the full version.** Railway's documentation does not say how it treats `:0.2` (the tag that
  follows the newest patch) or a commit id, and neither has been tried here. Point the services at
  `:0.2.0`, a tag it names as a version.
- **`:latest` is the other mode**, and not the one to use here: Railway would redeploy on every
  push to `main`, with no version to go back to but a commit id.

Variables, in each service's *Variables* tab (the Raw Editor takes them all at once):

Changing `AUTH_PORT` or `HISTORY_PORT` on a running service also needs its healthcheck
target port updated to match (Settings -> Networking), even though neither service has a
public domain - Railway still probes that port internally, and a mismatch fails the deploy
and silently keeps the previous revision running on the old port instead.

```
# auth-service
AUTH_PORT=12130
SPRING_DATASOURCE_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
SPRING_DATASOURCE_USERNAME=${{Postgres.PGUSER}}
SPRING_DATASOURCE_PASSWORD=${{Postgres.PGPASSWORD}}
JWT_SECRET=<openssl rand -base64 48>          # mark it sealed
JWT_EXPIRATION_MS=86400000

# history-service
HISTORY_PORT=12131
WIKIMEDIA_CONTACT=https://github.com/F3rren/century-road-backend
# Same Postgres service as auth-service, but its own database (note the _history
# suffix, appended to whatever Postgres.PGDATABASE actually is) - real SQL-level
# isolation, not just a separate schema. That database does not exist until you
# create it once by hand (see the note right after this block) - without it, or
# without these three variables at all, the service crashes on startup trying to
# run its Flyway migration against an unresolved "${SPRING_DATASOURCE_URL}" or a
# database that isn't there.
SPRING_DATASOURCE_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}_history
SPRING_DATASOURCE_USERNAME=${{Postgres.PGUSER}}
SPRING_DATASOURCE_PASSWORD=${{Postgres.PGPASSWORD}}

# gateway
SPRING_PROFILES_INCLUDE=railway
AUTH_SERVICE_URI=http://auth-service.railway.internal:12130
HISTORY_SERVICE_URI=http://history-service.railway.internal:12131
# GATEWAY_PORT must stay 8080: it is the target port the gateway's public
# domain is wired to (Settings -> Networking), independent of AUTH_PORT/
# HISTORY_PORT on the other two services, which are private-network only.
# Changing it here without also changing that target port fails the
# healthcheck. The app never reads PORT, only GATEWAY_PORT - no need to set it.
GATEWAY_PORT=8080
FRONTEND_ORIGIN=https://<the frontend's public domain>
```

**Before `history-service` can start for the first time**, its database has to exist - Railway's managed Postgres doesn't run the custom init script `compose-dev.yml`/`compose-prod.yml` use for this locally. One-time step, on the `Postgres` service: *Connect* tab → *psql* (or any Postgres client with the connection string shown there), then:

```sql
CREATE DATABASE "<PGDATABASE value>_history";
```

`<PGDATABASE value>` is whatever the `Postgres` service's own `PGDATABASE` variable actually is (check its *Variables* tab - `${{...}}` references only expand for other Railway services, not inside a manual psql session). Nothing else to do afterward: `history-service` creates its own tables in that database the first time it starts, via Flyway.

On the gateway service, *Settings → Networking → Generate Domain* (port 8080), and set the
healthcheck path to `/actuator/health`. `SPRING_PROFILES_INCLUDE=railway` is what hides the
actuator and adds the security headers: see
[Running the gateway with no proxy of ours in front](#running-the-gateway-with-no-proxy-of-ours-in-front).
For the first administrator, see [First administrator](#first-administrator).

Two things that cost time the first time:

- **Variable changes are staged.** Railway keeps them pending until you confirm the deploy.
- **The names must match.** If the gateway logs `Failed to resolve '<name>.railway.internal'` with
  `NXDOMAIN`, no service has that name. Rename the service, or use the name it really has in the
  `*_SERVICE_URI` variables. Until the routes load, the gateway still answers `/actuator/health`
  with `UP`, so health alone does not prove it works.

### Checking it

```bash
G=https://<the gateway's public domain>
curl -s $G/actuator/health                                          # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' $G/actuator/prometheus     # 404
curl -s -o /dev/null -w '%{http_code}\n' $G/api/history/on-this-day/10/16     # 200
curl -s -D - -o /dev/null -X POST -H 'Content-Type: application/json' \
  -d '{"email":"nobody@example.test","password":"wrong"}' $G/api/auth/login   # 401, and:
#   strict-transport-security: max-age=300; includeSubDomains   (once, not the service's own)
```

And that the login limiter cannot be talked around. Five wrong attempts a minute are allowed per
address and email, so seven with a different forged address each must still be limited:

```bash
for i in 1 2 3 4 5 6 7; do
  curl -s -o /dev/null -w '%{http_code} ' -X POST -H 'Content-Type: application/json' \
    -H "X-Forwarded-For: 10.0.0.$i" \
    -d '{"email":"limit-check@example.test","password":"wrong"}' $G/api/auth/login
done; echo                                  # 401 401 401 401 401 429 429
```

On Railway's edge this held: a client cannot choose the address the limiter sees, whether through
`X-Forwarded-For` or `Forwarded`. It is behaviour of the platform, so run it again if that ever
comes into doubt. The `Login refused | <address>|<email>` lines in `auth-service`'s log show which
address was used.

### The frontend

The frontend repository has a `Dockerfile.railway` that serves the compiled app with Caddy. Add it
with *Add → GitHub Repo*, pick the branch to deploy (`main` for production, with *Wait for CI*
on), and set:

```
RAILWAY_DOCKERFILE_PATH=Dockerfile.railway
VITE_API_BASE_URL=https://<the gateway's public domain>/api
```

`VITE_API_BASE_URL` is compiled into the app, so changing it means a new build. The gateway allows
one origin, `FRONTEND_ORIGIN`, spelled exactly like the address in the browser with no trailing
slash: the address of a preview deployment is a different origin and is refused.

### Not covered

- **Backups — a known, accepted gap.** [`infra/postgres/backup.sh`](#backups) covers the
  VPS/Compose path; it does not run here. Railway's own scheduled backups and point-in-time
  recovery need the **Pro** plan; on a lower plan the only backups are the ones Railway takes
  on its own initiative before some platform-side changes (e.g. a security patch), which are
  not something to plan around. Revisit this — either the Pro plan, or a scheduled
  `pg_dump` against Railway's public Postgres connection string — before the data here is worth
  more than the cost of losing it.
- **Application metrics.** The gateway exposes only `/actuator/health` here, and there is no
  Prometheus or Grafana in this setup, so none of the [alerting rules](#observability) — the
  circuit breaker, upstream latency, stale-serve rate ones — apply: that telemetry simply does
  not exist on this deployment.
- **Platform-level alerting is covered, differently.** Project Settings → Webhooks, pointed at
  a Discord incoming webhook, notifies on *Volume Alert Triggered*, *Monitor Triggered*,
  *Deployment Crashed*, *Deployment Oom Killed* and *Deployment Failed*. Coarser than the
  Grafana rules — it knows a service crashed or a volume is filling up, not that the Wikipedia
  circuit breaker is open — but it is real coverage for "is anything on fire", configured
  without running anything extra.

## API documentation

Every endpoint is documented in OpenAPI and browsable in Swagger UI. In development it is at
http://localhost:8080/swagger-ui.html, served by the gateway for every service at once: pick
`auth-service` or `history-service` from the list at the top. "Try it out" sends its calls to
the gateway, the way a frontend does, so there is no CORS to get in the way. For the protected
endpoints, call `/api/auth/login`, copy the `token` from the answer and paste it into
"Authorize". (A service's definition loads only while that service is running: the gateway
relays the document from it, so a stopped service shows as an error in the list.)

This is the contract, the machine-readable one. The sections below are the guide to the
history API, for a person reading it through.

**How it fits together.** Each service publishes its own OpenAPI document at `/v3/api-docs`
(springdoc, the API module only). The gateway relays it at `/docs/<service>/v3/api-docs`, GET
only and only that path, because in production the services are not reachable from a browser,
and serves the one Swagger UI that reads them. The documents declare a relative server (`/`),
so a call always goes to whichever origin served the page.

**It is off unless switched on.** The `dev` profile turns it on, and nothing else does: the
gateway is the one public address in production, and describing an API is something to enable
on purpose. `compose-prod.yml` passes none of the switches, so a production stack made from it
never serves them. Anywhere else, for a staging environment say, set on `auth-service`,
`history-service` and `gateway`:

```
SPRINGDOC_API_DOCS_ENABLED=true
```

and on the `gateway` also `SPRINGDOC_SWAGGER_UI_ENABLED=true`. Without them a service answers
`/v3/api-docs` like any other route (`auth-service`: a 401, like everything without a token) and
the gateway has no page and no relay route at all.

**Adding an endpoint.** Two tests fail until it is documented, in each service, so it cannot be
forgotten: one walks the endpoints the application really has and wants each in the document
with a summary, and one wants every field of every request and answer to have a description.
What it takes:

- on the controller, `@Tag`; on each method, `@Operation(summary = ...)` and an `@ApiResponse`
  for each refusal it can produce, with the `error` code in the description;
- on each DTO field, `@Schema(description = ...)`;
- on a protected controller, `@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)`.

Two things learned the hard way. Do not give an `example` to optional parameters that cannot be
combined: "Try it out" sends every example, so a first click on Execute would be a 400 (this
happened with `year` and `fromYear`/`toYear`). And a validation such as `@Pattern(regexp =
"(?i)...")` is published as it is, where an inline flag is a syntax error for JavaScript;
`auth-service` strips those (`OpenApiConfig`), and the allowed values are listed as an enum
instead.

springdoc is pinned to 2.6.0, the last release built on Spring Boot 3.3. Its newer lines need
newer Spring Boot, so it moves with Spring Boot and not on its own; `dependabot.yml` does not
propose its minor or major bumps.

## History API

`GET /api/history/on-this-day/{month}/{day}` returns what happened on a calendar day, from
Wikipedia's "On this day" feed. Public: no token, nothing per-user in it.

| Parameter | Meaning | Default |
|---|---|---|
| `month`, `day` (path) | any real date; `2/29` is valid, `2/30` is a 400 | required |
| `lang` | `it` or `en` | `it` |
| `types` | any of `selected`, `events`, `births`, `deaths`, `holidays`; comma-separated or repeated | all five |
| `year` | one year; negative for before the common era (`-44`) | none |
| `fromYear`, `toYear` | an inclusive range, either end optional; not combinable with `year` | none |

```bash
curl 'https://<host>/api/history/on-this-day/10/16?lang=it&types=events,births&fromYear=1900'
```

The answer is the usual envelope. `data.sections.<type>` holds `items` plus three fields
about where they came from:

- `language`: the edition that really supplied the items. It is not always the one asked
  for: **the Italian feed has no births or deaths at all**, so those come from English,
  with `fallback: true`. A frontend should say so rather than pass English off as Italian.
- `stale`: the copy is older than six hours because Wikipedia could not be reached to
  refresh it. Old history is served in preference to an error, for up to seven days.
- `data.warnings`: `PRIMARY_UNAVAILABLE` (the language asked for could not be fetched, all
  sections are from the fallback) or `FALLBACK_UNAVAILABLE` (a gap could not be filled).

#### Reading an item

An item has `text`, `year` (absent for holidays, negative before the common era) and `pages`.
**`text` is the event.** `pages` are the Wikipedia articles *linked from that text*, in the
order they appear in it. They are related reading, not "the article about the event" (abridged
example):

```json
{
  "year": 1992,
  "text": "Referendum in Francia sull'adesione al Trattato di Maastricht: vincono i \"sì\"",
  "pages": [ { "title": "Referendum" }, { "title": "Francia" }, { "title": "Trattato di Maastricht" } ]
}
```

Most events have no article of their own, so the first page can be as generic as
"Referendum" or "Beirut". A page's `description` and `extract` describe *that article*, not
the event it was linked from: the extract of "Beirut" says what Beirut is, not what happened
there. So show `text` as the headline and `pages` as "related articles", and never use
`pages[0]` as an event's title or summary.

Births and deaths are the one place where `pages[0]` is usually right: `text` opens with the
person's name and the first link is that person. Usually is not always, and nothing in the
answer says which one is the person, so treat it as a convenience rather than a guarantee.

A page has a `title` and a `url`, and, when Wikipedia has them, what a card needs. A key that
has no value is left out, not sent as `null`.

| Field | What it is |
|---|---|
| `description`, `extract` | the article's one-line description and its opening paragraph, as plain text |
| `thumbnail`, `originalImage` | the same picture at two sizes, each with `url`, `width`, `height` and `filePageUrl`. Use the thumbnail in lists and the original in a detail view: originals can be several megabytes, and the feed has some over 8,000 pixels wide, so check `width` and `height` before loading one |
| `coordinates` | `lat` and `lon` in decimal degrees, on the pages that have a place. Values outside the range of a place on Earth are dropped |
| `wikibaseItem` | the Wikidata id, such as `Q3820`. It is the same in every language, so "Beirut" from `it` and from `en` can be matched without comparing titles |

#### Filtering by year

The year filter is applied here, not by Wikipedia, which cannot filter by year, so it
narrows a single day; it cannot answer "everything that happened in 1789". For one country
at a time, the [country index](#events-by-country) can. Holidays have no year and are left
out once a year filter is set.

| Status | `error` | Meaning |
|---|---|---|
| 400 | `INVALID_DATE`, `UNSUPPORTED_LANGUAGE`, `INVALID_TYPE`, `INVALID_YEAR`, `BAD_REQUEST` | the request cannot be answered; nothing was asked of Wikipedia |
| 503 | `UPSTREAM_UNAVAILABLE` | Wikipedia unreachable, and no copy and no other language to fall back on |
| 503 | `UPSTREAM_RATE_LIMITED` | Wikipedia asked us to slow down; `Retry-After` says for how long |
| 502 | `UPSTREAM_BAD_RESPONSE` | Wikipedia answered with something unusable, e.g. the API has changed |

### Events by country

A day's answer covers one day, and its pages carry coordinates, not countries. For
"everything Wikipedia lists in Italy" the service keeps a **country index**: every day of the
year in both editions, with each event placed in a country. Two endpoints read it, public like
the day's one. Neither calls Wikipedia: they read the database.

`GET /api/history/countries?lang=it` lists the countries with at least one event, by code:

```json
{ "success": true, "data": [ { "countryCode": "AT", "eventCount": 56 }, { "countryCode": "AU", "eventCount": 54 } ] }
```

`GET /api/history/countries/{code}/timeline` is one country's events, oldest first:

| Parameter | Meaning | Default |
|---|---|---|
| `code` (path) | ISO 3166-1 alpha-2, uppercase: `IT`, not `it` | required |
| `lang` | `it` or `en` | `it` |
| `fromYear`, `toYear` | an inclusive range, either end optional; negative before the common era | none |

```bash
curl 'https://<host>/api/history/countries/IT/timeline?lang=it&fromYear=1901&toYear=2000'
```

`data` holds the events and the same `attribution` as a day's answer (abridged):

```json
{
  "countryCode": "IT",
  "language": "it",
  "indexedAt": "2026-10-02T00:41:13.120511Z",
  "events": [
    { "year": 1904, "month": 1, "day": 26, "text": "A Torino un incendio distrugge metà del patrimonio della Biblioteca Nazionale" }
  ]
}
```

What is in the index:

- **Events only**, and only those with a year: not the featured selection, births, deaths or
  holidays.
- **Placed the way the frontend's map places them.** The first linked page with coordinates
  decides, inside the same Natural Earth 1:110m country shapes: the very file, which
  `CountryLocatorTest` checks by its hash. An event whose first located page is at sea, or in a
  place the dataset has no code for (Kosovo, Somaliland, Northern Cyprus), is left out, and so
  is one with no located page at all: about half of each day's events (51% placed in a
  full local pass, 17,516 rows, 4.4 MB).
- **Each edition on its own**, with no fallback between them. `en` holds about half again as
  many events as `it` (10,716 against 6,800).
- **Up to a day behind Wikipedia.** `indexedAt` is when the newest of the returned events was
  written, and is absent when there are none.

A valid code with nothing indexed is a 200 with no events, not a 404. Both answers are cached
for five minutes (`Cache-Control: max-age=300, public`): short, because a new index fills while
it is being read.

| Status | `error` | Meaning |
|---|---|---|
| 400 | `INVALID_COUNTRY_CODE`, `UNSUPPORTED_LANGUAGE`, `INVALID_YEAR`, `BAD_REQUEST` | the request cannot be answered |

#### How the index is built

`TimelineIndexer` reads all 366 days (29 February included) in both editions, 732 requests one
at a time, and replaces each day's rows as soon as that day is read. The table is never emptied
as a whole, so it always has an answer. The pass runs:

- **every night at 00:00 UTC**, and takes a little over an hour (72 minutes measured), so it
  is over before Railway's 02:00-06:00 update window;
- **at startup, when the table is empty**, in the background: the service is up and healthy at
  once, and the country endpoints fill in as the pass goes.

A day Wikipedia cannot serve keeps the rows it had, and ten such days in a row stop the pass:
by then the trouble is upstream, and pressing on would only add to it. Expect a few: a
day's full feed sometimes takes Wikipedia longer than the 5-second read timeout to put
together (18 of 732 requests in a full local run), and the next night fills it in.

A pass cut short, by a redeploy say, leaves every day it did not reach as it was, so a first build cut short stays
partial until the next night. `Country index pass done` in the log closes each pass, with
what it refreshed, kept and wrote.

| Variable | Default | |
|---|---|---|
| `HISTORY_TIMELINE_CRON` | `0 0 0 * * *` | when the pass runs: six-field Spring cron, in UTC. `-` switches the job off, the build at startup included |
| `HISTORY_TIMELINE_DELAY` | `1s` | the pause between two requests |
| `HISTORY_TIMELINE_MAXCONSECUTIVEFAILURES` | `10` | days in a row that may fail before the pass stops |

**Rebuilding it by hand.** Every row is derived from Wikipedia, so nothing is lost: empty the
table and restart the service, and it rebuilds the index in the background.

```sql
TRUNCATE history.timeline_events;
```

**One instance.** Each running copy of the service runs the pass, so two would double the
requests and could write a day twice. Railway runs one; before running more, the job needs a
shared lock (ShedLock, say).

**Releasing it.** The index arrived in 0.5.0, a minor version, so a service on Railway's
*patches only* stays on 0.4.x until its tag is changed by hand. Its first start applies
migration `V2`, which only adds the table, and then builds the index. Going back to 0.4.x is
safe: Flyway leaves alone a migration newer than the ones it knows, and 0.4.x never reads the
table.

### Guided paths and insights

Wikipedia's feed says *what* happened on a day. Three endpoints say *why it matters* and
*where to start*, from content written by hand and kept in this repository. None of them asks
Wikipedia or the database, so they answer even when Wikipedia does not. Everything is in
Italian, and cached for an hour: it only changes with a release.

| Endpoint | Answers |
|---|---|
| `GET /api/history/start-here` | "Inizia da qui": a few hand-picked paths and events, each with a sentence on why to open it |
| `GET /api/history/paths` | every guided path as a card: title, one sentence, cover, reading time, number of stops |
| `GET /api/history/paths/{slug}` | one path: introduction and its stops in order, each with the place for the map to move to |
| `GET /api/history/insights?month=&day=` | the events that have an insight, optionally those of one day |
| `GET /api/history/insights/{slug}` | "Perché conta" for one event |

**A path** is a small itinerary of 6 to 10 stops, not a category. A stop carries its `place`
(`lat`, `lon`, for the map), its `date`, and `narrative`: the line that ties it to the stop
before. Opening a stop is a second request, `GET /api/history/insights/{slug}` with the stop's
`slug`, so a path stays small. `readingMinutes` is counted from the words at 200 a minute, never
written by hand. The cover, when there is one, is an image from Wikimedia Commons, with its file
page to credit.

**An insight** is told in three parts, as the product asks: `before` (what prepared the event),
`event` (what happened) and `after` (what followed - developments, not all direct effects).
Around them: `related`, two or three insights to read next (the data for "Continua a
esplorare"); `inPaths`, the paths that have a stop on it; `sources`, links where each claim can
be checked; `notes`, caveats about dates and places; and `provenance`.

To mark "Approfondimento disponibile" on a day's events, ask `GET
/api/history/insights?month=10&day=4` next to the day's own request and match on `date.year`.
Events carry no identifier, so the match is by date: that is why the insight is its own resource
and the on-this-day answer, a faithful copy of Wikipedia's, is left as it is.

**What the frontend must show, not drop:**

- `place.approximate: true` means the pin is a stand-in - a launch site for an event on the Moon
  or in orbit - and `place.note` says so. Show the note.
- `provenance.reviewedAt` is the day somebody really checked the text against its sources. It is
  **absent when nobody did**: show no date then, and do not call such an insight "verified".
- `notes` are caveats (a date that depends on the time zone). Show them when there are any.

#### Writing content

The content is JSON in `service/history-service/src/main/resources/editorial`:

```
editorial/insights/<slug>.json   one per event
editorial/paths/<slug>.json      one per path
editorial/start-here.json        the "Inizia da qui" proposals (1 to 6)
```

The file name is the slug. Adding content is a pull request, reviewed like code, and a release.

The service **checks every file at startup and refuses to start** with every problem listed: an
unknown key (a `"befor"` is an error, not an insight with no past), a stop that opens an insight
that does not exist, a path of three stops, two or three `links` required per insight, an
approximate pin with no note, a cover that is not on Commons. `EditorialContentTest` runs the
same checks on the real files in CI, so a mistake fails the build and not the deploy.

**Review dates.** Texts are first written as drafts, with no `reviewedAt`. When a person has
read one against its sources, they add `"reviewedAt": "YYYY-MM-DD"` to its `provenance`, in the
same commit. Nothing generates that date, and the service refuses one in the future.

The first path and its nine insights are **drafts** in exactly that sense: they have no review
date, and should be read against their sources and corrected before they are presented as more
than that.

### Discovery: a random event and the same years elsewhere

Two ways into the data that are not a search. Both read the [country index](#events-by-country),
so neither asks Wikipedia, and both are only as complete as the index.

`GET /api/history/random` - "Sorprendimi". One event picked at random, from those that match the
filters the visitor has active: `country` (ISO code), `fromYear`, `toYear`, `lang`. It carries its
date and country, so the frontend can open that day and select that country. Never cached. When
nothing matches it is a `200` with no `event`.

`GET /api/history/same-period?year=1969` - "Nello stesso periodo". The index's events in the
years around `year` (`span`, 0 to 25, default 5), by country, leaving out `excludeCountry` (the one
the visitor is looking at), at most `perCountry` (default 3) each, the closest to the year kept.

It is a comparison in time and says so (`comparison: "TEMPORAL"`, and a `notice` to show): the
events were contemporary, not connected. And it says how thin the data is: `coverage.level` is
`NONE`, `SPARSE` (under 5 events or under 3 countries) or `OK`, with a `note` to show at every
level, because even `OK` is a selection. The index holds only what Wikipedia lists on its day pages
and the map can place, about half of it, so an empty window is far more often a gap in the data
than a quiet world. A frontend should show `SPARSE` as "poco materiale", not as "nothing happened".

"Near this place" has no endpoint of its own: the map selects countries, and a country's events
across the year are [`/countries/{code}/timeline`](#events-by-country).

### Sources, and reporting a mistake

`GET /api/history/sources` is the data behind a "Il progetto e le fonti" page: every source of
content with its licence and whether it must be credited; how much there is (the index per
edition - events, countries, oldest and newest year, when last written - and how many paths and
insights, of which how many were reviewed); and `limits`, what the content is not, in Italian.
It is computed from the real state of the service, so it cannot go stale. The prose of the page -
what the project wants to tell, how contents are chosen - is the frontend's to write.

It is **provenance, not a seal**: nothing in it says "verified". The one source that is the
project's own, its editorial content, declares no licence, because none has been chosen; that is
a decision for the owner, not for the code.

`POST /api/history/reports` - "Segnala un errore". The frontend fills in `target` from the card
the visitor is on - an `EVENT` by its `year`, `month`, `day`, `language` and `text` (an event has
no identifier of its own), an `INSIGHT` or a `PATH` by its `slug` - so the visitor only says what is
wrong:

```bash
curl -X POST 'https://<host>/api/history/reports' -H 'Content-Type: application/json' -d '{
  "target": {"type": "EVENT", "year": 1957, "month": 10, "day": 4, "language": "it", "text": "Viene lanciato lo Sputnik 1."},
  "category": "WRONG_DATE",
  "message": "A Baikonur era già il 5 ottobre.",
  "contact": "nome@example.org"
}'
```

`category` is `WRONG_DATE`, `WRONG_PLACE`, `WRONG_TEXT`, `BROKEN_LINK` or `OTHER`; `message` is 10 to
1000 characters; `contact` is an optional email address, only for replying. The answer is a `201`
with the report's number. A malformed report is a `400` (`INVALID_REPORT`, `INVALID_DATE`, ...) and
**does not count against the limit**. The body must be `application/json` (anything else is a `415`)
and is read up to 8 KB, whatever the client announces: a report is a few hundred bytes, and this is
an endpoint anybody can call.

It is the one public endpoint that writes, so it is limited, in memory and per instance, as
auth-service's login is:

| Variable | Default | |
|---|---|---|
| `HISTORY_REPORTS_MAXPERCLIENT` | `5` | reports one address may send per window |
| `HISTORY_REPORTS_MAXTOTAL` | `200` | reports the whole service accepts per window |
| `HISTORY_REPORTS_WINDOW` | `1h` | the window |

Over either limit it is a `429` with `Retry-After`. "One address" is the caller's real address
only because `server.forward-headers-strategy: framework` is set in the `prod` profile, as in
auth-service: history-service is reachable only through the gateway.

**What is stored** (migration `V3`): the report and what it is about, and nothing that identifies
the visitor - no address, no session. `contact` is the one exception, and only if they chose to
leave it. **It is personal data**: use it to reply, then clear it, and say so in the privacy notice
of the frontend. There is no screen for reports yet; read them in SQL:

```sql
SELECT id, created_at, target_type, target_slug, target_year, target_month, target_day,
       category, message, contact
FROM history.error_reports WHERE handled_at IS NULL ORDER BY created_at;

UPDATE history.error_reports SET handled_at = now(), contact = NULL WHERE id = 42;
```

An admin view is the next step, and needs history-service to verify `auth-service`'s JWTs, which it
does not do yet.

### Being a good neighbour to Wikipedia

Wikipedia is a shared resource with rules, and this service follows them:

- **It says who it is.** Every request carries a `User-Agent` naming the client and
  `WIKIMEDIA_CONTACT`. Wikimedia's policy asks for exactly that, and blocks clients that do
  not, without notice.
- **It asks rarely.** What callers cause is bounded by the cache: one entry per language and
  day, kept six hours. The load on Wikipedia depends on how many *different* days are looked
  at, not on how many people call: at most one request per language and day every six hours,
  and a hundred simultaneous callers for the same day cost one request.
- **Its own work is bounded too.** The [country index](#how-the-index-is-built) adds one
  pass a night, whoever calls: 732 requests, one at a time and at least a second apart. It
  waits out any cool-down Wikipedia has asked for, and stops after ten failures in a row.
- **It asks gently.** At most three requests in flight (the nightly pass takes one), gzip on,
  one retry for a transient failure, and a circuit breaker that leaves Wikipedia alone after a
  run of errors.
- **It backs off when told to.** After a 429 nothing is sent for as long as `Retry-After`
  says, and callers are answered from cache or the other language in the meantime.
- **It never follows a redirect**, and the language is a fixed list, so what it contacts is
  never decided by a caller or by a response.

### Licence: what you must show

Wikipedia's text is **CC BY-SA 4.0**. Whatever shows it must credit Wikipedia, link the
article, and name the licence. The response carries all three: `data.attribution`
(source, licence, licence URL, a ready notice) and, per entry, `pages[].url`. Show them.

Images are not covered by that licence: each file has its own. The service therefore
forwards only images hosted on Wikimedia Commons, which accepts only free files, and gives
each, thumbnail or original, a `filePageUrl` naming its author and licence. The feed serves
Commons images from both `upload.wikimedia.org` and `thumb.wikimedia.org`, and both count.
Images uploaded to a single wiki, where
non-free "fair use" pictures live, are dropped, because nothing in Wikipedia's answer says
which are which.

## Tests

```bash
cd service/auth-service    && ./mvnw test    #  71 tests
cd service/gateway         && ./mvnw test    #  43 tests
cd service/history-service && ./mvnw test    # 383 tests
bash .github/scripts/release_test.sh           #  81 checks: the release script, no network
bash infra/caddy/headers_test.sh               #  11 checks: the Caddyfile's headers, needs Docker
```

`auth-service` runs its integration tests against a real PostgreSQL started through
Testcontainers, so **Docker must be running and able to pull images**. Without it those
tests do not fail on an assertion — the Spring context never starts, and every test in the
five integration classes errors with `Could not find a valid Docker environment`.

That message is misleading: it also appears when Docker is running fine but cannot pull,
or when the daemon rejects the API version the client asks for. When it shows up, read the
`Attempted configurations were:` block just above it — it names the real reason for each
strategy that was tried.

The gateway suite needs no Docker: it stubs its upstream in-process. `history-service` needs
it for its database, like `auth-service`, but never reaches the real Wikipedia: its tests
talk to a stand-in on a local port.

## Observability

Every service exposes `/actuator/health` and `/actuator/prometheus`. Prometheus scrapes
them over the internal network and Grafana is provisioned with it as a datasource, so the
datasource comes up with no manual wiring. Configuration lives under `infra/`.

In production Prometheus and Grafana listen on the host's loopback only. From your
machine, open a tunnel and use them as if they were local:

```bash
ssh -L 3000:localhost:<GRAFANA_PORT> -L 9090:localhost:<PROMETHEUS_PORT> user@your-server
```

Then Grafana is at http://localhost:3000 and Prometheus at http://localhost:9090.

`history-service` adds a few metrics of its own: `history_upstream_requests_seconds` (Wikipedia
calls by outcome: `ok`, `rate_limited`, `unavailable`, `bad_response`), `history_stale_served_total`,
`history_fallback_total`, and the cache and circuit-breaker gauges. A rising `bad_response` means
the integration is broken, not the network.

A dashboard for all of this (`history-service-overview`, provisioned from
`infra/grafana/provisioning/dashboards/history-service-overview.json`) is there from the first
start — no manual import.

**Alerting** is Grafana's own built-in alerting, not a separate Alertmanager: it evaluates
directly against the Prometheus datasource already wired above, and its rules, contact point
and notification policy are provisioned from `infra/grafana/provisioning/alerting/`, so they
exist from the first start too. Five rules, all on metrics already listed above: a service down,
the Wikipedia circuit breaker open, upstream latency degraded, a spike in stale responses, and
the Wikipedia bulkhead fully saturated — see `rules.yaml` for the exact thresholds. Notifications
go to `ALERT_WEBHOOK_URL` (a Discord incoming webhook by default; edit `contact-points.yaml` for
Slack instead), the same variable `infra/postgres/backup.sh` uses for a failed backup, so there
is one operational channel, not several.

After the first deploy, check **Alerting → Alert rules** in Grafana: every rule should show a
real Normal/Pending/Firing state. These rules were written against the metrics' names and were
not exercised against a running Grafana while adding them — if one shows an evaluation error
instead, open it in the Grafana UI, which will say why, and fix it there; editing the exported
YAML by hand afterward is easier than getting it right blind a second time.

The proxy serves `/actuator/health` and answers 404 to every other `/actuator/*` path.
The gateway shares its port between the API and its actuator, so without that filter the
metrics would be public.

## Security notes

- **Postgres is published only in dev**, on the loopback (`localhost:5433`, in
  `compose-dev.yml`). `compose-prod.yml` publishes nothing for the database.
- **Prometheus and Grafana are loopback-only.** Prometheus runs with
  `--web.enable-lifecycle` and no authentication, so anyone who could reach its port could
  shut it down. Do not change those bindings to `0.0.0.0` to save an SSH tunnel.
- **Never commit `.env` or `.env.dev`.** They hold the JWT signing secret; anyone with it can
  mint valid tokens for any user. Use different secrets in the two.
- **`rclone.conf` (the backup off-box storage credentials) lives outside this repository**,
  at `~/.config/rclone/rclone.conf` on the host — same reasoning as `.env`. `ALERT_WEBHOOK_URL`
  is lower stakes (it can post to the ops channel, nothing more) but still belongs only in
  `.env`, not committed.
- **Published images are scanned for known vulnerabilities** (Trivy, in `publish-images`) before
  the moving tag (`latest`/branch) is pointed at them; results are in the repository's Security
  tab, alongside CodeQL's.
- **`server.forward-headers-strategy` is enabled in the `prod` profile**, so the services
  trust the `X-Forwarded-*` headers they receive. That is safe only because the proxy
  overwrites them rather than passing on what the caller sent. The standard `Forwarded`
  header is the one the proxy does not set: it is dropped there, and the gateway ignores it
  as well, because Spring prefers it to `X-Forwarded-For` and a caller could otherwise pick
  the address the login rate limiter sees. If you ever expose the gateway directly, remove
  the setting first: a caller could otherwise claim any address it likes through
  `X-Forwarded-For` and walk around the login rate limiting.
