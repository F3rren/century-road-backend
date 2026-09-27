#!/bin/sh
# Runs once, on first init of an empty data directory, alongside init-postgis.sql.
#
# A second logical database, not just a second schema: history-service gets real
# SQL-level isolation from auth-service (Postgres refuses cross-database queries
# outright, unlike cross-schema ones under the same connection) while both still
# share this one Postgres container/resource - no second instance to run, back up
# or pay for. See the SPRING_DATASOURCE_URL comment on history-service in
# compose-dev.yml/compose-prod.yml.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE "${POSTGRES_DB}_history";
EOSQL
