#!/usr/bin/env bash
# Shared by backup.sh and restore.sh. Reads POSTGRES_USER and POSTGRES_DB the same way
# docker compose does: the environment first, then the .env next to docker-compose.yml,
# then the compose defaults. The .env is parsed, not sourced, so nothing in it runs.

read_env_value() {
  local key="$1" file="$2" line value
  [ -f "$file" ] || return 1
  line="$(grep -E "^[[:space:]]*(export[[:space:]]+)?${key}=" "$file" | tail -n 1)" || return 1
  value="${line#*=}"
  value="$(printf '%s' "$value" | sed -e 's/^[[:space:]]*//')"
  case "$value" in
    # A quoted value ends at its closing quote; anything after it (a comment) is dropped.
    \"*) value="${value#\"}"; value="${value%%\"*}" ;;
    \'*) value="${value#\'}"; value="${value%%\'*}" ;;
    # An unquoted value ends at a comment or trailing whitespace.
    *) value="${value%%[[:space:]]#*}"; value="$(printf '%s' "$value" | sed -e 's/[[:space:]]*$//')" ;;
  esac
  printf '%s' "$value"
}

load_postgres_settings() {
  local env_file="$1/.env"
  if [ -z "${POSTGRES_USER:-}" ]; then
    POSTGRES_USER="$(read_env_value POSTGRES_USER "$env_file" || true)"
  fi
  if [ -z "${POSTGRES_DB:-}" ]; then
    POSTGRES_DB="$(read_env_value POSTGRES_DB "$env_file" || true)"
  fi
  POSTGRES_USER="${POSTGRES_USER:-postgres}"
  POSTGRES_DB="${POSTGRES_DB:-fetch}"
}
