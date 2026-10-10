#!/usr/bin/env bash
# One-time preparation of the CasaOS host for stocks (docs/deploy.md, step 4). Safe to re-run: it never
# overwrites an existing .env or data.
#
#   sudo deploy/scripts/init-host.sh --owner gha-runner --image ghcr.io/<owner>/stocks \
#        --url https://stocks.example.com [--casaos-link] [--dir /DATA/AppData/stocks]
#
# It creates DIR (default /DATA/AppData/stocks) with pgdata/, backups/, scripts/pg_backup.sh and agent-data/,
# creates DIR/.env from .env.example with fresh DB_PASSWORD, API_KEY_PEPPER and ENGINE_SEED (mode 600), and gives
# the runner user (--owner) DIR itself, .env and scripts/ so the deploy job can update them. pgdata/ and
# backups/ stay with root and the postgres container. --casaos-link also links DIR/.env into
# /var/lib/casaos/apps/stocks/ for a CasaOS "Import" install.
#
# Run it from a checkout of the repository, or from a folder holding .env.example, init-host.sh and
# pg_backup.sh (use --from FOLDER).
set -euo pipefail

DIR="/DATA/AppData/stocks"
OWNER=""
IMAGE=""
URL=""
CASAOS_LINK=false
FROM=""

usage() { sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dir) DIR="${2:?}"; shift 2 ;;
    --owner) OWNER="${2:?}"; shift 2 ;;
    --image) IMAGE="${2:?}"; shift 2 ;;
    --url) URL="${2:?}"; shift 2 ;;
    --from) FROM="${2:?}"; shift 2 ;;
    --casaos-link) CASAOS_LINK=true; shift ;;
    -h | --help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 64 ;;
  esac
done

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
find_src() { # $1 = path inside the repo, $2 = bare file name in --from
  local c
  for c in ${FROM:+"$FROM/$2"} ${FROM:+"$FROM/$1"} "$here/../../$1" "$here/$2"; do
    if [[ -f "$c" ]]; then echo "$c"; return 0; fi
  done
  echo "cannot find $1 (use --from)" >&2
  return 1
}
ENV_EXAMPLE="$(find_src .env.example .env.example)"
BACKUP_SCRIPT="$(find_src deploy/backup/pg_backup.sh pg_backup.sh)"

if [[ -n "$IMAGE" && ! "$IMAGE" =~ ^[a-z0-9]+([._:/-][a-z0-9]+)*$ ]]; then
  echo "--image must be a lowercase repository without tag, e.g. ghcr.io/alice/stocks" >&2; exit 64
fi
if [[ -n "$URL" && ! "$URL" =~ ^https://[^[:space:]\$\'\"]+$ ]]; then
  echo "--url must be an https URL" >&2; exit 64
fi
if [[ -n "$OWNER" ]] && ! id "$OWNER" > /dev/null 2>&1; then
  echo "user $OWNER does not exist" >&2; exit 64
fi

rand_hex() { od -An -tx1 -N"$1" /dev/urandom | tr -d ' \n'; }
rand_b64() { head -c "$1" /dev/urandom | base64 | tr -d '\n'; }

set_key() { # set_key FILE KEY VALUE (only when the key is empty or missing)
  local file="$1" key="$2" value="$3" tmp
  if grep -qE "^${key}=.+" "$file"; then return 0; fi
  tmp="$(mktemp)"
  if grep -qE "^${key}=" "$file"; then
    awk -v k="$key" -v v="$value" '$0 ~ "^" k "=" { print k "=" v; next } { print }' "$file" > "$tmp"
  else
    cat "$file" > "$tmp"; echo "${key}=${value}" >> "$tmp"
  fi
  cat "$tmp" > "$file"; rm -f "$tmp"
}

echo "== $DIR"
mkdir -p "$DIR" "$DIR/pgdata" "$DIR/backups" "$DIR/scripts" "$DIR/agent-data"
install -m 0755 "$BACKUP_SCRIPT" "$DIR/scripts/pg_backup.sh"
echo "   scripts/pg_backup.sh installed"

if [[ -f "$DIR/.env" ]]; then
  echo "   .env exists: only empty keys are filled"
else
  install -m 0600 "$ENV_EXAMPLE" "$DIR/.env"
  echo "   .env created from $(basename "$ENV_EXAMPLE")"
fi
chmod 0600 "$DIR/.env"
set_key "$DIR/.env" DB_PASSWORD "$(rand_hex 24)"
set_key "$DIR/.env" API_KEY_PEPPER "$(rand_b64 48)"
set_key "$DIR/.env" ENGINE_SEED "$(rand_hex 32)"
if [[ -n "$IMAGE" ]]; then
  awk -v v="$IMAGE" '/^STOCKS_IMAGE=/ { print "STOCKS_IMAGE=" v; next } { print }' "$DIR/.env" > "$DIR/.env.tmp" \
    && cat "$DIR/.env.tmp" > "$DIR/.env" && rm -f "$DIR/.env.tmp"
fi
if [[ -n "$URL" ]]; then
  awk -v v="$URL" '/^APP_PUBLIC_URL=/ { print "APP_PUBLIC_URL=" v; next } { print }' "$DIR/.env" > "$DIR/.env.tmp" \
    && cat "$DIR/.env.tmp" > "$DIR/.env" && rm -f "$DIR/.env.tmp"
fi

if [[ -n "$OWNER" ]]; then
  chown "$OWNER": "$DIR" "$DIR/.env" "$DIR/agent-data"
  chown -R "$OWNER": "$DIR/scripts"
  echo "   $DIR, .env, scripts/ and agent-data/ now belong to $OWNER"
fi

if [[ "$CASAOS_LINK" == "true" ]]; then
  mkdir -p /var/lib/casaos/apps/stocks
  ln -sfn "$DIR/.env" /var/lib/casaos/apps/stocks/.env
  echo "   linked /var/lib/casaos/apps/stocks/.env -> $DIR/.env (for CasaOS Import)"
fi

missing="$(grep -E '^(APP_PUBLIC_URL|STOCKS_IMAGE|ALPACA_KEY_ID|ALPACA_SECRET_KEY|FINNHUB_TOKEN|ADMIN_BOOTSTRAP_USERNAME|ADMIN_BOOTSTRAP_PASSWORD)=(.*example\.com.*|.*your-github-user.*)?$' "$DIR/.env" | cut -d= -f1 | tr '\n' ' ' || true)"
echo
echo "Done. Edit $DIR/.env next (sudo -u ${OWNER:-<owner>} nano $DIR/.env)."
if [[ -n "$missing" ]]; then echo "Still to fill in: $missing"; fi
