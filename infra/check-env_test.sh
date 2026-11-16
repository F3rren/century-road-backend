#!/usr/bin/env bash
# Tests infra/check-env.sh and infra/lib/dotenv.sh: what the preflight refuses, what it lets through,
# that its list of secret files is compose-prod.yml's, and that values are read the way Compose reads
# them. Plain bash, no network, a few seconds.
#
#     bash infra/check-env_test.sh
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(cd "$here/.." && pwd)
check="$here/check-env.sh"
# shellcheck source=lib/dotenv.sh
source "$here/lib/dotenv.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failures=0

fail() { echo "FAIL: $1"; failures=$((failures + 1)); }
pass() { echo "ok:   $1"; }

# expect NAME STATUS PATTERN FILE [SECRETS_DIR] - check-env exits with STATUS and its output matches PATTERN.
expect() {
  local name=$1 status=$2 pattern=$3 file=$4 secrets=${5:-$work/secrets} out rc=0
  out=$(SECRETS_DIR="$secrets" bash "$check" "$file" 2>&1) || rc=$?
  if [ "$rc" -ne "$status" ]; then
    fail "$name: exit $rc, expected $status"$'\n'"$out"
  elif ! grep -Eq -- "$pattern" <<<"$out"; then
    fail "$name: output does not match /$pattern/"$'\n'"$out"
  else
    pass "$name"
  fi
}

# A .env that passes: the template with its placeholders replaced by plausible values.
valid="$work/valid.env"
sed -e "s|^ALERT_WEBHOOK_URL=.*|ALERT_WEBHOOK_URL=https://hooks.example.test/century-road|" \
    -e "s|^RCLONE_REMOTE=.*|RCLONE_REMOTE=offsite:century-road|" \
    "$root/.env.example" > "$valid"

