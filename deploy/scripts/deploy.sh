#!/usr/bin/env bash
# Deploy (or roll back to) one image tag of stocks on the server (ARCHITECTURE §24.7, docs/deploy.md).
#
# Run by the `deploy` job of .github/workflows/release.yml on the self-hosted runner [self-hosted, casaos], or by
# hand on the server from a checkout of this repository:
#
#   STOCKS_IMAGE=ghcr.io/<owner>/stocks deploy/scripts/deploy.sh --tag sha-1a2b3c4 [--sync-from .]
#
# Steps:
#   1. preflight (docker, compose file, .env, tag format)
#   2. --sync-from DIR: copy DIR/docker-compose.yml into the deploy dir (unless DEPLOY_SYNC_COMPOSE=false) and
#      DIR/deploy/backup/pg_backup.sh into $STOCKS_DATA_ROOT/scripts/
#   3. pull <image>:<tag>, remember the running image (local tag <image>:rollback)
#   4. pre-deploy pg_dump (backups/stocks-<date>-predeploy-<tag>.dump) when the database is running
#   5. write STOCKS_TAG=<tag> into .env, `docker compose up -d`, wait for the app healthcheck (readiness, :8081)
#   6. on failure: print the app logs, restore the previous compose file and image, wait for health again,
#      exit 1 (rolled back) or 2 (rollback failed too; see docs/runbook.md "Failed deploy")
#
# Environment (all optional except STOCKS_IMAGE, which may also come from .env):
#   STOCKS_IMAGE           lowercase repository without tag, e.g. ghcr.io/alice/stocks
#   DEPLOY_DIR             directory holding docker-compose.yml and .env    (default /DATA/AppData/stocks)
#   STOCKS_DATA_ROOT       host data root used by docker-compose.yml         (default /DATA/AppData/stocks)
#   COMPOSE_PROJECT        compose project name                              (default stocks)
#   COMPOSE_PROFILES       extra compose profiles, e.g. "agents"             (default none)
#   DEPLOY_SYNC_COMPOSE    true|false: overwrite DEPLOY_DIR/docker-compose.yml with --sync-from's (default true)
#   DEPLOY_HEALTH_TIMEOUT  seconds to wait for a healthy app                 (default 420)
#   DEPLOY_BACKUP          true|false: pre-deploy pg_dump                    (default true)
#   DEPLOY_KEEP_IMAGES     how many older sha-/version tags of the image to keep   (default 3)
set -euo pipefail

TAG=""
SYNC_FROM=""
DEPLOY_DIR="${DEPLOY_DIR:-/DATA/AppData/stocks}"
DATA_ROOT="${STOCKS_DATA_ROOT:-/DATA/AppData/stocks}"
PROJECT="${COMPOSE_PROJECT:-stocks}"
SYNC_COMPOSE="${DEPLOY_SYNC_COMPOSE:-true}"
TIMEOUT="${DEPLOY_HEALTH_TIMEOUT:-420}"
BACKUP="${DEPLOY_BACKUP:-true}"
KEEP_IMAGES="${DEPLOY_KEEP_IMAGES:-3}"
SERVICE="app"

usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag) TAG="${2:-}"; shift 2 ;;
    --sync-from) SYNC_FROM="${2:-}"; shift 2 ;;
    --dir) DEPLOY_DIR="${2:-}"; shift 2 ;;
    --project) PROJECT="${2:-}"; shift 2 ;;
    --timeout) TIMEOUT="${2:-}"; shift 2 ;;
    --no-backup) BACKUP=false; shift ;;
    --no-sync-compose) SYNC_COMPOSE=false; shift ;;
    -h | --help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 64 ;;
  esac
done

# ------------------------------------------------------------------------------------------------ helpers
ts() { date -u +%H:%M:%SZ; }
log() { echo "[$(ts)] $*"; }
err() {
  if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then echo "::error::$*"; fi
  echo "[$(ts)] ERROR: $*" >&2
}
die() { err "$*"; exit 1; }
group() { if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then echo "::group::$*"; else log "== $*"; fi; }
endgroup() { if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then echo "::endgroup::"; fi; }
is_true() { case "$1" in [Tt][Rr][Uu][Ee] | 1 | [Yy][Ee][Ss]) return 0 ;; *) return 1 ;; esac; }

COMPOSE_FILE_PATH=""
ENV_FILE=""
compose() {
  docker compose -p "$PROJECT" --project-directory "$DEPLOY_DIR" -f "$COMPOSE_FILE_PATH" --env-file "$ENV_FILE" "$@"
}

