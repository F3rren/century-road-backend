-- One login role per service, each the owner of its own database and of nothing else.
--
-- Without this, both services connect as POSTGRES_USER, which the Postgres image creates as superuser:
-- whoever got into history-service (the one that talks to the outside world) could read every user,
-- password hash and refresh token in auth-service's database, and run anything on the cluster. Two
-- databases are only an isolation once nobody but their owner can open them.
--
-- Safe to run again: roles are created once, their password and attributes are set every time, and
-- ownership is moved for whatever already exists. Two callers:
--   - infra/postgres/init-app-roles.sh, on the first start of an empty data directory (no tables yet);
--   - by hand, once, on an installation that already has data (README, "Moving to one database user
--     per service"), after a backup.
--
-- psql variables, all required:
--   auth_db           auth-service's database          (POSTGRES_DB)
--   history_db        history-service's database       (POSTGRES_DB + "_history")
--   auth_password     the password of auth_app
--   history_password  the password of history_app
-- Run as a superuser, connected to any database (the script connects to the two itself).

\set ON_ERROR_STOP on

-- 1. The roles: able to log in and nothing more.
SELECT format('CREATE ROLE %I', name)
FROM (VALUES ('auth_app'), ('history_app')) AS wanted(name)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = wanted.name)
\gexec

ALTER ROLE auth_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    PASSWORD :'auth_password';
ALTER ROLE history_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    PASSWORD :'history_password';

-- 2. Each database belongs to its role, and nobody else may connect to it. An owner keeps every
--    right on its database; PUBLIC (every other role) loses CONNECT. Superusers are not affected.
SELECT format('ALTER DATABASE %I OWNER TO auth_app', :'auth_db') \gexec
SELECT format('ALTER DATABASE %I OWNER TO history_app', :'history_db') \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'auth_db') \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'history_db') \gexec

-- 3. What Flyway already created, if anything: tables (their sequences follow them) and Flyway's own
--    history table. Objects that belong to an extension (PostGIS's spatial_ref_sys) stay as they are.
--    The public schema itself needs nothing: since Postgres 15 it is owned by pg_database_owner,
--    which is now the service's role.
\connect :"auth_db"
SELECT format('ALTER TABLE %I.%I OWNER TO auth_app', n.nspname, c.relname)
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
  AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
  AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass
                                            AND d.objid = c.oid AND d.deptype = 'e')
\gexec

\connect :"history_db"
SELECT 'ALTER SCHEMA history OWNER TO history_app'
WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'history')
\gexec
SELECT format('ALTER TABLE %I.%I OWNER TO history_app', n.nspname, c.relname)
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname IN ('history', 'public')
  AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
  AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass
                                            AND d.objid = c.oid AND d.deptype = 'e')
\gexec
