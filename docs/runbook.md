# stocks runbook

Operating the running league on the CasaOS server. Setup and deploys: [deploy.md](deploy.md). Spec: ARCHITECTURE
§8.4 (invariants), §12.9 (single instance and halt), §14 (corporate actions), §15.9 (market-data failures), §20.5
(agent runner), §24 (deployment).

All commands run on the server. The shorthand `dc` means:

```sh
cd /DATA/AppData/stocks/compose          # the Compose project directory (its .env links to ../.env)
alias dc='sudo docker compose -p stocks --env-file .env'        # production (Watchtower; your own sudo user)
# with the optional self-hosted runner, run it as the runner's user instead:
# alias dc='sudo -u gha-runner docker compose -p stocks --env-file .env'
```

Scripts such as `restore.sh` live in the repository's `deploy/scripts/`. Production deploys through Watchtower and
has no runner checkout, so fetch the one you need from `main` first, e.g.
`sudo curl -fsSLo /DATA/AppData/stocks/scripts/restore.sh https://raw.githubusercontent.com/rjgehan/stocks/main/deploy/scripts/restore.sh`.

**Never use CasaOS's Uninstall or its settings dialog for this app** (deploy.md §7): Uninstall deletes the
project directory, and without "keep data" the whole `/DATA/AppData/stocks`. Stop the stack with `dc down`.

## At a glance

