#!/usr/bin/env bash
# Runs the real Caddyfile in front of a stand-in for the services and checks what a browser gets:
# the security headers exactly once, with our values, whatever the service sent itself.
#
# The stand-in answers like auth-service does on a request Spring Security takes for secure: an
# HSTS with a one-year max-age, plus values that contradict ours. If any of them reached the
# browser next to (or instead of) ours, HSTS_MAX_AGE would not be what the browser applies.
#
# Needs Docker. Run it: bash infra/caddy/headers_test.sh
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CADDYFILE="$HERE/Caddyfile"
IMAGE="${CADDY_IMAGE:-caddy:2-alpine}"
LABEL="century-road-caddy-test=$$"
NET="century-road-caddy-test-$$"
WORK="$(mktemp -d)"

PASSED=0
FAILED=0

cleanup() {
  docker ps -aq --filter "label=$LABEL" | xargs -r docker rm -f >/dev/null 2>&1
  docker network rm "$NET" >/dev/null 2>&1
  rm -rf "$WORK"
}
trap cleanup EXIT

docker info >/dev/null 2>&1 || { echo "Docker is not running: this test needs it." >&2; exit 2; }

ok()   { PASSED=$((PASSED + 1)); echo "  ok    $1"; }
fail() { FAILED=$((FAILED + 1)); echo "  FAIL  $1"; [ -n "${2:-}" ] && printf '%s\n' "$2" | sed 's/^/        /'; }

# ---- what is in front of, and what runs ----------------------------------------------------

# A working tree checked out on Windows has CRLF line endings; the file in the repo, and on the
# VPS, does not. Test what runs there.
tr -d '\r' < "$CADDYFILE" > "$WORK/Caddyfile"

cat > "$WORK/upstream.Caddyfile" <<'EOF'
{
	auto_https off
}
:8080 {
	header {
		Strict-Transport-Security "max-age=31536000 ; includeSubDomains"
		X-Frame-Options "SAMEORIGIN"
		X-Content-Type-Options "not-what-the-proxy-says"
		Referrer-Policy "origin"
	}
	respond "upstream" 200
}
EOF

# Variants of the real file, each one a line or two short, to find out what the result depends on.
sed '/^[[:space:]]*-Server[[:space:]]*$/d' "$WORK/Caddyfile" > "$WORK/without-server.Caddyfile"
sed '/^[[:space:]]*defer[[:space:]]*$/d' "$WORK/without-server.Caddyfile" > "$WORK/without-server-or-defer.Caddyfile"

# The Caddyfile goes in through the environment: a bind mount does not survive the path
# rewriting on Windows, and this behaves the same on Linux.
start_caddy() {
  local name="$1" file="$2"; shift 2
  docker run -d --name "$name" --label "$LABEL" --network "$NET" \
    -e CADDYFILE="$(cat "$file")" "$@" \
    --entrypoint sh "$IMAGE" -c \
    'printf "%s\n" "$CADDYFILE" > /tmp/Caddyfile && exec caddy run --config /tmp/Caddyfile --adapter caddyfile' \
    >/dev/null
}

start_proxy() {
  local name="$1" file="$2"; shift 2
  start_caddy "$name" "$file" -e PUBLIC_DOMAIN=localhost -e GATEWAY_PORT=8080 \
    -p 127.0.0.1::443 -p 127.0.0.1::80 "$@"
}

host_port() { local p; p="$(docker port "$1" "$2/tcp" | head -n 1)"; echo "${p##*:}"; }

# ---- asking -------------------------------------------------------------------------------

# Status line and headers of a request, CRs removed. `localhost` has to be the name asked for,
# or Caddy has no certificate to offer, so the address is pinned instead.
fetch() { # scheme port path
  curl -sk --max-time 10 --resolve "localhost:$2:127.0.0.1" -D - -o /dev/null "$1://localhost:$2$3" | tr -d '\r'
}

wait_until_answering() { # port
  local _
  for _ in $(seq 1 40); do
    [ -n "$(fetch https "$1" /actuator/health)" ] && return 0
    sleep 0.5
  done
  return 1
}

count_of() { grep -ci "^$1:" <<<"$2" || true; }
value_of() { grep -i "^$1:" <<<"$2" | head -n 1 | sed 's/^[^:]*:[[:space:]]*//'; }

