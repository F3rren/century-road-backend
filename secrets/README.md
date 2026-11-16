# Production secrets

One file per secret, read by `compose-prod.yml` as [Compose secrets](https://docs.docker.com/compose/how-tos/use-secrets/)
and mounted into `/run/secrets` of the containers that need it, and of no other. Everything in this
directory except this file is gitignored. Development does not use it: `compose-dev.yml` reads
`.env.dev`.

| File | Who reads it | What it is |
|---|---|---|
| `postgres_password` | `db` | the superuser's password: Postgres itself, its init and the backups |
| `auth_db_password` | `db` (init), `auth-service` | the `auth_app` role's password |
| `history_db_password` | `db` (init), `history-service` | the `history_app` role's password |
| `jwt_secret` | `auth-service` | signs the access tokens; base64, at least 32 bytes once decoded |
| `grafana_password` | `grafana` | the Grafana admin login |

## Creating them

From the repository root, on the server:

```bash
mkdir -p secrets && chmod 700 secrets
for name in postgres_password auth_db_password history_db_password grafana_password; do
  openssl rand -base64 32 | tr -d '\n' > "secrets/$name"
done
openssl rand -base64 48 | tr -d '\n' > secrets/jwt_secret
chmod 644 secrets/*
infra/check-env.sh
```

- **The directory is `700`, the files `644`.** Inside the containers, Postgres, Grafana and the services
  run as their own users, which a host file of mode `600` would lock out. The directory is what keeps
  every other account on the host away from the files.
- **No line break at the end.** `tr -d '\n'` (or `printf '%s'`) writes the value alone: an editor that
  adds a final newline makes a password that is not quite the one you meant. `infra/check-env.sh`
  refuses it.
- **Three different database passwords**, or one role's password opens another's database.

## An installation that already runs

The superuser's and Grafana's passwords are only read the first time their data directory is created:
changing the file afterwards changes nothing in Postgres or Grafana. So when moving an existing `.env`
to these files, **copy the current values**:

```bash
printf '%s' '<POSTGRES_PASSWORD from .env>' > secrets/postgres_password
printf '%s' '<JWT_SECRET from .env>'        > secrets/jwt_secret    # a new one logs everybody out
printf '%s' '<GRAFANA_PASSWORD from .env>'  > secrets/grafana_password
```

then create the two role passwords as above, remove those five lines from `.env`, and follow
README, "One database user per service".