| Thing | Where |
|---|---|
| Containers | `stocks-app` (Spring Boot, :8080 HTTP+WS, :8081 actuator inside the container only; no host port), `stocks-db` (PostgreSQL 18.6), `stocks-backup`, `stocks-cloudflared` (this app's tunnel, `CLOUDFLARE_TUNNEL_TOKEN`), `stocks-agent-runner` (profile `agents`, the bots), `stocks-claude-trader` (profile `trader`, Claude Code check-ins on the owner's subscription, docs/agents.md §2.11) |
| Networks | `stocks-backend` (internal: db, backup, app), `stocks-edge` (app egress, cloudflared → `http://stocks-app:8080`, agent runner) |
| Config | `/DATA/AppData/stocks/.env` (mode 600; `compose/.env` is a link to it). `compose/docker-compose.yml` is replaced by every deploy (previous copy `.bak`). |
| Data | `pgdata/` (database), `backups/` (dumps), `agent-data/` (bots), `trader-data/` (the Claude trader: agents.toml and each agent's state) |
| What is deployed | `docker inspect -f '{{.Config.Image}} {{.Image}}' stocks-app` (Watchtower: `:latest` and its digest; the `sha-…` tag pointing at it is on the GHCR package page), `grep STOCKS_TAG /DATA/AppData/stocks/.env`; runner deploys also write `compose/.deploy-state` |
| Logs | `docker logs -f stocks-app` (ECS JSON: `… 2>&1 \| jq -r '"\(.["@timestamp"]) \(.["log.level"]) \(.message)"'`); rotated at 5 × 10 MB per container |
| Deploy history | GitHub → Actions → Release (each run's summary lists the image tags it pushed); `docker logs watchtower` (when it restarted what) |

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

## Deploys (Watchtower)

Production follows `main`: every push publishes `ghcr.io/rjgehan/stocks:latest` (and `agent-runner-latest`), and
Watchtower restarts `stocks-app` (and `stocks-agent-runner`, `stocks-cloudflared`) on the new image within its poll
interval. The site is down for about a minute while the single instance restarts; orders never fill retroactively.

- **A bad `latest`** (the app does not become healthy, or a bug): pin the last good image and stop following
  `latest` until a fix is pushed:

  ```sh
  sudo sed -i 's/^STOCKS_TAG=.*/STOCKS_TAG=sha-1a2b3c4/' /DATA/AppData/stocks/.env   # the previous sha (package page)
  dc up -d app
  ```

  Set `STOCKS_TAG=latest` and `dc up -d` again once `main` is fixed. Database migrations are forward-only: if the bad
  release migrated the schema incompatibly, restore last night's dump with the older image (see **Restore**).
- **Compose or backup-script changes** are not applied by Watchtower: when a release changes `docker-compose.yml`
  or `deploy/backup/pg_backup.sh`, copy them from `main` into `compose/` and `scripts/` and run `dc up -d`.

## Failed deploy (optional self-hosted runner)

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

An admin can also halt the engine on purpose (System page, or `POST /api/v1/admin/engine/halt` with `{reason}`)
before risky maintenance; resume the same way.

**Halted with `Bootstrap failed: …`** (§12.9): the engine could not load its state at boot, usually because the
database was unreachable. It retries by itself with backoff (up to once a minute) and resumes on success; check
`dc ps db` and the app log. Resume from the System page also bootstraps first.

**Fill-quality warnings (I10).** `FILL_QUALITY_AUDIT` checks every execution 16–30 minutes after it happened
against that minute's consolidated 1-minute bar (widened by the quote spread). A fill outside it is logged at WARN
(`I10 fill quality: …`) and written once to Admin → Audit as `SYSTEM / FILL_QUALITY_WARNING`. It **never halts**
the engine: look at the execution's reference quote (order detail → "quote used") and the provider status at that
time. A burst of them usually means a bad quote feed (stale IEX quotes, a provider outage), not a books problem.

## Market data problems

- **Alpaca 406 on the stream** ("connection limit exceeded"): another process uses the same Alpaca key, most often
  a developer machine running `MARKETDATA_PROFILE=hybrid` with the production key. Use separate Alpaca accounts for
  dev and prod. The app backs off 90 s and retries by itself.
- **Alpaca or Finnhub down at boot:** the app still starts (bundled calendar, assets from the database); the System
  page shows `DOWN`; market orders wait in `AWAITING_QUOTE` instead of filling on stale prices.
- **Keys changed:** update `.env`, then `dc up -d`.
- Production never falls back to simulated prices; with `MARKETDATA_PROFILE=simulated` it refuses to start.

## Corporate actions (splits, dividends, ticker changes)

Jobs (ET, admin System page → Jobs): `CORPORATE_ACTION_SYNC` 02:30 daily (Alpaca corporate actions for held,
open-order and watched symbols, 7 days back and 90 ahead), `CORPORATE_ACTION_APPLY` 03:45 on trading days (every
PENDING action whose ex-date has come: ticker changes, then splits, then dividends), `PAY_DIVIDENDS` 06:00 on
trading days (pays accrued dividends whose payable date has come). Each affected player gets a notification and an
activity row; none of it moves the leaderboard.

**Needs admin.** Mergers, spin-offs and delistings (Phase 1 has no tool for them), a split without valid rates, a
split that was reported after its ex-date's pre-open (also when the app was down at 02:30 and came back after 04:00
ET on the ex-date, before it had seen the split), and a split that the split guard detected in the bars (04:01 and
09:20 ET) become `NEEDS_ADMIN`. Connected admins get an alert, and the System page shows the count. **A split in
`NEEDS_ADMIN` puts the symbol on hold**: its orders wait with `CORPORATE_ACTION_HOLD`, new orders get
`SYMBOL_NOT_TRADABLE`, and positions are valued at the last pre-split close. Resolve it the same morning:

1. Admin → **Corp. actions** (`/admin/corporate-actions`) → filter "Needs admin" → **Resolve**.
2. Check the split on the issuer's press release or exchange notice. **Apply** with the confirmed old and new share
   counts (2-for-1 = old 1, new 2; 1-for-8 reverse = old 8, new 1) and a note: it applies the split at once
   (positions, lots, cash in lieu, open orders on the symbol cancelled) and lifts the hold. **Skip** (with a note)
   when it is not a real split, or for a merger / spin-off / delisting: that only lifts the hold. Both ask for your
   password again when the last check is older than 15 minutes, and both are in Admin → Audit.
   API: `POST /api/v1/admin/corporate-actions/{id}/resolve` with
   `{"resolution":"APPLY","oldRate":"1","newRate":"2","note":"…"}` or `{"resolution":"SKIP","note":"…"}`.
3. A merger or delisting of a held symbol needs a manual decision (Phase 2 brings a tool); until then the position
   stays at its last price. Tell the players.

**A failed `CORPORATE_ACTION_APPLY` run** (System → Jobs shows FAILED): fix the cause from the app log, then
**Run now** with run key = that date (or wait: the next 02:30 sync and the boot catch-up apply overdue actions).
After a ticker change, quotes for the new symbol start with the 03:00 asset sync; if it ran before the change,
**Run now** `ASSET_SYNC`.

## Agent runner (the Claude and ChatGPT bots)

Setup, keys, prices and the per-run log format: [agents.md §2.5](agents.md#25-the-leagues-agent-runner-phase-1-toolsagent-runner).
The runner is the `agent-runner` service (profile `agents`): it trades as the two AI players at 09:30, 12:45 and
15:50 ET on trading days, plus the extra runs each bot books for itself (`<bot>/requested-runs.json`; delete an entry
to cancel it), through `http://app:8080/mcp`, within `AGENT_MAX_TOOL_CALLS`, `AGENT_MAX_RUN_SECONDS` and the per-bot
`AGENT_DAILY_USD_CAP`.

```sh
dc --profile agents up -d agent-runner                               # start, or apply .env changes
docker logs --tail 50 stocks-agent-runner                            # "bot enabled …", "next run: …", one line per run
docker exec stocks-agent-runner node dist/main.js --check            # configuration and prices (no secrets)
docker exec stocks-agent-runner node dist/main.js --once --bot claude    # run now (real LLM cost; counts toward the cap;
                                                                     # skipped_busy if a scheduled run of it is in progress)
ls /DATA/AppData/stocks/agent-data/claude/runs/                      # one JSONL log per run; spend.json = today's spend
dc --profile agents stop agent-runner                                # pause both bots
```

- **A bot misbehaves** (spams orders, trades nonsense): freeze its player in Admin → Players (its key stops working
  at once, open orders are cancelled), or stop the runner. Unfreeze when fixed.
- **`status=daily_cap`** in the run line: the bot hit its dollar cap; its remaining runs today are skipped.
  `bot skipped … missing …`: a key or model is empty in `.env`. `no price for CLAUDE_MODEL=…`: set
  `AGENT_PRICE_CLAUDE` (or `AGENT_PRICE_CHATGPT`).
- **Permission denied on `/data`**: the image runs as uid 1000; run `sudo chown -R 1000:1000 /DATA/AppData/stocks/agent-data` once.
- The bots' orders and rationales are also visible in the app (order detail, player profile, the feed). Their
  private notes live only in `/DATA/AppData/stocks/agent-data/<bot>/notes.md`; what they choose to publish with
  `add_journal_note` shows on their profile (Journal card) and in the feed at once (public mode; after the feed
  delay only if the admin sets one in Admin → Settings).
- **Run reports (Phase 2):** every run, skipped ones included, is reported to the app with the bot's own key and
  shows under **Admin → Bots** (status, tool calls, orders, cost) and as "Bot activity" on the bot's profile. No
  reports? Check the runner runs the wave A image (`docker logs stocks-agent-runner | grep -i report`) and that
  `AGENT_REPORT_RUNS` is not `false`. A bot can report at most 100 new runs per 24 h (429 beyond that).
- **Run now (claude-trader bots, §20.9):** the **Run now** button (Admin → Bots, the bot's profile, or the ⋯ menu in
  Admin → Players) asks `stocks-claude-trader` for one check-in; it is picked up within about a minute. A **greyed-out
  button** ("No claude-trader service has asked for @x's runs …") or **"Expired: the trader did not pick it up"**:
  the trader is not running (`dc --profile trader up -d --no-deps claude-trader`), or the agent is `enabled = false`
  or missing in its agents file, or its key belongs to another player (`docker logs stocks-claude-trader | grep -i
  "run request"`). A busy trader (long check-ins, every `STOCKS_TRADER_MAX_PARALLEL` slot in use) does not grey it
  out: it sends a heartbeat for each agent it cannot claim for, and the request waits for a free slot. Right after
  an app restart the button stays grey for up to a minute, until the trader's next claim or heartbeat; an open page
  re-checks every 30 s and turns it on by itself. A picked-up request without a report after 30 minutes is FAILED.
  A frozen owner cannot run their bots (`PLAYER_FROZEN`; unfreeze them first).
- **Run all (§20.9):** **Run all** (Admin → Bots above the cards, Admin → Players under the header; **Dry run all** in
  its menu) asks for one check-in of every bot the trader serves; the confirmation names them and says why the others
  are left out. The summary under the button lists each bot: **Requested**, **Already on its way** (a request was
  open: nothing new), or **Skipped** with the reason: *FROZEN* (unfreeze it first), *no claude-trader service serves
  it* (same checks as the greyed-out button above; right after an app restart wait a minute) or *the daily limit* (20
  requests per bot in 24 h). The trader runs at most `STOCKS_TRADER_MAX_PARALLEL` (3) sessions at once, so with more
  bots the others wait for a slot; a request still unclaimed after 30 minutes expires (raise the limit, or run the
  rest again later). Every Run all is in the audit log (`BOT_RUN_ALL`, plus one `BOT_RUN_REQUEST` per bot).

## Phase 2 features: jobs, alerts, Web Push, OAuth, SSE

Everything below works with the Phase 1 `.env`; every setting has a default (ARCHITECTURE §23).

**New jobs and tasks** (Admin → System lists them with their last run):

| Name | When | What | If it fails |
|---|---|---|---|
| `BENCHMARK_BACKFILL [2025-12-31:QQQ]` | once, at the first boot of wave A | QQQ daily closes since 2025-12-31 (display only: P&L Analysis) | **Run now**; until it is DONE the QQQ line is blank or short |
| `SCREENER_STATS` | every 60 s | 20-day average volumes once per trading day; ≤ 3 Finnhub company profiles a minute (market caps fill in over about 3.5 h after the first deploy) | the screener footer shows the coverage; without `FINNHUB_TOKEN` there are no market caps |
| `PUSH_LEAGUE_EVENTS` | every 60 s | Web Push for a new weekly leader | harmless; the in-app feed still shows it |
| `OAUTH_CLEANUP` | hourly | expired OAuth requests and codes, stale refresh tokens, dead grants, unused DCR clients | harmless; retried next hour |
| `HOUSEKEEPING` (02:00) | daily | also deletes `api_access_log` buckets older than 90 days | as before |

The price-alert monitor is a thread, not a job (`price-alerts`, every 250 ms, outside the engine): it fires only on a
live price, never on a stale, synthetic, delayed or halted quote, one older than 15 minutes, or while the market is
closed. `stocks.alerts.enabled=false` turns it off; `stocks.jobs.enabled` does not.

**Web Push (§19.2).** The VAPID key pair is generated on first boot and kept in `push_vapid_key` ("Generated the Web
Push VAPID key pair" in the log, once). Players enable push per device under Settings → Notifications (iPhone: only
from the Home Screen app, iOS 16.4+) and can send themselves a test notification (once per 10 s). Checks:
- Settings → Notifications says push is unavailable, or writes answer 503 `PUSH_UNAVAILABLE`: the key pair is unusable
  or `stocks.push.enabled=false`; the boot log has "Web Push is off: …" with the reason. Notifications inside the app
  keep working.
- Never rotate the VAPID key casually: every existing subscription is bound to it, so all devices must enable push
  again. To supply your own pair: `JDK_JAVA_OPTIONS=-Dstocks.push.vapid.public-key=… -Dstocks.push.vapid.private-key=…`
  (base64url, both or neither), then `dc up -d app`.
- A device is dropped on a 404/410 from its push service, on logout of that session, on a password change and after
  10 failures in a row; the player re-enables it in Settings.
- Cloudflare: if a "Cache Everything" rule exists, exclude `/sw.js` and `/index.html`, or installed apps keep an old
  service worker. A new deploy shows "New version available · Reload" in open apps.

**OAuth connectors (§17.9).** The access-token signing key is generated on first boot, stored encrypted under
`API_KEY_PEPPER` in `oauth_signing_key` ("OAuth signing key generated and stored encrypted", once); rotation is under
"Secrets and accounts" below, player-side revocation under "Players, bots and money" above. Checks from outside:
`curl -s https://<host>/.well-known/oauth-protected-resource/mcp` returns `"resource":"https://<host>/mcp"`, and
`curl -si https://<host>/mcp` answers 401 with `resource_metadata=` in `WWW-Authenticate`. A connector that loops
on sign-in usually means a Cloudflare challenge or cache on `/oauth2/*` or `/.well-known/*`.

**SSE stream (§19.3).** `GET /api/v1/stream` serves API keys only (a browser session gets 403): at most 3 streams per
player and 200 in all (429 beyond), 15 s heartbeats, a `Last-Event-ID` resume buffer of 2,000 events / 5 minutes held
in memory (a restart answers `reset {RESTARTED}` and clients refetch). Revoked keys and frozen players are cut off
within 30 s (a freeze at once). Through the tunnel:
`curl -N "https://<host>/api/v1/stream?topics=session" -H "Authorization: Bearer $KEY"` prints `retry: 3000` and
`event: hello`, then `: hb` every 15 s; if nothing arrives until the connection closes, a proxy is buffering it.

**API access log (§17.10).** API-key REST and MCP calls are counted per hour, key, endpoint and outcome: each player
sees their own under Settings → Recent API calls, the admin sees everyone's. Rows older than 90 days are deleted by
`HOUSEKEEPING`; up to 15 s of counts can be lost on a crash (not on a normal restart).

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
# production (Watchtower, no runner checkout): fetch the script from main (see the top of this page), then
sudo bash /DATA/AppData/stocks/scripts/restore.sh /DATA/AppData/stocks/backups/stocks-2026-10-09.dump
# with the optional runner, its checkout of deploy/ from the last deploy job works too:
# sudo -u gha-runner /home/gha-runner/actions-runner/_work/stocks/stocks/deploy/scripts/restore.sh <dump>
```

`restore.sh` asks you to type `RESTORE`, takes a `prerestore` safety dump, stops the Claude trader, the app and the
agent runner, drops and recreates the `stocks` database, runs `pg_restore --exit-on-error`, starts the stack and
waits for the app to be healthy. The agent runner is started again (`--profile agents`) when it was running before, or
when `COMPOSE_PROFILES` contains `agents`; otherwise it stays stopped, so check `docker ps` for `stocks-agent-runner`
after a restore and start it with `dc --profile agents up -d agent-runner` if the bots should trade. The Claude
trader (`stocks-claude-trader`, profile `trader`) is started again when it was running before. The image is not changed: restoring an **older** dump under a newer image is fine (Flyway
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

## Players, bots and money (admin console)

Everything here is in the admin console (`/admin`, ARCHITECTURE §21) or its API under `/api/v1/admin` (browser
session of an ADMIN only; an `Authorization` header is always refused). Writes marked R in §21.1 (creating players,
freezing, keys, resets, cash transfers, settings, engine halt) ask for the password again when the last check is
more than 15 minutes old (`POST /api/v1/auth/reauth`).

- **Invite a friend**: Admin → Invites → Create (uses 1–50, valid 1–720 hours) and send the link
  `https://<host>/join/<code>`. The code is shown once (only its hash is stored); revoke a leaked link at once. The
  friend picks a handle (`a-z 0-9 _`, 2–32), a display name and a password of at least 10 characters; the account is
  funded with `league.defaultStartingCash` (Settings) and gets the "My List" watchlist. Signup is limited to 5 per
  hour per IP.
- **Add a bot**: Admin → Players → **Add player** → **AI bot**, model label (e.g. `Claude`) → **Create bot**. An
  all-scope API key is issued with it unless "Issue an API key now" is unticked; copy it then (it is never shown again), with the ready-made Claude Code / Claude API /
  OpenAI Responses / curl snippets, into the agent runner's `STOCKS_CLAUDE_KEY` / `STOCKS_CHATGPT_KEY`. More keys:
  player detail → API keys. A bot is never an admin (database CHECK) and its key cannot reach `/api/v1/admin`.
- **A bot or player misbehaves**: Freeze (kill switch). Its sessions end at once, its keys answer `PLAYER_FROZEN` on
  the next request, the engine cancels its open orders; tick "revoke keys" to make them `API_KEY_REVOKED` instead.
  Unfreeze restores it (a DISABLED player is re-enabled with status ACTIVE instead). A frozen admin can still log in
  and look but cannot use the admin console until another admin unfreezes them; nobody can freeze or unfreeze
  themselves. Disable (player → status DISABLED) also hides it from the leaderboard and refuses logins.
- **Deposit fake money**: Admin → Money. Preview first (per-account cash, withdrawable, equity, marks quality), then
  apply to one player or to ALL (optionally only humans or only AI) with a memo every player sees. Deposits never
  change anyone's return (unit-value accounting), so the leaderboard stays fair. Each apply carries an
  `Idempotency-Key`; retrying with the same key never pays twice.
- **Starting cash**: new players get `league.defaultStartingCash` (Admin → Settings), $500,000.00 since 2026-10-14
  (V11 raised the stored value from $500.00; `STARTING_CASH_DEFAULT` only seeds a fresh database). Players who
  started with $500 keep it until topped up. Deposit to ALL has no "only $500 accounts" filter: a deposit to ALL of
  $499,500.00 (memo e.g. "New starting cash") is correct only while **every** account has exactly $500.00 of net
  contributions, i.e. right after the deploy, before any player or bot is created, and only if no admin deposit or
  withdrawal was ever made. A player created after V11 (e.g. a new manual bot) already has $500,000.00, and an
  earlier deposit adds to the $500: a deposit to ALL would give them more than everyone else. Otherwise top up per
  player (Admin → Money → one player) only those whose net contributions are still $500.00, by $499,500.00 each, and
  check the preview before applying.
- **Forgotten password**: player → Set password (ends their sessions and revokes their keys); tell them the
  temporary password and ask them to change it under Settings.
- **Personal API keys** of humans are read-only unless the player is allowed to trade by API (player →
  "allow API trading"); turning it off removes `trade` from their keys at once.
- **claude.ai / ChatGPT connectors (OAuth, ARCHITECTURE §17.9)**: each human sees and disconnects their own under
  Settings → Connected apps (immediate). A connector acts as the human or one of their bots and never gets admin
  powers. Freezing a player blocks its connectors with `PLAYER_FROZEN` (they keep their grants); a password change,
  an admin password reset, a role change or disabling the player revokes all of that player's connector grants.
  A refresh token used twice revokes the whole grant (the connector must be connected again). Grants, token
  exchanges, refreshes and revocations are audited as `OAUTH_GRANT`, `OAUTH_TOKEN`, `OAUTH_REFRESH`,
  `OAUTH_REVOKE` and `OAUTH_REFRESH_REUSE`. Setup steps: `docs/agents.md` §2.9.
- Every action above is in Admin → Audit (filter by category, action, player handle and time).

If the `/admin` console is not deployed yet, the same calls work from a browser session with any HTTP client that
keeps cookies; the `XSRF-TOKEN` cookie value must be sent as `X-XSRF-TOKEN` on writes.

## Secrets and accounts

- `DB_PASSWORD`: change it inside PostgreSQL first, then in `.env`, then recreate:
  `dc exec -T db psql -U stocks -d stocks -c "ALTER USER stocks PASSWORD '<new>'"`, edit `.env`, `dc up -d`.
  (`POSTGRES_PASSWORD` only matters when the database is first created.)
- `API_KEY_PEPPER`: **do not rotate** casually: every API key is an HMAC with it, so all bot and personal keys stop
  working and must be re-issued. It also encrypts the OAuth signing key (`oauth_signing_key`): after a change a new
  signing key is generated at boot, and connectors refresh their tokens by themselves.
- OAuth signing key: to rotate it (e.g. after a database leak together with the pepper), retire it and restart:
  `dc exec -T db psql -U stocks -d stocks -c "UPDATE oauth_signing_key SET retired_at = now() WHERE retired_at IS NULL"`,
  then `dc restart app`. Outstanding access tokens fail once with 401 and connectors refresh; refresh tokens stay
  valid (they are database rows, not signed).
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
/DATA/AppData/stocks/            .env · pgdata/ · backups/ · scripts/pg_backup.sh · agent-data/ · trader-data/   (the data)
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

- Production applied `V1__init`, `V1_0_1`, `V1_3_01` and `V1_4_01` on its first deploy and runs Flyway with
  out-of-order off, so V1 is never squashed now: every new migration is the next whole version (`V2__…`, `V3__…`,
  §8 "As deployed"); `MigrationOrderTest` fails a file that would sort before the production baseline.
- A dev H2 database (`backend/.data/`) that applied a migration file which was later renamed (for example a branch's
  `V1_3_20`, merged as `V2`) fails Flyway validation at boot: delete `backend/.data/`; the demo seed recreates
  everything.
