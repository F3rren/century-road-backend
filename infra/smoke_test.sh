#!/usr/bin/env bash
# Starts the three packaged services the way production does - their jars, the prod profile, a real
# Postgres, each service as its own database role (infra/postgres/app-roles.sql) with its passwords and
# JWT secret only as files named after their property (what compose-prod.yml mounts in /run/secrets) -
# and asks the gateway for something from each. The test suites cannot catch a service that
# only fails once packaged: they run on the test classpath, which has more on it than the jar (Boot 4's
# split modules made that real: history-service passed every test and did not start).
#
#     PGHOST=localhost PGPORT=5432 PGUSER=postgres PGPASSWORD=... bash infra/smoke_test.sh
#
# Needs the three jars already built (./mvnw -DskipTests package in each service), psql, curl and a
# throwaway Postgres with a superuser: it creates two databases, and creates or re-passwords the
# auth_app and history_app roles. Ports 18080-18082. Exit status 0 when all three start and answer, 1
# otherwise, with the logs of whatever failed.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
: "${PGHOST:?set PGHOST}" "${PGPORT:?set PGPORT}" "${PGUSER:?set PGUSER}" "${PGPASSWORD:?set PGPASSWORD}"
db=smoke_$$
logs=$(mktemp -d)
pids=()

cleanup() {
  for pid in ${pids[@]+"${pids[@]}"}; do kill "$pid" 2>/dev/null || true; done
  wait 2>/dev/null || true
  psql -q -d postgres -c "DROP DATABASE IF EXISTS $db" -c "DROP DATABASE IF EXISTS ${db}_history" >/dev/null 2>&1 || true
  rm -rf "${secrets:-/nonexistent}"
}
trap cleanup EXIT

fail() {
  echo "smoke: $1" >&2
  for log in "$logs"/*.log; do echo "----- $(basename "$log")" >&2; tail -n 40 "$log" >&2; done
  exit 1
}

psql -q -v ON_ERROR_STOP=1 -d postgres -c "CREATE DATABASE $db" -c "CREATE DATABASE ${db}_history"

# The production arrangement: one role per service, owner of its own database only.
auth_password=$(openssl rand -hex 16)
history_password=$(openssl rand -hex 16)
psql -q -v ON_ERROR_STOP=1 -d postgres -v auth_db="$db" -v history_db="${db}_history" \
  -v auth_password="$auth_password" -v history_password="$history_password" \
  -f "$root/infra/postgres/app-roles.sql" >/dev/null

# Their secrets as files named after the property each carries, no password in any variable.
secrets=$(mktemp -d)
mkdir "$secrets/auth" "$secrets/history"
printf '%s' "$auth_password" > "$secrets/auth/spring.datasource.password"
openssl rand -base64 48 | tr -d '\n' > "$secrets/auth/jwt.secret"
printf '%s' "$history_password" > "$secrets/history/spring.datasource.password"

jdbc="jdbc:postgresql://$PGHOST:$PGPORT"
start() {
  local name=$1; shift
  env -i PATH="$PATH" HOME="${HOME:-/tmp}" SPRING_PROFILES_ACTIVE=prod JAVA_TOOL_OPTIONS=-Xmx384m "$@" \
    java -jar "$root"/service/"$name"/target/*.jar > "$logs/$name.log" 2>&1 &
  pids+=($!)
}

start auth-service AUTH_PORT=18081 JWT_EXPIRATION_MS=3600000 \
  SPRING_DATASOURCE_URL="$jdbc/$db" SPRING_DATASOURCE_USERNAME=auth_app \
  CENTURY_ROAD_SECRETS_DIR="$secrets/auth"
start history-service HISTORY_PORT=18082 HISTORY_TIMELINE_CRON=- \
  WIKIMEDIA_CONTACT=https://github.com/F3rren/century-road-backend \
  SPRING_DATASOURCE_URL="$jdbc/${db}_history" SPRING_DATASOURCE_USERNAME=history_app \
  CENTURY_ROAD_SECRETS_DIR="$secrets/history"
start gateway GATEWAY_PORT=18080 \
  AUTH_SERVICE_URI=http://localhost:18081 HISTORY_SERVICE_URI=http://localhost:18082 \
  FRONTEND_ORIGIN=https://app.example.test

for port in 18081 18082 18080; do
  for _ in $(seq 1 90); do
    curl -fsS "localhost:$port/actuator/health" 2>/dev/null | grep -q '"UP"' && continue 2
    for pid in "${pids[@]}"; do kill -0 "$pid" 2>/dev/null || fail "a service exited before it was up"; done
    sleep 2
  done
  fail "nothing healthy on :$port after three minutes"
done
echo "smoke: auth-service, history-service and gateway are up"

# Through the gateway, as a browser would: one route to each service, each reaching its database.
login=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
  -d '{"email":"nobody@example.test","password":"not-the-password"}' localhost:18080/api/auth/login)
[ "$login" = 401 ] || fail "POST /api/auth/login answered $login, expected 401"
paths=$(curl -s -o /dev/null -w '%{http_code}' localhost:18080/api/history/paths)
[ "$paths" = 200 ] || fail "GET /api/history/paths answered $paths, expected 200"
stats=$(curl -s -o /dev/null -w '%{http_code}' 'localhost:18080/api/history/stats/days?limit=3')
[ "$stats" = 200 ] || fail "GET /api/history/stats/days answered $stats, expected 200"
echo "smoke: login refused (401), paths and stats served (200) through the gateway"

# What the roles are for: each opens its own database and not the other's.
if PGPASSWORD="$history_password" psql -q -U history_app -d "$db" -c 'select 1' >/dev/null 2>&1; then
  fail "history_app could open auth-service's database"
fi
# And no service has a password in its environment.
for pid in "${pids[@]}"; do
  if tr '\0' '\n' < "/proc/$pid/environ" | grep -qiE '^[A-Z_]*(PASSWORD|SECRET)='; then
    fail "a service has a password or secret in its environment"
  fi
done
echo "smoke: history_app cannot open auth-service's database; no password in any service's environment"
