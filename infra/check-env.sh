#!/usr/bin/env bash
# Checks the production settings and secrets before `docker compose -f compose-prod.yml up -d`, and
# says everything that is wrong at once.
#
#     infra/check-env.sh                 # checks ./.env and ./secrets/
#     infra/check-env.sh path/.env       # another .env; its secrets are path/secrets/ unless SECRETS_DIR says otherwise
#     infra/check-env.sh --list-secrets  # the secret files compose-prod.yml expects, one per line
#
# Compose's ${VAR:?} only catches a setting that is missing or empty, and a missing secret file only at
# `up`: a template placeholder such as `change-me` is a value like any other, and Postgres or Grafana
# would start with it as their password. This catches those, a JWT secret the service would refuse at
# startup, secret files the containers could not read or other host users could, and settings that
# would make the stack start and then misbehave (a FRONTEND_ORIGIN with a trailing slash, two services
# on one port).
#
# It reads .env with infra/lib/dotenv.sh, never runs it, and changes nothing. Exit status: 0 when
# everything looks ready, 1 when there are problems, 2 when the .env itself is missing.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib/dotenv.sh
source "$here/lib/dotenv.sh"

# The files of compose-prod.yml's top-level `secrets:`, and the .env line each one replaced.
# infra/check-env_test.sh fails if this list and compose-prod.yml disagree.
secret_files=(postgres_password auth_db_password history_db_password jwt_secret grafana_password)
replaced_variable() {
  case $1 in
    postgres_password) echo POSTGRES_PASSWORD ;;
    auth_db_password) echo AUTH_DB_PASSWORD ;;
    history_db_password) echo HISTORY_DB_PASSWORD ;;
    jwt_secret) echo JWT_SECRET ;;
    grafana_password) echo GRAFANA_PASSWORD ;;
  esac
}

if [ "${1:-}" = "--list-secrets" ]; then
  printf '%s\n' "${secret_files[@]}"
  exit 0
fi

file=${1:-.env}
if [ ! -f "$file" ]; then
  echo "check-env: $file not found - copy .env.example to .env and fill it in." >&2
  exit 2
fi
secrets_dir=${SECRETS_DIR:-$(dirname "$file")/secrets}

problems=()
warnings=()
problem() { problems+=("$1"); }
warning() { warnings+=("$1"); }

# value KEY - the value, or empty when the key is not assigned.
value() { dotenv_get "$file" "$1" || true; }

# ---- .env: every setting compose-prod.yml or backup.sh needs, with no usable default -------------

required=(
  POSTGRES_DB POSTGRES_USER
  JWT_EXPIRATION_MS
  GATEWAY_PORT AUTH_PORT HISTORY_PORT
  PUBLIC_DOMAIN FRONTEND_ORIGIN WIKIMEDIA_CONTACT
  GRAFANA_USER ALERT_WEBHOOK_URL
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

# A secret left in .env is not read any more, and is one more copy of it on the disk.
for name in "${secret_files[@]}"; do
  variable=$(replaced_variable "$name")
  if [ -n "$(value "$variable")" ]; then
    problem "$variable is now the file secrets/$name: move the value there and delete the line from $file"
  fi
done

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

# ---- secrets/: one readable file per secret, the directory closed to other host users -----------

readable_secrets=()
if [ ! -d "$secrets_dir" ]; then
  problem "$secrets_dir does not exist: create the secret files as secrets/README.md shows"
else
  mode=$(stat -c '%a' "$secrets_dir")
  if [ "${mode: -2}" != "00" ]; then
    problem "$secrets_dir is $mode: make it 700, so that no other account on the host can reach the files"
  fi
  for name in "${secret_files[@]}"; do
    path="$secrets_dir/$name"
    if [ ! -f "$path" ]; then
      problem "secrets/$name is missing"
      continue
    fi
    if [ ! -s "$path" ]; then
      problem "secrets/$name is empty"
      continue
    fi
    fmode=$(stat -c '%a' "$path")
    if (( (8#$fmode & 8#004) == 0 )); then
      problem "secrets/$name is $fmode: make it 644 - the containers read it as their own users (the 700 directory keeps the host's other accounts out)"
    fi
    if [ "$(tail -c 1 "$path" | od -An -c | tr -d ' ')" = '\n' ]; then
      problem "secrets/$name ends with a line break: write it with printf '%s' or tr -d '\\n' (secrets/README.md)"
    fi
    if grep -q 'change-me' "$path"; then
      problem "secrets/$name still holds a placeholder"
    fi
    readable_secrets+=("$name")
  done
fi

secret_ok() { [[ " ${readable_secrets[*]-} " == *" $1 "* ]]; }

# The same rule JwtKey applies at startup: base64 (either alphabet), at least 32 bytes once decoded.
if secret_ok jwt_secret; then
  secret=$(tr -- '-_' '+/' < "$secrets_dir/jwt_secret" | tr -d '\n')
  while [ $(( ${#secret} % 4 )) -ne 0 ]; do secret="$secret="; done
  if ! bytes=$(printf '%s' "$secret" | base64 -d 2>/dev/null | wc -c); then
    problem "secrets/jwt_secret is not valid base64 (openssl rand -base64 48 | tr -d '\\n')"
  elif [ "$bytes" -lt 32 ]; then
    problem "secrets/jwt_secret decodes to $bytes bytes; at least 32 are needed (openssl rand -base64 48)"
  fi
fi

# One role per service is only an isolation if knowing one password does not give another.
if secret_ok postgres_password && secret_ok auth_db_password && secret_ok history_db_password; then
  distinct=$(sha256sum "$secrets_dir/postgres_password" "$secrets_dir/auth_db_password" \
    "$secrets_dir/history_db_password" | cut -d' ' -f1 | sort -u | wc -l)
  if [ "$distinct" -ne 3 ]; then
    problem "secrets/postgres_password, auth_db_password and history_db_password must be three different passwords"
  fi
fi

# ---- the verdict -------------------------------------------------------------------------------

for w in ${warnings[@]+"${warnings[@]}"}; do echo "check-env: warning: $w"; done
if [ ${#problems[@]} -gt 0 ]; then
  echo "check-env: $file and $secrets_dir are not ready - ${#problems[@]} problem(s):" >&2
  for p in "${problems[@]}"; do echo "  - $p" >&2; done
  exit 1
fi
echo "check-env: $file and $secrets_dir look ready (${#required[@]} settings, ${#secret_files[@]} secrets checked)"