# Print the value of KEY from the .env file (last assignment wins; surrounding quotes removed).
env_get() {
  local line
  line="$(grep -E "^[[:space:]]*$1=" "$ENV_FILE" | tail -n 1 || true)"
  line="${line#*=}"
  line="${line%\"}"; line="${line#\"}"
  line="${line%\'}"; line="${line#\'}"
  printf '%s' "$line"
}

# Set KEY=VALUE in the .env file in place (keeps the file's inode, owner, mode and ACLs).
env_set() {
  local key="$1" value="$2" tmp
  tmp="$(mktemp)"
  if grep -qE "^[[:space:]]*${key}=" "$ENV_FILE"; then
    awk -v k="$key" -v v="$value" '
      $0 ~ "^[[:space:]]*" k "=" { if (!done) { print k "=" v; done = 1 }; next }
      { print }' "$ENV_FILE" > "$tmp"
  else
    cat "$ENV_FILE" > "$tmp"
    [[ -z "$(tail -c 1 "$ENV_FILE")" ]] || echo >> "$tmp"
    echo "${key}=${value}" >> "$tmp"
  fi
  cat "$tmp" > "$ENV_FILE"
  rm -f "$tmp"
}

container_id() { compose ps -q "$1" 2>/dev/null | head -n 1; }

# Wait until the app container reports healthy. Returns 1 on unhealthy, exit, crash loop or timeout.
wait_healthy() {
  local deadline cid status state restarts
  deadline=$(( $(date +%s) + TIMEOUT ))
  log "waiting up to ${TIMEOUT}s for '$SERVICE' to become healthy (readiness on :8081)"
  while :; do
    cid="$(container_id "$SERVICE")"
    if [[ -n "$cid" ]]; then
      status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo gone)"
      state="$(docker inspect -f '{{.State.Status}}' "$cid" 2>/dev/null || echo gone)"
      restarts="$(docker inspect -f '{{.RestartCount}}' "$cid" 2>/dev/null || echo 0)"
      case "$status/$state" in
        healthy/running) log "'$SERVICE' is healthy"; return 0 ;;
        unhealthy/*) err "'$SERVICE' is unhealthy"; return 1 ;;
        */exited | */dead) err "'$SERVICE' container is $state"; return 1 ;;
        */gone) ;; # being replaced right now; look again
        none/running) err "'$SERVICE' has no healthcheck; refusing to call it healthy"; return 1 ;;
      esac
      if [[ "$restarts" -gt 0 ]]; then err "'$SERVICE' restarted $restarts time(s) (crash loop)"; return 1; fi
    fi
    if [[ $(date +%s) -ge $deadline ]]; then err "timed out after ${TIMEOUT}s (health: ${status:-n/a})"; return 1; fi
    sleep 5
  done
}

dump_logs() {
  local cid
  cid="$(container_id "$SERVICE")"
  [[ -n "$cid" ]] || return 0
  group "last 200 log lines of '$SERVICE'"
  docker logs --tail 200 "$cid" 2>&1 || true
  endgroup
}

write_summary() {
  local result="$1"
  [[ -n "${GITHUB_STEP_SUMMARY:-}" ]] || return 0
  {
    echo "### stocks deploy: ${result}"
    echo ""
    echo "| | |"
    echo "|---|---|"
    echo "| Image | \`${IMAGE}:${TAG}\` |"
    echo "| Previous | \`${PREV_REF:-none}\` |"
    echo "| Directory | \`${DEPLOY_DIR}\` |"
    echo "| Host | \`$(hostname)\` |"
  } >> "$GITHUB_STEP_SUMMARY"
}

# ------------------------------------------------------------------------------------------------ preflight
[[ -n "$TAG" ]] || { usage >&2; die "--tag is required"; }
[[ "$TAG" =~ ^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$ ]] || die "invalid tag '$TAG'"
[[ "$TAG" != "rollback" ]] || die "'rollback' is reserved for the local rollback image"
[[ "$TIMEOUT" =~ ^[0-9]+$ ]] || die "DEPLOY_HEALTH_TIMEOUT must be a number of seconds"
command -v docker > /dev/null || die "docker is not installed or not on PATH"
docker compose version > /dev/null 2>&1 || die "the docker compose plugin is missing"
docker info > /dev/null 2>&1 || die "cannot talk to the Docker daemon (is this user in the docker group?)"
[[ -d "$DEPLOY_DIR" ]] || die "DEPLOY_DIR $DEPLOY_DIR does not exist (run deploy/scripts/init-host.sh first)"
COMPOSE_FILE_PATH="$DEPLOY_DIR/docker-compose.yml"
ENV_FILE="$DEPLOY_DIR/.env"
[[ -f "$ENV_FILE" ]] || die "$ENV_FILE is missing (copy .env.example, fill it in, chmod 600)"
[[ -w "$ENV_FILE" ]] || die "$ENV_FILE is not writable by $(id -un) (the deploy records STOCKS_TAG there)"

