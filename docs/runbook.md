# stocks runbook

Operating the running league on the CasaOS server. Setup and deploys: [deploy.md](deploy.md). Spec: ARCHITECTURE
§8.4 (invariants), §12.9 (single instance and halt), §15.9 (market-data failures), §24 (deployment).

All commands run on the server. The shorthand `dc` means:

```sh
cd /DATA/AppData/stocks/compose          # the Compose project directory (its .env links to ../.env)
alias dc='sudo -u gha-runner docker compose -p stocks --env-file .env'
```

**Never use CasaOS's Uninstall or its settings dialog for this app** (deploy.md §7): Uninstall deletes the
project directory, and without "keep data" the whole `/DATA/AppData/stocks`. Stop the stack with `dc down`.

## At a glance

| Thing | Where |
|---|---|
| Containers | `stocks-app` (Spring Boot, :8080 HTTP+WS, :8081 actuator inside the container only), `stocks-db` (PostgreSQL 18.6), `stocks-backup`, later `stocks-agent-runner` (profile `agents`) |
| Networks | `stocks-backend` (internal: db, backup, app), `stocks-edge` (app egress, cloudflared, agent runner) |
| Config | `/DATA/AppData/stocks/.env` (mode 600; `compose/.env` is a link to it). `compose/docker-compose.yml` is replaced by every deploy (previous copy `.bak`). |
| Data | `pgdata/` (database), `backups/` (dumps), `agent-data/` (bots) |
| What is deployed | `compose/.deploy-state`, `grep STOCKS_TAG /DATA/AppData/stocks/.env`, `docker inspect -f '{{.Config.Image}}' stocks-app` |
| Logs | `docker logs -f stocks-app` (ECS JSON: `… 2>&1 \| jq -r '"\(.["@timestamp"]) \(.["log.level"]) \(.message)"'`); rotated at 5 × 10 MB per container |
| Deploy history | GitHub → Actions → Release (each run's summary lists the image and the previous one) |

## Health

```sh
dc ps                                                    # STATUS must say (healthy) for app, db, backup
docker inspect -f '{{.State.Health.Status}}' stocks-app
docker exec stocks-app curl -fsS http://127.0.0.1:8081/actuator/health            # details: db, diskSpace, …
docker exec stocks-app curl -fsS http://127.0.0.1:8081/actuator/health/readiness
```

Port 8081 is never published or tunneled, and in production it listens on the container's loopback only
(`management.server.address: 127.0.0.1`), so other containers (cloudflared, the agent runner) cannot read the
metrics either; use `docker exec`. The admin **System** page in the app shows the
engine state, the market-data probe, job runs and invariant results.

Start, stop, restart (the data stays):

```sh
dc restart app        # about a minute of downtime; no retroactive fills (§9.4)
dc stop app && dc start app
dc up -d              # re-apply .env changes (recreates only what changed)
```

## Failed deploy

The deploy job (`deploy/scripts/deploy.sh`) already did the obvious: when the new version was not healthy within
`DEPLOY_HEALTH_TIMEOUT`, it printed the app log, restored the previous image (`<image>:rollback`) and compose file,
and failed with **exit 1**. The league is running the previous version; fix forward with a new commit.

**Exit 2: rollback failed too.** The app is down. Usually the new version ran a database migration that the old
version cannot work with.

1. Read why: `docker logs --tail 300 stocks-app`.
2. If it is a configuration problem (`.env`), fix `.env` and run `dc up -d`.
3. Otherwise restore the database to the moment before the deploy, then run the old image:

   ```sh
   ls -lt /DATA/AppData/stocks/backups | head          # stocks-<date>-predeploy-<tag>.dump of the failed deploy
   grep STOCKS_TAG /DATA/AppData/stocks/.env           # deploy.sh left STOCKS_TAG=rollback: the image that ran before
   sudo -u gha-runner /home/gha-runner/actions-runner/_work/stocks/stocks/deploy/scripts/restore.sh \
     /DATA/AppData/stocks/backups/stocks-<date>-predeploy-<tag>.dump
   ```

   `restore.sh` restores the dump and starts the stack on that `rollback` image.

   Then set `STOCKS_TAG` in `.env` to that older `sha-…` tag (see `compose/.deploy-state`, `PREVIOUS_REF`) so later
   restarts keep it. Anything players did between that dump and now is lost; tell them.

## Engine halted / invariant failure

`InvariantChecker` (I1–I9, §8.4) runs at boot, at 04:05 ET and hourly. A failure **halts the engine**: order and
transfer commands return `ENGINE_HALTED` (503), market data keeps flowing, and the admin System page turns red
with the failing invariant and account.

1. Do not restart in a loop: the check runs again at boot and halts again.
2. Take a dump now: `dc exec -T backup /bin/sh /opt/stocks/pg_backup.sh halted`.
3. Read the failing invariant and the app log around it (`docker logs stocks-app 2>&1 | grep -i invariant`).
4. Never edit `cash_ledger`, executions or positions by hand: they are append-only (PostgreSQL triggers reject
   UPDATE and DELETE) and the invariants are what prove the books are right. The fix is a code fix (new release)
   or, for a single bad account, an admin action the spec allows (freeze, reset).
5. Resume from the admin System page (or `POST /api/v1/admin/engine/resume`, §21.1) only once the cause is
   understood. Resume runs the check first.

An admin can also halt the engine on purpose (System page) before risky maintenance.

## Market data problems

- **Alpaca 406 on the stream** ("connection limit exceeded"): another process uses the same Alpaca key, most often
  a developer machine running `MARKETDATA_PROFILE=hybrid` with the production key. Use separate Alpaca accounts for
  dev and prod. The app backs off 90 s and retries by itself.
- **Alpaca or Finnhub down at boot:** the app still starts (bundled calendar, assets from the database); the System
  page shows `DOWN`; market orders wait in `AWAITING_QUOTE` instead of filling on stale prices.
- **Keys changed:** update `.env`, then `dc up -d`.
- Production never falls back to simulated prices; with `MARKETDATA_PROFILE=simulated` it refuses to start.

## Backups

- Nightly at `BACKUP_TIME_UTC` (05:00 UTC) and at container start when today's dump is missing; one more before
  every deploy. Files: `/DATA/AppData/stocks/backups/stocks-YYYY-MM-DD[-label].dump`, kept `BACKUP_KEEP_DAYS` (14).
- Check: `dc ps backup` shows `(healthy)` (unhealthy = no success for 25 h);
  `cat /DATA/AppData/stocks/backups/.last-success`;
  `docker logs stocks-backup`.
- Backup now: `dc exec -T backup /bin/sh /opt/stocks/pg_backup.sh manual`
- Verify a dump is readable without touching the database:
  `docker run --rm -v /DATA/AppData/stocks/backups:/b:ro postgres:18.6-alpine pg_restore --list /b/<file>.dump | head`
- Copy dumps off the server regularly (see deploy.md §10).

## Restore

```sh
# the runner keeps a checkout of deploy/ from the last deploy job:
cd /home/gha-runner/actions-runner/_work/stocks/stocks
sudo -u gha-runner deploy/scripts/restore.sh /DATA/AppData/stocks/backups/stocks-2026-10-09.dump
```

`restore.sh` asks you to type `RESTORE`, takes a `prerestore` safety dump, stops the app (and the agent runner),
drops and recreates the `stocks` database, runs `pg_restore --exit-on-error`, starts the stack and waits for the
app to be healthy. The image is not changed: restoring an **older** dump under a newer image is fine (Flyway
applies the newer migrations at start); for a dump from a **newer** release, deploy that release's tag first.

The manual equivalent (ARCHITECTURE §24.2):

```sh
dc stop app
dc exec -T db sh -c 'dropdb -U stocks --if-exists --force stocks && createdb -U stocks -O stocks stocks \
  && pg_restore -U stocks -d stocks --no-owner --exit-on-error' < /DATA/AppData/stocks/backups/stocks-YYYY-MM-DD.dump
dc start app
```

**Rehearse it once** before the league starts (a Phase 1 exit criterion): restore last night's dump, log in, check
balances and the leaderboard.

## Secrets and accounts

- `DB_PASSWORD`: change it inside PostgreSQL first, then in `.env`, then recreate:
  `dc exec -T db psql -U stocks -d stocks -c "ALTER USER stocks PASSWORD '<new>'"`, edit `.env`, `dc up -d`.
  (`POSTGRES_PASSWORD` only matters when the database is first created.)
- `API_KEY_PEPPER`: **do not rotate** casually: every API key is an HMAC with it, so all bot and personal keys stop
  working and must be re-issued.
- `ENGINE_SEED`: may change at any time (it only seeds latency jitter).
- `ADMIN_BOOTSTRAP_*`: only used when no admin exists; keep them empty after the first login.
- Alpaca/Finnhub keys: rotate at the provider, update `.env`, `dc up -d`.

## Disk space

- Images: each deploy keeps the current image, `:rollback` and three older `sha-…`/version tags; older ones are
  removed. Check with `docker system df`.
- Logs are capped by the Compose logging options. Dumps are pruned after `BACKUP_KEEP_DAYS`.
- PostgreSQL is small for a 20-player league (tens of MB per year, mostly intraday samples, which are pruned
  after 14 days).

## Host layout

```
/DATA/AppData/stocks/            .env · pgdata/ · backups/ · scripts/pg_backup.sh · agent-data/   (the data)
/DATA/AppData/stocks/compose/    docker-compose.yml(.bak) · .deploy-state · .env -> ../.env      (the project)
```

The Compose project directory is kept apart from the data because CasaOS deletes a project's directory on
Uninstall, even with "keep data". **A host set up before this layout** (with `docker-compose.yml` directly in
`/DATA/AppData/stocks`) makes `deploy.sh` stop with "still uses the old layout". Migrate it once:

```sh
cd /home/gha-runner/actions-runner/_work/stocks/stocks      # or the folder you ran init-host.sh from
sudo bash deploy/scripts/init-host.sh --owner gha-runner     # moves the compose file, .bak and .deploy-state
cd /DATA/AppData/stocks/compose                              # into compose/ and links compose/.env
sudo -u gha-runner docker compose -p stocks up -d --force-recreate   # relabels the containers (about a minute down)
```

Then set the repository variable `DEPLOY_DIR` to `/DATA/AppData/stocks/compose` if you had set it explicitly.

## Development notes

- After the V1 migration is squashed or edited (Phase 0 and early Phase 1, §8), delete `backend/.data/` on every
  development machine: the dev H2 file still has the old schema history. The demo seed recreates everything.
- Production databases are never squashed: after the first production deploy, schema changes are new migrations
  only.
