#!/usr/bin/env bash
# Dumps the Postgres database used by docker-compose.yml to a timestamped
# .sql.gz file. Run from the repository root (or pass BACKUP_DIR explicitly).
set -euo pipefail

# A dump holds every user's data, including stored OAuth access/refresh tokens,
# so nothing it writes should be readable by other users on the host.
umask 077

COMPOSE_PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$COMPOSE_PROJECT_DIR"

# shellcheck source=scripts/lib-env.sh
. "$COMPOSE_PROJECT_DIR/scripts/lib-env.sh"
load_postgres_settings "$COMPOSE_PROJECT_DIR"
BACKUP_DIR="${BACKUP_DIR:-$COMPOSE_PROJECT_DIR/backups}"

mkdir -p "$BACKUP_DIR"
# umask only covers a directory this run creates; tighten a pre-existing one too.
chmod 700 "$BACKUP_DIR"

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
OUT_FILE="$BACKUP_DIR/fetch-$TIMESTAMP.sql.gz"

# A failed or interrupted dump must not leave a small file behind that looks like a backup.
cleanup_partial() {
  rm -f "$OUT_FILE"
  echo "Backup FAILED; nothing was written." >&2
}
trap cleanup_partial ERR INT TERM

echo "Backing up database '$POSTGRES_DB' to $OUT_FILE"
docker compose exec -T db pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" | gzip > "$OUT_FILE"
chmod 600 "$OUT_FILE"

# pg_dump ends every complete dump with this line; without it the dump was cut short.
if ! gunzip -c "$OUT_FILE" | tail -n 5 | grep -q "PostgreSQL database dump complete"; then
  echo "The dump is incomplete." >&2
  false
fi
trap - ERR INT TERM

echo "Backup complete: $OUT_FILE"
