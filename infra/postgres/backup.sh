#!/usr/bin/env bash
# Dumps both Postgres databases (the main one and the "_history" one - see
# infra/postgres/init-history-db.sh) from the running compose-prod.yml stack and copies the
# result off this host with rclone. Meant for cron, not interactive use - see
# backup.cron.example for how to schedule it, and the README's "Backups" section for the
# restore procedure, which is deliberately a manual runbook rather than a second script.
#
# Assumes the current directory is the repository root: the crontab line does the `cd`, the
# same way every other host-invoked script here is documented rather than self-locating.
# Reads .env for the backup/rclone settings; rclone's own remote credentials live separately,
# at the usual ~/.config/rclone/rclone.conf for whichever user cron runs as - never in .env,
# never in this repo, same reasoning as every other secret here.
set -euo pipefail

COMPOSE_FILE="compose-prod.yml"

if [ ! -f .env ]; then
  echo "backup.sh: no .env in $(pwd) - run this from the repository root." >&2
  exit 1
fi
set -a
# shellcheck disable=SC1091
source .env
set +a

# ALERT_WEBHOOK_URL is now available (if set), so the failure trap can go live before anything
# below it that could fail - including the var guards themselves - rather than after them.
notify_failure() {
  [ -n "${ALERT_WEBHOOK_URL:-}" ] || return 0
  curl -fsS -X POST -H 'Content-Type: application/json' \
    -d '{"content":"century-road backup.sh failed - see the cron log on the host"}' \
    "$ALERT_WEBHOOK_URL" >/dev/null 2>&1 || true
}
trap notify_failure ERR

: "${BACKUP_DIR:?set BACKUP_DIR in .env}"
: "${BACKUP_RETENTION_DAYS:?set BACKUP_RETENTION_DAYS in .env}"
: "${BACKUP_REMOTE_RETENTION_DAYS:?set BACKUP_REMOTE_RETENTION_DAYS in .env}"
: "${RCLONE_REMOTE:?set RCLONE_REMOTE in .env}"

# Dumps hold bcrypt password hashes and refresh-token hashes (see docs/architecture.md's Data
# model): keep them unreadable to any other local account, not just out of the repo/off .env.
umask 077
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
MAIN_DUMP="$BACKUP_DIR/centuryroad_${TIMESTAMP}.sql.gz"
HISTORY_DUMP="$BACKUP_DIR/centuryroad_history_${TIMESTAMP}.sql.gz"

# Both databases are dumped inside the "db" container, which resolves POSTGRES_USER/POSTGRES_DB
# from its own environment - the same idiom infra/postgres/init-history-db.sh uses for the
# second database's name, so this script never has to know either literally.
docker compose -f "$COMPOSE_FILE" exec -T db sh -c \
  'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB"' | gzip > "$MAIN_DUMP"
docker compose -f "$COMPOSE_FILE" exec -T db sh -c \
  'pg_dump -U "$POSTGRES_USER" -d "${POSTGRES_DB}_history"' | gzip > "$HISTORY_DUMP"

rclone copy "$MAIN_DUMP" "$RCLONE_REMOTE"
rclone copy "$HISTORY_DUMP" "$RCLONE_REMOTE"

# Local retention is short: this host's disk is not the durable copy. Remote retention is
# longer, on purpose - it is what survives a problem with this host itself, not just a bad dump.
find "$BACKUP_DIR" -name '*.sql.gz' -mtime "+${BACKUP_RETENTION_DAYS}" -delete
rclone delete "$RCLONE_REMOTE" --min-age "${BACKUP_REMOTE_RETENTION_DAYS}d"

echo "backup.sh: done - $MAIN_DUMP and $HISTORY_DUMP copied to $RCLONE_REMOTE"
