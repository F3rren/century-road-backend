#!/bin/sh
# Runs once, on first init of an empty data directory, after init-history-db.sh: creates the two
# service roles and gives each its own database (infra/postgres/app-roles.sql says why and how).
#
# The passwords come from AUTH_DB_PASSWORD and HISTORY_DB_PASSWORD, or - the way compose-prod.yml
# passes them - from the files AUTH_DB_PASSWORD_FILE and HISTORY_DB_PASSWORD_FILE point at, the same
# convention the Postgres image uses for POSTGRES_PASSWORD_FILE.
set -e

# secret NAME - the value of NAME, or the contents of the file NAME_FILE names. Fails when neither is set.
secret() {
  eval "value=\${$1:-}"
  eval "file=\${$1_FILE:-}"
  if [ -n "$file" ]; then
    value=$(cat "$file")
  fi
  if [ -z "$value" ]; then
    echo "init-app-roles: set $1 (or $1_FILE)" >&2
    exit 1
  fi
  printf '%s' "$value"
}

auth_password=$(secret AUTH_DB_PASSWORD)
history_password=$(secret HISTORY_DB_PASSWORD)

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v auth_db="$POSTGRES_DB" \
  -v history_db="${POSTGRES_DB}_history" \
  -v auth_password="$auth_password" \
  -v history_password="$history_password" \
  -f /usr/local/share/century-road/app-roles.sql
