#!/usr/bin/env bash
# Render the CasaOS import file (docs/deploy.md §7B). The CasaOS "Import" form does not substitute ${...}
# variables, so this resolves docker-compose.yml against DIR/.env with `docker compose config` and writes
# DIR/casaos-import.yml (mode 600: it contains your secrets). Open that file in the CasaOS Files app (or `cat` it),
# copy everything, and paste it into App Store → + → Install a customized app → Import.
#
#   sudo bash casaos-compose.sh [--dir /DATA/AppData/stocks] [--ref main]
set -euo pipefail

DIR="/DATA/AppData/stocks"
REF="main"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dir) DIR="${2:?}"; shift 2 ;;
    --ref) REF="${2:?}"; shift 2 ;;
    -h | --help) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 64 ;;
  esac
done

[[ -f "$DIR/.env" ]] || { echo "$DIR/.env not found: run init-host.sh first" >&2; exit 1; }
missing="$(grep -E '^(APP_PUBLIC_URL|DB_PASSWORD|API_KEY_PEPPER|ENGINE_SEED|ALPACA_KEY_ID|ALPACA_SECRET_KEY|CLOUDFLARE_TUNNEL_TOKEN)=$' "$DIR/.env" \
  | cut -d= -f1 | tr '\n' ' ' || true)"
if [[ -n "$missing" ]]; then
  echo "warning: empty in $DIR/.env: $missing" >&2
fi

mkdir -p "$DIR/compose"
curl -fsSL -o "$DIR/compose/docker-compose.yml" \
  "https://raw.githubusercontent.com/rjgehan/stocks/${REF}/docker-compose.yml"
out="$DIR/casaos-import.yml"
umask 077
docker compose -p stocks -f "$DIR/compose/docker-compose.yml" --env-file "$DIR/.env" config > "$out.tmp"
mv "$out.tmp" "$out"
chmod 600 "$out"
if grep -q '\${' "$out"; then
  echo "error: unresolved variables remain in $out" >&2; exit 1
fi
echo "Wrote $out ($(wc -l < "$out") lines). Paste its contents into CasaOS → App Store → + → Install a customized app → Import."
