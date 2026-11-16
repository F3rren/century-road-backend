# shellcheck shell=bash
# Reads one KEY=VALUE from a Compose-style env file (.env) without executing it.
#
# Sourced by infra/check-env.sh and infra/postgres/backup.sh. `source .env` would run the file as
# shell, where `$`, spaces, quotes and backticks mean something else than they mean to Compose: the
# same line could give the containers one password and the script another, and any line would run.
#
# Rules, the ones Compose applies to the values this project uses: the last assignment of a key wins;
# an optional leading `export ` is ignored; a value in matching single or double quotes is taken as
# written, without the quotes; an unquoted value ends at ` #` (a trailing comment) and loses
# trailing spaces; a Windows line ending is dropped.

# dotenv_get FILE KEY - prints the value; returns 1 when KEY is not assigned in FILE.
dotenv_get() {
  local file=$1 key=$2 line value
  line=$(grep -E "^[[:space:]]*(export[[:space:]]+)?${key}=" "$file" | tail -n 1) || return 1
  [ -n "$line" ] || return 1
  value=${line#*=}
  value=${value%$'\r'}
  case $value in
    \"*\")
      value=${value#\"}
      value=${value%\"}
      ;;
    \'*\')
      value=${value#\'}
      value=${value%\'}
      ;;
    *)
      value=${value%%[[:space:]]#*}
      value=${value%"${value##*[![:space:]]}"}
      ;;
  esac
  printf '%s' "$value"
}