IMAGE="${STOCKS_IMAGE:-$(env_get STOCKS_IMAGE)}"
[[ "$IMAGE" =~ ^[a-z0-9]+([._:/-][a-z0-9]+)*$ ]] || die "STOCKS_IMAGE '$IMAGE' is not a lowercase image repository"
[[ "$IMAGE" != *your-github-user* ]] || die "STOCKS_IMAGE still has the .env.example placeholder"

# ------------------------------------------------------------------------------------------------ sync files
COMPOSE_CHANGED=false
BACKUP_SCRIPT_CHANGED=false
if [[ -n "$SYNC_FROM" ]]; then
  [[ -f "$SYNC_FROM/docker-compose.yml" ]] || die "--sync-from $SYNC_FROM has no docker-compose.yml"
  if is_true "$SYNC_COMPOSE"; then
    if ! cmp -s "$SYNC_FROM/docker-compose.yml" "$COMPOSE_FILE_PATH"; then
      if [[ -f "$COMPOSE_FILE_PATH" ]]; then
        cp -p "$COMPOSE_FILE_PATH" "$COMPOSE_FILE_PATH.bak"
        log "updating $COMPOSE_FILE_PATH from the repository (previous copy: docker-compose.yml.bak)"
      else
        log "installing $COMPOSE_FILE_PATH from the repository"
      fi
      cp "$SYNC_FROM/docker-compose.yml" "$COMPOSE_FILE_PATH.tmp"
      mv -f "$COMPOSE_FILE_PATH.tmp" "$COMPOSE_FILE_PATH"
      COMPOSE_CHANGED=true
    fi
  else
    log "DEPLOY_SYNC_COMPOSE=false: keeping $COMPOSE_FILE_PATH as is"
  fi
  if [[ -f "$SYNC_FROM/deploy/backup/pg_backup.sh" ]]; then
    mkdir -p "$DATA_ROOT/scripts"
    if ! cmp -s "$SYNC_FROM/deploy/backup/pg_backup.sh" "$DATA_ROOT/scripts/pg_backup.sh"; then
      # Replace by rename: the running backup loop keeps reading its old copy until it is restarted.
      cp "$SYNC_FROM/deploy/backup/pg_backup.sh" "$DATA_ROOT/scripts/pg_backup.sh.tmp"
      chmod 0755 "$DATA_ROOT/scripts/pg_backup.sh.tmp"
      mv -f "$DATA_ROOT/scripts/pg_backup.sh.tmp" "$DATA_ROOT/scripts/pg_backup.sh"
      BACKUP_SCRIPT_CHANGED=true
      log "updated $DATA_ROOT/scripts/pg_backup.sh"
    fi
  fi
fi
[[ -f "$COMPOSE_FILE_PATH" ]] || die "$COMPOSE_FILE_PATH is missing (use --sync-from <repo checkout> or copy it there)"
[[ -f "$DATA_ROOT/scripts/pg_backup.sh" ]] || die "$DATA_ROOT/scripts/pg_backup.sh is missing (use --sync-from or copy deploy/backup/pg_backup.sh)"

export STOCKS_IMAGE="$IMAGE"
export STOCKS_TAG="$TAG"
compose config -q || die "docker compose config failed for $COMPOSE_FILE_PATH with $ENV_FILE"

# ------------------------------------------------------------------------------------------------ previous state
PREV_ID=""
PREV_REF=""
PREV_TAG_PERSIST="$(env_get STOCKS_TAG)"
cid="$(container_id "$SERVICE")"
if [[ -n "$cid" ]]; then
  PREV_ID="$(docker inspect -f '{{.Image}}' "$cid")"
  PREV_REF="$(docker inspect -f '{{.Config.Image}}' "$cid")"
  docker tag "$PREV_ID" "$IMAGE:rollback"
  log "running now: $PREV_REF ($PREV_ID); tagged $IMAGE:rollback"
  # Only an immutable tag of the same repository is worth recording as the rollback target in .env.
  if [[ "$PREV_REF" == "$IMAGE:"* && "${PREV_REF##*:}" =~ ^(sha-[0-9a-f]+|v?[0-9]+\.[0-9]+.*)$ ]]; then
    PREV_TAG_PERSIST="${PREV_REF##*:}"
  else
    PREV_TAG_PERSIST="rollback"
  fi
else
  log "no running '$SERVICE' container: first deploy, nothing to roll back to"
fi

# ------------------------------------------------------------------------------------------------ pull
group "pull $IMAGE:$TAG"
pulled=false
for attempt in 1 2 3; do
  if docker pull "$IMAGE:$TAG"; then pulled=true; break; fi
  log "pull attempt $attempt failed"; sleep $(( attempt * 5 ))
