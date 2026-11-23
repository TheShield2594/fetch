#!/usr/bin/env bash
# Restores the Postgres database used by docker-compose.yml from a .sql.gz
# backup created by scripts/backup.sh.
#
# Usage: scripts/restore.sh <path-to-backup.sql.gz>
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <path-to-backup.sql.gz>" >&2
  exit 1
fi

BACKUP_FILE="$1"
if [ ! -f "$BACKUP_FILE" ]; then
  echo "Backup file not found: $BACKUP_FILE" >&2
  exit 1
fi

COMPOSE_PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$COMPOSE_PROJECT_DIR"

# shellcheck source=scripts/lib-env.sh
. "$COMPOSE_PROJECT_DIR/scripts/lib-env.sh"
load_postgres_settings "$COMPOSE_PROJECT_DIR"

if ! gunzip -t "$BACKUP_FILE" 2>/dev/null || \
  ! gunzip -c "$BACKUP_FILE" | tail -n 5 | grep -q "PostgreSQL database dump complete"; then
  echo "$BACKUP_FILE is not a complete backup from scripts/backup.sh; nothing was changed." >&2
  exit 1
fi

echo "This will OVERWRITE the current contents of database '$POSTGRES_DB'."
read -r -p "Type 'yes' to continue: " CONFIRM
if [ "$CONFIRM" != "yes" ]; then
  echo "Aborted."
  exit 1
fi

# Nothing may write to the database while it is replaced. Only services that are running are
# stopped, and the same ones are started again afterwards, whether the restore worked or not.
APP_SERVICES=(api addon mdns web)
RUNNING="$(docker compose ps --status running --services 2>/dev/null || true)"
STOPPED=()
for service in "${APP_SERVICES[@]}"; do
  if printf '%s\n' "$RUNNING" | grep -qx "$service"; then
    STOPPED+=("$service")
  fi
done
restart_services() {
  if [ "${#STOPPED[@]}" -gt 0 ]; then
    echo "Starting ${STOPPED[*]} again..."
    docker compose start "${STOPPED[@]}"
  fi
}
trap restart_services EXIT
if [ "${#STOPPED[@]}" -gt 0 ]; then
  echo "Stopping ${STOPPED[*]} for the restore..."
  docker compose stop "${STOPPED[@]}"
fi

# The dump has no DROP statements, so the current schema is dropped first; otherwise the first
# CREATE fails on an existing database, which is exactly the state after a failed migration.
# Everything runs in one transaction: if any statement fails, the database is left as it was.
echo "Restoring $BACKUP_FILE into '$POSTGRES_DB'..."
{
  echo "SET client_min_messages TO warning;"
  echo "DROP SCHEMA IF EXISTS public CASCADE;"
  echo "CREATE SCHEMA public;"
  gunzip -c "$BACKUP_FILE"
} | docker compose exec -T db psql --single-transaction -v ON_ERROR_STOP=1 -q \
  -U "$POSTGRES_USER" "$POSTGRES_DB" > /dev/null

echo "Restore complete."