# What is wrong with the headers of a response, one line each; nothing when they are right. The
# Server header is expected gone unless the caller says it is not this case's business.
problems() { # headers max_age [ignore-server]
  local headers="$1" max_age="$2" spec name want n got
  local -a expected=(
    "Strict-Transport-Security|max-age=$max_age; includeSubDomains"
    "X-Content-Type-Options|nosniff"
    "X-Frame-Options|DENY"
    "Referrer-Policy|no-referrer"
  )
  for spec in "${expected[@]}"; do
    name="${spec%%|*}"; want="${spec#*|}"
    n="$(count_of "$name" "$headers")"
    got="$(value_of "$name" "$headers")"
    [ "$n" = "1" ] || echo "$name is sent $n times, expected once"
    [ "$got" = "$want" ] || echo "$name is '$got', expected '$want'"
  done
  [ "${3:-}" = "ignore-server" ] && return 0
  [ "$(count_of Server "$headers")" = "0" ] || echo "Server is sent: $(value_of Server "$headers")"
}

expect_clean() { # label port path max_age [ignore-server]
  local headers found
  headers="$(fetch https "$2" "$3")"
  found="$(problems "$headers" "$4" "${5:-}")"
  if [ -z "$found" ]; then ok "$1"; else fail "$1" "$found"; fi
}

expect_broken() { # label port path max_age
  local headers found
  headers="$(fetch https "$2" "$3")"
  found="$(problems "$headers" "$4")"
  if [ -n "$found" ]; then ok "$1"; else fail "$1" "nothing was wrong, so this check cannot tell a broken file from a good one"; fi
}

expect_different() { # label file file
  if cmp -s "$2" "$3"; then fail "$1" "the variant is identical to the file it is made from"; else ok "$1"; fi
}

# ---- bring it up ---------------------------------------------------------------------------

docker network create "$NET" >/dev/null || { echo "could not create a Docker network" >&2; exit 2; }
start_caddy upstream "$WORK/upstream.Caddyfile" --network-alias gateway
start_proxy proxy-default        "$WORK/Caddyfile"
start_proxy proxy-year           "$WORK/Caddyfile" -e HSTS_MAX_AGE=31536000
start_proxy proxy-without-server "$WORK/without-server.Caddyfile"
start_proxy proxy-broken         "$WORK/without-server-or-defer.Caddyfile"

PORT_default="$(host_port proxy-default 443)"
PORT_year="$(host_port proxy-year 443)"
PORT_without_server="$(host_port proxy-without-server 443)"
PORT_broken="$(host_port proxy-broken 443)"
HTTP_PORT="$(host_port proxy-default 80)"

for p in "$PORT_default" "$PORT_year" "$PORT_without_server" "$PORT_broken"; do
  wait_until_answering "$p" || { echo "a proxy did not come up on port $p" >&2; docker ps -a --filter "label=$LABEL"; exit 2; }
done

# ---- the cases ------------------------------------------------------------------------------

echo "the Caddyfile as it is, HSTS_MAX_AGE unset"
expect_clean "a proxied API path carries our headers once, and none of the service's" "$PORT_default" /api/auth/login 300
expect_clean "the public health check does too" "$PORT_default" /actuator/health 300

status="$(fetch https "$PORT_default" /actuator/prometheus | head -n 1)"
case "$status" in *" 404 "*) ok "the private actuator paths are a 404" ;; *) fail "the private actuator paths are a 404" "$status" ;; esac
expect_clean "and that 404 carries the headers as well" "$PORT_default" /actuator/prometheus 300

plain="$(fetch http "$HTTP_PORT" /api/auth/login)"
case "$(head -n 1 <<<"$plain")" in *" 308 "*) ok "plain HTTP is sent to HTTPS" ;; *) fail "plain HTTP is sent to HTTPS" "$(head -n 1 <<<"$plain")" ;; esac
if [ "$(count_of Strict-Transport-Security "$plain")" = "0" ]; then
  ok "and carries no HSTS, which a browser would ignore over HTTP anyway"
else
  fail "and carries no HSTS, which a browser would ignore over HTTP anyway" "$(value_of Strict-Transport-Security "$plain")"
fi

echo "HSTS_MAX_AGE set"
expect_clean "the value follows the variable" "$PORT_year" /api/auth/login 31536000

echo "what the override depends on"
expect_different "the variant without -Server is really a different file" "$WORK/Caddyfile" "$WORK/without-server.Caddyfile"
expect_clean "our headers still replace the service's without the -Server line"   "$PORT_without_server" /api/auth/login 300 ignore-server
expect_different "the variant without -Server and defer is really a different file" \
  "$WORK/without-server.Caddyfile" "$WORK/without-server-or-defer.Caddyfile"
expect_broken "the check notices when nothing makes them replace it" "$PORT_broken" /api/auth/login 300

# ------------------------------------------------------------------------------------------

echo
echo "$PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