# secrets DIR - a valid secrets directory, created the way secrets/README.md says.
secrets() {
  local dir=$1 name
  mkdir -p "$dir" && chmod 700 "$dir"
  for name in postgres_password auth_db_password history_db_password grafana_password; do
    openssl rand -base64 32 | tr -d '\n' > "$dir/$name"
  done
  openssl rand -base64 48 | tr -d '\n' > "$dir/jwt_secret"
  chmod 644 "$dir"/*
  echo "$dir"
}
secrets "$work/secrets" >/dev/null

# variant NAME SED-EXPRESSION - a copy of the valid .env with one change.
variant() { sed -e "$2" "$valid" > "$work/$1.env"; echo "$work/$1.env"; }
# broken NAME COMMAND - a fresh valid secrets directory, then COMMAND run inside it.
broken() { local dir; dir=$(secrets "$work/$1"); (cd "$dir" && eval "$2"); echo "$dir"; }

# ---- the template and a filled-in installation ----

expect "the template is refused, its placeholders named" 1 "ALERT_WEBHOOK_URL.*placeholder" "$root/.env.example"
expect "the template's RCLONE_REMOTE is named" 1 "RCLONE_REMOTE" "$root/.env.example"
expect "a filled-in installation passes" 0 "look ready" "$valid"
expect "a missing .env is its own status" 2 "not found" "$work/nope.env"

# ---- .env ----

expect "a missing setting is refused" 1 "WIKIMEDIA_CONTACT is missing" \
  "$(variant no-contact '/^WIKIMEDIA_CONTACT=/d')"
expect "an empty setting is refused" 1 "PUBLIC_DOMAIN is empty" \
  "$(variant empty-domain 's|^PUBLIC_DOMAIN=.*|PUBLIC_DOMAIN=|')"
expect "a secret left in .env is refused" 1 "JWT_SECRET is now the file secrets/jwt_secret" \
  "$(variant leftover "\$a JWT_SECRET=$(openssl rand -base64 48)")"
expect "a trailing slash on the origin is refused" 1 "FRONTEND_ORIGIN" \
  "$(variant slash 's|^FRONTEND_ORIGIN=.*|FRONTEND_ORIGIN=https://app.example.test/|')"
expect "two services on one port are refused" 1 "three different ports" \
  "$(variant ports 's|^AUTH_PORT=.*|AUTH_PORT=12129|')"
expect "a port that is not a number is refused" 1 "HISTORY_PORT is not a port" \
  "$(variant nan 's|^HISTORY_PORT=.*|HISTORY_PORT=history|')"
expect "a scheme in PUBLIC_DOMAIN is refused" 1 "PUBLIC_DOMAIN" \
  "$(variant domain 's|^PUBLIC_DOMAIN=.*|PUBLIC_DOMAIN=https://api.example.test|')"
expect "half the first-admin pair is refused" 1 "set together" \
  "$(variant half-admin 's|^BOOTSTRAP_ADMIN_EMAIL=.*|BOOTSTRAP_ADMIN_EMAIL=admin@example.test|')"
expect "the whole pair passes with a reminder" 0 "warning: BOOTSTRAP_ADMIN" \
  "$(variant admin 's|^BOOTSTRAP_ADMIN_EMAIL=.*|BOOTSTRAP_ADMIN_EMAIL=admin@example.test|; s|^BOOTSTRAP_ADMIN_PASSWORD=.*|BOOTSTRAP_ADMIN_PASSWORD=long-enough-password-1|')"
expect "a quoted value with a dollar sign passes" 0 "look ready" \
  "$(variant dollar "s|^WIKIMEDIA_CONTACT=.*|WIKIMEDIA_CONTACT='ops \$team <ops@example.test>'|")"

# ---- secrets/ ----

expect "a missing secrets directory is refused" 1 "does not exist" "$valid" "$work/no-secrets"
expect "a directory other users can enter is refused" 1 "is 755: make it 700" "$valid" \
  "$(broken open-dir 'chmod 755 .')"
expect "a missing secret file is refused" 1 "secrets/jwt_secret is missing" "$valid" \
  "$(broken no-jwt 'rm jwt_secret')"
expect "an empty secret file is refused" 1 "secrets/grafana_password is empty" "$valid" \
  "$(broken empty ': > grafana_password')"
expect "a file the containers cannot read is refused" 1 "auth_db_password is 600: make it 644" "$valid" \
  "$(broken private 'chmod 600 auth_db_password')"
expect "a line break at the end is refused" 1 "postgres_password ends with a line break" "$valid" \
  "$(broken newline 'echo >> postgres_password')"
expect "a placeholder in a file is refused" 1 "history_db_password still holds a placeholder" "$valid" \
  "$(broken placeholder "printf change-me > history_db_password")"
expect "a short JWT secret is refused" 1 "decodes to 16 bytes" "$valid" \
  "$(broken short-jwt "openssl rand -base64 16 | tr -d '\n' > jwt_secret")"
expect "a JWT secret that is not base64 is refused" 1 "not valid base64" "$valid" \
  "$(broken bad-jwt "printf 'not*base64*at*all*!!' > jwt_secret")"
expect "a url-safe JWT secret without padding passes" 0 "look ready" "$valid" \
  "$(broken urlsafe "openssl rand -base64 40 | tr '+/' '-_' | tr -d '=\n' > jwt_secret")"
expect "a role sharing the superuser's password is refused" 1 "three different passwords" "$valid" \
  "$(broken shared 'cp postgres_password auth_db_password')"

# ---- the list of secrets is compose-prod.yml's ----

listed=$(bash "$check" --list-secrets | sort)
declared=$(grep -oE '\./secrets/[a-z_]+' "$root/compose-prod.yml" | sed 's|\./secrets/||' | sort -u)
if [ "$listed" = "$declared" ]; then
  pass "check-env.sh checks exactly the secret files compose-prod.yml declares"
else
  fail "check-env.sh lists [$(echo $listed)], compose-prod.yml declares [$(echo $declared)]"
fi

# ---- the reader itself, on the forms Compose accepts ----

env="$work/forms.env"
cat > "$env" <<'EOF'
PLAIN=plain
DOUBLE="with $dollar and spaces"
SINGLE='single $quoted'
COMMENT=value # trailing comment
export EXPORTED=exported
EMPTY=
TWICE=first
TWICE=last
EOF
printf 'WINDOWS=crlf\r\n' >> "$env"
read_is() {
  local got
  got=$(dotenv_get "$env" "$1") || got="<absent>"
  if [ "$got" = "$2" ]; then pass "dotenv_get $1"; else fail "dotenv_get $1: [$got], expected [$2]"; fi
}
read_is PLAIN 'plain'
read_is DOUBLE 'with $dollar and spaces'
read_is SINGLE 'single $quoted'
read_is COMMENT 'value'
read_is EXPORTED 'exported'
read_is EMPTY ''
read_is TWICE 'last'
read_is WINDOWS 'crlf'
read_is NOT_THERE '<absent>'

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all checks passed"
