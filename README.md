# Century Road — Backend

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
| `/api/history/**` | [historical events for a date](#history-api), public |
| `/actuator/health` | liveness, public. Every other `/actuator/*` path is a 404 at the proxy |

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
```

Those two variables are all there is to change. The proxy, the gateway (which routes to
`auth-service`), Prometheus and every healthcheck read them, so nothing else needs editing.
`history-service` stays on 8082.

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
  `:0.2.0`, a tag it names as a version. Try it on `history-service` first: it has no database.
- **`:latest` is the other mode**, and not the one to use here: Railway would redeploy on every
  push to `main`, with no version to go back to but a commit id.

Variables, in each service's *Variables* tab (the Raw Editor takes them all at once):

```
# auth-service
SPRING_DATASOURCE_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
SPRING_DATASOURCE_USERNAME=${{Postgres.PGUSER}}
SPRING_DATASOURCE_PASSWORD=${{Postgres.PGPASSWORD}}
JWT_SECRET=<openssl rand -base64 48>          # mark it sealed
JWT_EXPIRATION_MS=86400000

# history-service
WIKIMEDIA_CONTACT=https://github.com/F3rren/century-road-backend

# gateway
SPRING_PROFILES_INCLUDE=railway
AUTH_SERVICE_URI=http://auth-service.railway.internal:8081
HISTORY_SERVICE_URI=http://history-service.railway.internal:8082
GATEWAY_PORT=8080
PORT=8080
FRONTEND_ORIGIN=https://<the frontend's public domain>
```

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

- **Backups.** Every user and password hash is in Postgres's one volume. Nothing here backs it up:
  check what the plan offers before there is data worth losing.
- **Metrics.** The gateway exposes only `/actuator/health` here, and there is no Prometheus or
  Grafana in this setup. Railway's own logs and resource graphs are what you have.

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
narrows a single day; it cannot answer "everything that happened in 1789". Holidays have no
year and are left out once a year filter is set.

| Status | `error` | Meaning |
|---|---|---|
| 400 | `INVALID_DATE`, `UNSUPPORTED_LANGUAGE`, `INVALID_TYPE`, `INVALID_YEAR`, `BAD_REQUEST` | the request cannot be answered; nothing was asked of Wikipedia |
| 503 | `UPSTREAM_UNAVAILABLE` | Wikipedia unreachable, and no copy and no other language to fall back on |
| 503 | `UPSTREAM_RATE_LIMITED` | Wikipedia asked us to slow down; `Retry-After` says for how long |
| 502 | `UPSTREAM_BAD_RESPONSE` | Wikipedia answered with something unusable, e.g. the API has changed |

### Being a good neighbour to Wikipedia

Wikipedia is a shared resource with rules, and this service follows them:

- **It says who it is.** Every request carries a `User-Agent` naming the client and
  `WIKIMEDIA_CONTACT`. Wikimedia's policy asks for exactly that, and blocks clients that do
  not, without notice.
- **It asks rarely.** One entry per language and day, kept six hours. The load on Wikipedia
  depends on how many *different* days are looked at, not on how many people call: at most
  one request per language and day every six hours, and a hundred simultaneous callers for
  the same day cost one request.
- **It asks gently.** At most three requests in flight, gzip on, one retry for a transient
  failure, and a circuit breaker that leaves Wikipedia alone after a run of errors.
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
cd service/history-service && ./mvnw test    # 224 tests
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

The gateway suite needs no Docker: it stubs its upstream in-process. So does
`history-service`: its tests talk to a stand-in for Wikipedia on a local port and never
reach the real one.

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
- **`server.forward-headers-strategy` is enabled in the `prod` profile**, so the services
  trust the `X-Forwarded-*` headers they receive. That is safe only because the proxy
  overwrites them rather than passing on what the caller sent. The standard `Forwarded`
  header is the one the proxy does not set: it is dropped there, and the gateway ignores it
  as well, because Spring prefers it to `X-Forwarded-For` and a caller could otherwise pick
  the address the login rate limiter sees. If you ever expose the gateway directly, remove
  the setting first: a caller could otherwise claim any address it likes through
  `X-Forwarded-For` and walk around the login rate limiting.
