#!/usr/bin/env bash
# Checks the production settings before `docker compose -f compose-prod.yml up -d`, and says
# everything that is wrong at once.
#
#     infra/check-env.sh            # checks ./.env
#     infra/check-env.sh path/.env  # checks another file
#
# Compose's ${VAR:?} only catches a value that is missing or empty: a template placeholder such as
# `change-me` is a value like any other, and Postgres or Grafana would start with it as their
# password. This catches those, a JWT_SECRET the service would refuse at startup, and settings that
# would make the stack start and then misbehave (a FRONTEND_ORIGIN with a trailing slash, two
# services on one port).
#
# It reads the file with infra/lib/dotenv.sh, never runs it, and changes nothing. Exit status: 0 when
# the settings look ready, 1 when there are problems, 2 when the file itself is missing.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib/dotenv.sh
source "$here/lib/dotenv.sh"

file=${1:-.env}
if [ ! -f "$file" ]; then
  echo "check-env: $file not found - copy .env.example to .env and fill it in." >&2
  exit 2
fi

problems=()
warnings=()
problem() { problems+=("$1"); }
warning() { warnings+=("$1"); }

# value KEY - the value, or empty when the key is not assigned.
value() { dotenv_get "$file" "$1" || true; }

# Every setting compose-prod.yml or backup.sh needs, with no usable default.
required=(
  POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD AUTH_DB_PASSWORD HISTORY_DB_PASSWORD
  JWT_SECRET JWT_EXPIRATION_MS
  GATEWAY_PORT AUTH_PORT HISTORY_PORT
  PUBLIC_DOMAIN FRONTEND_ORIGIN WIKIMEDIA_CONTACT
  GRAFANA_USER GRAFANA_PASSWORD ALERT_WEBHOOK_URL
  BACKUP_DIR BACKUP_RETENTION_DAYS BACKUP_REMOTE_RETENTION_DAYS RCLONE_REMOTE
)

usable=()
for key in "${required[@]}"; do
  if ! dotenv_get "$file" "$key" >/dev/null; then
    problem "$key is missing"
  elif [ -z "$(value "$key")" ]; then
    problem "$key is empty"
  elif [[ $(value "$key") == *change-me* ]]; then
    problem "$key still holds the template's placeholder"
  else
    usable+=("$key")
  fi
done

has() { [[ " ${usable[*]-} " == *" $1 "* ]]; }

# The same rule JwtKey applies at startup: base64 (either alphabet), at least 32 bytes once decoded.
if has JWT_SECRET; then
  secret=$(value JWT_SECRET | tr -- '-_' '+/')
  while [ $(( ${#secret} % 4 )) -ne 0 ]; do secret="$secret="; done
  if ! bytes=$(printf '%s' "$secret" | base64 -d 2>/dev/null | wc -c); then
    problem "JWT_SECRET is not valid base64 (generate one with: openssl rand -base64 48)"
  elif [ "$bytes" -lt 32 ]; then
    problem "JWT_SECRET decodes to $bytes bytes; at least 32 are needed (openssl rand -base64 48)"
  fi
fi

# One role per service is only an isolation if knowing one password does not give another.
if has POSTGRES_PASSWORD && has AUTH_DB_PASSWORD && has HISTORY_DB_PASSWORD; then
  pw=$(printf '%s\n' "$(value POSTGRES_PASSWORD)" "$(value AUTH_DB_PASSWORD)" "$(value HISTORY_DB_PASSWORD)")
  if [ "$(sort -u <<<"$pw" | wc -l)" -ne 3 ]; then
    problem "POSTGRES_PASSWORD, AUTH_DB_PASSWORD and HISTORY_DB_PASSWORD must be three different passwords"
  fi
fi

is_port() { [[ $1 =~ ^[0-9]+$ ]] && [ "$1" -ge 1 ] && [ "$1" -le 65535 ]; }
for key in GATEWAY_PORT AUTH_PORT HISTORY_PORT; do
  if has "$key" && ! is_port "$(value "$key")"; then
    problem "$key is not a port number: $(value "$key")"
  fi
done
for key in PROMETHEUS_PORT GRAFANA_PORT PROXY_HTTP_PORT PROXY_HTTPS_PORT; do
  v=$(value "$key")
  if [ -n "$v" ] && ! is_port "$v"; then
    problem "$key is not a port number: $v"
  fi
done
if has GATEWAY_PORT && has AUTH_PORT && has HISTORY_PORT; then
  ports=$(printf '%s\n' "$(value GATEWAY_PORT)" "$(value AUTH_PORT)" "$(value HISTORY_PORT)")
  if [ "$(sort -u <<<"$ports" | wc -l)" -ne 3 ]; then
    problem "GATEWAY_PORT, AUTH_PORT and HISTORY_PORT must be three different ports"
  fi
fi

for key in JWT_EXPIRATION_MS BACKUP_RETENTION_DAYS BACKUP_REMOTE_RETENTION_DAYS; do
  if has "$key" && ! [[ $(value "$key") =~ ^[1-9][0-9]*$ ]]; then
    problem "$key must be a positive whole number: $(value "$key")"
  fi
done

# A browser compares origins exactly: scheme and host (and port), nothing after them.
if has FRONTEND_ORIGIN && ! [[ $(value FRONTEND_ORIGIN) =~ ^https?://[^/[:space:]]+$ ]]; then
  problem "FRONTEND_ORIGIN must be scheme://host[:port] with no path or trailing slash: $(value FRONTEND_ORIGIN)"
fi
if has ALERT_WEBHOOK_URL && ! [[ $(value ALERT_WEBHOOK_URL) =~ ^https:// ]]; then
  problem "ALERT_WEBHOOK_URL must be an https:// URL"
fi
if has PUBLIC_DOMAIN && [[ $(value PUBLIC_DOMAIN) =~ (://|/) ]]; then
  problem "PUBLIC_DOMAIN is a host name only, without scheme or path: $(value PUBLIC_DOMAIN)"
fi

# The first-administrator pair works only as a pair, and should not stay set for good.
admin_email=$(value BOOTSTRAP_ADMIN_EMAIL)
admin_password=$(value BOOTSTRAP_ADMIN_PASSWORD)
if { [ -n "$admin_email" ] && [ -z "$admin_password" ]; } || { [ -z "$admin_email" ] && [ -n "$admin_password" ]; }; then
  problem "BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD are set together or not at all"
elif [ -n "$admin_email" ]; then
  warning "BOOTSTRAP_ADMIN_* are set: clear them once the first administrator exists"
fi

for w in ${warnings[@]+"${warnings[@]}"}; do echo "check-env: warning: $w"; done
if [ ${#problems[@]} -gt 0 ]; then
  echo "check-env: $file is not ready - ${#problems[@]} problem(s):" >&2
  for p in "${problems[@]}"; do echo "  - $p" >&2; done
  exit 1
fi
echo "check-env: $file looks ready (${#required[@]} required settings checked)"
