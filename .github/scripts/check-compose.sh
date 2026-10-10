#!/usr/bin/env bash
# Validate docker-compose.yml and .env.example (ARCHITECTURE §24.2): it must work with plain `docker compose` AND
# survive a CasaOS "Import". Needs the docker CLI with the compose plugin (no daemon) and jq.
#
#   .github/scripts/check-compose.sh [docker-compose.yml] [.env.example]
set -euo pipefail

COMPOSE="${1:-docker-compose.yml}"
ENV_EXAMPLE="${2:-.env.example}"
fail=0
bad() { echo "check-compose: $*" >&2; fail=1; }

[[ -f "$COMPOSE" ]] || { echo "check-compose: $COMPOSE not found" >&2; exit 2; }
[[ -f "$ENV_EXAMPLE" ]] || { echo "check-compose: $ENV_EXAMPLE not found" >&2; exit 2; }

# 1. Docker Compose accepts it, with and without the optional profiles. Required secrets get dummy values.
tmp_env="$(mktemp)"
trap 'rm -f "$tmp_env"' EXIT
sed -E 's/^(DB_PASSWORD|API_KEY_PEPPER|ENGINE_SEED)=$/\1=ci-dummy-value/' "$ENV_EXAMPLE" > "$tmp_env"
dc() { docker compose -f "$COMPOSE" --env-file "$tmp_env" "$@"; }
dc config -q || bad "docker compose config failed"
dc --profile agents config -q || bad "docker compose --profile agents config failed"
json="$(dc --profile agents config --format json)"

# 2. CasaOS-safety rules (see the header of docker-compose.yml).
if grep -nE '^[[:space:]]+build:' "$COMPOSE"; then bad "'build:' is not allowed (deploy images from GHCR)"; fi
if grep -nE '^[[:space:]]+env_file:' "$COMPOSE"; then bad "'env_file:' is not allowed (CasaOS Import cannot resolve it)"; fi
if grep -nE '\$[^{]' "$COMPOSE"; then bad "bare or escaped dollar signs are not allowed (CasaOS re-interpolates the file)"; fi
if grep -nE '^[[:space:]]*-[[:space:]]+\.{1,2}/|source:[[:space:]]+\.' "$COMPOSE"; then bad "relative host paths are not allowed"; fi
while read -r src; do
  [[ "$src" == /DATA/AppData/stocks/* ]] || bad "bind source outside /DATA/AppData/stocks: $src"
done < <(jq -r '.services[].volumes[]? | select(.type == "bind") | .source' <<< "$json")

# 3. Every variable the compose file references is defined in .env.example (CasaOS Import drops defaults).
while read -r var; do
  grep -qE "^${var}=" "$ENV_EXAMPLE" || bad "\${$var} is used in $COMPOSE but missing from $ENV_EXAMPLE"
done < <(grep -oE '\$\{[A-Za-z_][A-Za-z0-9_]*' "$COMPOSE" | cut -c3- | sort -u)

# 4. Structure the deploy job and CasaOS rely on.
jq -e '."x-casaos".main as $m | .services | has($m)' <<< "$json" > /dev/null || bad "x-casaos.main is not a service"
jq -e '."x-casaos".port_map | type == "string"' <<< "$json" > /dev/null || bad "x-casaos.port_map must be a string"
jq -e '.services.app.healthcheck.test | length > 0' <<< "$json" > /dev/null || bad "app has no healthcheck"
jq -e '.services.app.image | test("^[a-z0-9./_-]+:[A-Za-z0-9._-]+$")' <<< "$json" > /dev/null \
  || bad "app image does not resolve to a lowercase repository:tag"
jq -e '[.services[] | select(.ports != null) | .ports[] | select(.target == 8081)] | length == 0' <<< "$json" > /dev/null \
  || bad "the actuator port 8081 must never be published"
jq -e '.services.db.image | startswith("postgres:18.6")' <<< "$json" > /dev/null || bad "db must run postgres 18.6 (§2.3)"

if [[ $fail -ne 0 ]]; then exit 1; fi
echo "check-compose: $COMPOSE and $ENV_EXAMPLE OK"
