#!/bin/sh
# PostgreSQL backups for stocks (ARCHITECTURE §24.2, docs/runbook.md).
#
# Runs inside the `backup` service (postgres:18.6-alpine, busybox sh). The deploy job copies this file to
# /DATA/AppData/stocks/scripts/, which docker-compose.yml mounts read-only at /opt/stocks.
#
#   pg_backup.sh [LABEL]    one dump now: /backups/stocks-YYYY-MM-DD[-LABEL].dump (pg_dump -Fc), then prune
#   pg_backup.sh --daemon   dump at start if today's dump is missing, then every day at BACKUP_TIME_UTC
#
# By hand:  docker compose -p stocks exec -T backup /bin/sh /opt/stocks/pg_backup.sh manual
# Restore:  deploy/scripts/restore.sh <dump file>   (see docs/runbook.md)
#
# Connection comes from the libpq environment set by compose: PGHOST=db, PGUSER, PGDATABASE, PGPASSWORD.
# KEEP_DAYS (default 14) prunes old dumps. BACKUP_TIME_UTC (default 05:00) is the daily run time.
set -eu

BACKUP_DIR="${BACKUP_DIR:-/backups}"
KEEP_DAYS="${KEEP_DAYS:-14}"
BACKUP_TIME_UTC="${BACKUP_TIME_UTC:-05:00}"

log() { echo "pg_backup: $*"; }

backup_once() {
  label="${1:-}"
  case "$label" in
    *[!A-Za-z0-9._-]*) log "invalid label '$label' (allowed: A-Z a-z 0-9 . _ -)" >&2; return 2 ;;
  esac
  case "$KEEP_DAYS" in
    '' | *[!0-9]*) log "KEEP_DAYS must be a whole number of days, got '$KEEP_DAYS'" >&2; return 2 ;;
  esac
  mkdir -p "$BACKUP_DIR"
  f="$BACKUP_DIR/stocks-$(date -u +%F)${label:+-$label}.dump"
  if pg_dump -Fc -f "$f.tmp"; then
    mv -f "$f.tmp" "$f"
  else
    rm -f "$f.tmp"
    log "FAILED to dump ${PGDATABASE:-?} from ${PGHOST:-?}" >&2
    return 1
  fi
  date -u +%Y-%m-%dT%H:%M:%SZ > "$BACKUP_DIR/.last-success"
  find "$BACKUP_DIR" -maxdepth 1 -name 'stocks-*.dump' -mtime +"$KEEP_DAYS" -exec rm -f {} +
  log "wrote $f ($(du -h "$f" | cut -f1))"
}

# Seconds from now until the next HH:MM (UTC); a full day if that is right now.
seconds_until() {
  case "$1" in
    [0-2][0-9]:[0-5][0-9]) ;;
    *) log "BACKUP_TIME_UTC must be HH:MM, got '$1'" >&2; return 2 ;;
  esac
  hh=${1%%:*}; mm=${1#*:}
  hh=${hh#0}; mm=${mm#0}
  target=$(( ${hh:-0} * 3600 + ${mm:-0} * 60 ))
  now=$(date -u +%s)
  wait_s=$(( (target - now % 86400 + 86400) % 86400 ))
  [ "$wait_s" -gt 0 ] || wait_s=86400
  echo "$wait_s"
}

daemon() {
  trap 'log "stopping"; exit 0' INT TERM
  seconds_until "$BACKUP_TIME_UTC" > /dev/null # validate before the first sleep
  log "daemon started: daily at $BACKUP_TIME_UTC UTC into $BACKUP_DIR, keeping $KEEP_DAYS days"
  if [ ! -f "$BACKUP_DIR/stocks-$(date -u +%F).dump" ]; then
    backup_once || log "startup backup failed; next attempt at $BACKUP_TIME_UTC UTC" >&2
  fi
  while :; do
    s=$(seconds_until "$BACKUP_TIME_UTC")
    sleep "$s" &
    wait $! || true
    backup_once || log "backup failed; next attempt tomorrow" >&2
  done
}

case "${1:-}" in
  --daemon) daemon ;;
  -h | --help) sed -n '2,15p' "$0" ;;
  *) backup_once "${1:-}" ;;
esac
