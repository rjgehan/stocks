#!/usr/bin/env bash
# Restore a pg_dump (made by deploy/backup/pg_backup.sh) into the stocks database (docs/runbook.md "Restore").
#
#   deploy/scripts/restore.sh /DATA/AppData/stocks/backups/stocks-2026-10-09.dump [--yes]
#
# 1. takes a safety dump of the current database (backups/stocks-<date>-prerestore.dump)
# 2. stops app (and agent-runner), drops and recreates the database, pg_restore --exit-on-error
# 3. starts the stack again (with the agents profile when the agent runner was running, or COMPOSE_PROFILES names
#    it) and waits for the app to be healthy
# The image is not changed. If the dump comes from an OLDER release than the running image, Flyway applies the
# missing migrations at start; if it comes from a NEWER one, deploy that release's tag first.
#
# Environment: DEPLOY_DIR, the Compose project directory (default /DATA/AppData/stocks/compose, see deploy.sh),
# COMPOSE_PROJECT (default stocks).
set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/DATA/AppData/stocks/compose}"
PROJECT="${COMPOSE_PROJECT:-stocks}"
DUMP=""
YES=false

for a in "$@"; do
  case "$a" in
    --yes) YES=true ;;
    -h | --help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "unknown option $a" >&2; exit 64 ;;
    *) DUMP="$a" ;;
  esac
done
[[ -n "$DUMP" && -f "$DUMP" && -r "$DUMP" ]] || { echo "usage: $0 <readable .dump file> [--yes]" >&2; exit 64; }
[[ -f "$DEPLOY_DIR/docker-compose.yml" && -f "$DEPLOY_DIR/.env" ]] || { echo "no docker-compose.yml/.env in $DEPLOY_DIR" >&2; exit 1; }

compose() { docker compose -p "$PROJECT" --project-directory "$DEPLOY_DIR" -f "$DEPLOY_DIR/docker-compose.yml" --env-file "$DEPLOY_DIR/.env" "$@"; }

if [[ "$YES" != "true" ]]; then
  echo "This REPLACES the whole 'stocks' database with $DUMP."
  read -r -p "Type RESTORE to continue: " answer
  [[ "$answer" == "RESTORE" ]] || { echo "aborted"; exit 1; }
fi

echo "== safety dump of the current database"
if [[ -n "$(compose ps -q --status running backup)" ]]; then
  compose exec -T backup /bin/sh /opt/stocks/pg_backup.sh prerestore
else
  echo "   backup service not running: no safety dump" >&2
fi

echo "== stopping app and agent-runner"
# a plain `up -d` ignores profile services: remember whether the bots ran, to start them again at the end
runner_was_running="$(compose --profile agents ps -q --status running agent-runner 2> /dev/null || true)"
compose stop app > /dev/null
compose --profile agents stop agent-runner > /dev/null 2>&1 || true

echo "== restoring $DUMP"
compose up -d db > /dev/null
for _ in $(seq 1 60); do
  if compose exec -T db pg_isready -U stocks -d postgres > /dev/null 2>&1; then break; fi
  sleep 2
done
compose exec -T db sh -c \
  'dropdb -U stocks --if-exists --force stocks && createdb -U stocks -O stocks stocks && pg_restore -U stocks -d stocks --no-owner --exit-on-error' \
  < "$DUMP"

echo "== starting the stack"
if [[ -n "$runner_was_running" || ",${COMPOSE_PROFILES:-}," == *",agents,"* ]]; then
  compose --profile agents up -d
  echo "   agent runner started again (profile agents)"
else
  compose up -d
fi
for _ in $(seq 1 84); do
  cid="$(compose ps -q app)"
  status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$cid" 2>/dev/null || true)"
  if [[ "$status" == "healthy" ]]; then echo "restored; app is healthy"; exit 0; fi
  if [[ "$status" == "unhealthy" ]]; then break; fi
  sleep 5
done
echo "app did not become healthy; check: docker logs --tail 200 stocks-app" >&2
exit 1