done
endgroup
is_true "$pulled" || die "cannot pull $IMAGE:$TAG (wrong tag, or the server is not logged in to ghcr.io: docs/deploy.md)"
NEW_ID="$(docker image inspect -f '{{.Id}}' "$IMAGE:$TAG")"
if [[ ",${COMPOSE_PROFILES:-}," == *",agents,"* ]]; then
  docker pull "$IMAGE-agent-runner:$TAG" || die "cannot pull $IMAGE-agent-runner:$TAG (profile agents is on)"
fi

# ------------------------------------------------------------------------------------------------ pre-deploy backup
if is_true "$BACKUP"; then
  if [[ -n "$(compose ps -q --status running db 2>/dev/null)" && -n "$(compose ps -q --status running backup 2>/dev/null)" ]]; then
    group "pre-deploy backup"
    compose exec -T backup /bin/sh /opt/stocks/pg_backup.sh "predeploy-$TAG" \
      || die "pre-deploy backup failed; nothing was changed (skip it with DEPLOY_BACKUP=false)"
    endgroup
  else
    log "database or backup service not running: skipping the pre-deploy backup"
  fi
fi

# ------------------------------------------------------------------------------------------------ deploy
rollback() {
  if [[ -z "$PREV_ID" ]]; then
    err "first deploy failed and there is no previous version to roll back to"
    write_summary "FAILED (no previous version)"
    exit 1
  fi
  group "rollback to $PREV_REF"
  if is_true "$COMPOSE_CHANGED" && [[ -f "$COMPOSE_FILE_PATH.bak" ]]; then
    cp -p "$COMPOSE_FILE_PATH.bak" "$COMPOSE_FILE_PATH"
    log "restored the previous docker-compose.yml"
  fi
  export STOCKS_TAG="rollback"
  compose up -d "$SERVICE" || true
  endgroup
  if wait_healthy; then
    env_set STOCKS_TAG "$PREV_TAG_PERSIST"
    err "deploy of $IMAGE:$TAG failed; rolled back to $PREV_REF (STOCKS_TAG=$PREV_TAG_PERSIST)"
    write_summary "FAILED, rolled back to \`$PREV_REF\`"
    exit 1
  fi
  dump_logs
  env_set STOCKS_TAG "rollback"
  err "ROLLBACK FAILED too. The app is down. Follow docs/runbook.md 'Failed deploy' (a pre-deploy dump is in $DATA_ROOT/backups)."
  write_summary "FAILED, rollback FAILED"
  exit 2
}

env_set STOCKS_IMAGE "$IMAGE"
env_set STOCKS_TAG "$TAG"
group "docker compose up -d ($IMAGE:$TAG)"
if ! compose up -d; then
  endgroup
  err "docker compose up failed"
  dump_logs
  rollback
fi
endgroup
if is_true "$BACKUP_SCRIPT_CHANGED" && ! is_true "$COMPOSE_CHANGED"; then
  compose restart backup > /dev/null || log "warning: could not restart the backup service"
fi

if ! wait_healthy; then
  dump_logs
  rollback
fi

RUN_ID="$(docker inspect -f '{{.Image}}' "$(container_id "$SERVICE")")"
[[ "$RUN_ID" == "$NEW_ID" ]] || { err "'$SERVICE' runs $RUN_ID, expected $NEW_ID"; rollback; }

# ------------------------------------------------------------------------------------------------ success
{
  echo "DEPLOYED_TAG=$TAG"
  echo "DEPLOYED_IMAGE=$IMAGE"
  echo "DEPLOYED_ID=$NEW_ID"
  echo "DEPLOYED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "PREVIOUS_REF=${PREV_REF:-}"
  echo "PREVIOUS_ID=${PREV_ID:-}"
} > "$DEPLOY_DIR/.deploy-state" 2> /dev/null || log "warning: could not write $DEPLOY_DIR/.deploy-state"

# Keep the newest KEEP_IMAGES older sha-/version tags of this image for manual rollbacks; drop the rest.
kept=0
while read -r t; do
  [[ "$t" == "$TAG" ]] && continue
  kept=$(( kept + 1 ))
  if [[ $kept -gt $KEEP_IMAGES ]]; then docker image rm "$IMAGE:$t" > /dev/null 2>&1 || true; fi
done < <(docker image ls "$IMAGE" --format '{{.Tag}}' | grep -E '^(sha-[0-9a-f]+|v?[0-9]+\.[0-9]+.*)$' || true)
docker image prune -f --filter "label=org.opencontainers.image.title=stocks" > /dev/null 2>&1 || true

log "deployed $IMAGE:$TAG"
write_summary "OK"
