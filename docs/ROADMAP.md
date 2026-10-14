# stocks: Roadmap

> **Companion to** [`ARCHITECTURE.md`](ARCHITECTURE.md), which is the authoritative spec. Section references (§) point there.
> **Date:** 2026-10-09; Phase 2 wave A integrated 2026-10-11.
>
> **Phases:**
> - **Phase 0:** scaffold and contracts
> - **Phase 1:** MVP, a real end-to-end league that friends and bots can play
> - **Phase 2:** full realism and connectors
> - **Phase 3 and later:** deep realism and extras
>
> Each Phase 1 box belongs to the workstream in brackets: WS1 Platform/Identity/Admin · WS2 Market Data · WS3 Trading Core · WS4 Portfolio/League · WS5 Agents/Realtime · WS6 Frontend.

---

## Phase 0: Scaffold and contracts (Day 0–2)

- [x] [WS1] Maven Wrapper 3.9.11, `pom.xml` on Boot 4.1.1 with every Phase 1 dependency pinned (§2.1), including `micrometer-registry-prometheus` and zonky `embedded-postgres` 2.2.2 with binaries 18.6.0. Upgrade the local JDK to Temurin 21.0.12+ (still open: the build is verified on the owner's local Oracle JDK 21.0.1; CI uses the latest Temurin 21).
- [x] [WS6] Vite 8 React 19 TypeScript 6.0 app with every Phase 1 dependency pinned (§2.2), **the `openapi-typescript` → `$typescript` override, and the committed `package-lock.json`**; Tailwind 4 with design tokens (§22.8); `index.html` with `viewport-fit=cover`, manifest and icons (§22.3)
- [x] [WS1] `.gitignore` additions, `.dockerignore`, `.editorconfig`, `.env.example`, `Makefile` (`dev`, `test`, `test-pg`, `e2e`, `openapi`, `jar`, `image`, `compose-check`)
- [x] [WS1] Flyway `V1__init.sql` plus vendor extras **verbatim** from §8, green on H2 **and on embedded PostgreSQL 18.6** (`make test-pg`, no Docker). The original V1 was verified on PG 18.6 on 2026-10-09 (§28 #19); this run re-verifies the v1.1 additions.
- [x] [WS1] `common/**`: enums, `ErrorCode` (incl. `NOT_IMPLEMENTED`, `INTERNAL_ERROR`), `PublicId` (ULID), `TimeSource`/`AdjustableTimeSource`, **`Jdbc` (no raw `Instant` on JDBC)**, `Money`/`Rounding`, events, problem details, Jackson (decimals as strings)
- [x] [WS1] Contract interfaces and records from §5.3 in `com.stocks.contract.*`, every DTO from §18.3 as a `…Dto` record, the job SPI (`ScheduledJob`, `PeriodicTask`), and stub beans through `StubsAutoConfiguration` (`NoopMarketDataService` as the market-data default)
- [x] [WS1] Security skeleton with dev login; SPA fallback; ArchUnit rules (§5.2, including the cycle and JDBC rules); test kit (`MutableTimeSource`, `ScriptedMarketData`, fixtures, `EmbeddedPgContextInitializer`); `JobScheduler` skeleton
- [x] [WS3] `DirectEngineExecutor` in WS3 test sources (week 1, with the engine)
- [x] [WS1] CI (`ci.yml`, GitHub-hosted): backend on H2, backend on embedded PG, frontend, OpenAPI drift and assertions (no `type: number`, response properties required), e2e (once specs exist), compose/CasaOS checks + shellcheck + actionlint, multi-arch image build without push (§26). `OpenApiExportTest` drives `make openapi` (§26 step 4). Every job's commands pass locally (the image job needs Docker); the first GitHub run is still pending.
- [x] [WS1] Release (`release.yml`, §24.7): CI → multi-arch image (`linux/amd64`, `linux/arm64`) pushed to GHCR (`latest`, `sha-<7>`, semver) with SBOM and provenance → deploy job on the owner's self-hosted CasaOS runner (`[self-hosted, casaos]`, gated by `DEPLOY_ENABLED`), health-gated with automatic rollback; manual deploy/rollback of any tag through `workflow_dispatch`
- [x] [WS1] Self-hosting kit: production `Dockerfile`, CasaOS-importable `docker-compose.yml` (`x-casaos` metadata, `/DATA/AppData/stocks` bind mounts), `deploy/scripts/{init-host,deploy,restore}.sh`, `deploy/backup/pg_backup.sh`, `deploy/cloudflared/config.example.yml`, `docs/deploy.md`, `docs/runbook.md`
- [x] [WS5] **Spike:** Spring AI 2.0.1 STATELESS MCP `whoami` through the overridden transport's `contextExtractor`, with Boot's `JsonMapper` as `mcpServerJsonMapper` (§20.1, §28 #13 and #20)
- [ ] [all] Every workstream reviews and signs off the contracts PR

---

## Phase 1: MVP, the playable league (target: about 5 weeks, milestones M0–M4)

### WS1: Platform, identity and admin
- [x] Human auth: JSON login and logout, Spring Session JDBC, `__Host-` cookie, CSRF (SPA mode), lockout, per-IP login limit
- [x] Invite-only signup (`/auth/signup`) that funds a $500 account and seeds the "My List" watchlist (through the WS3 `AccountService` / WS4 `WatchlistService` contracts; `AccountBackfillRunner` opens missing accounts at boot)
- [x] API keys `stk_<id>_<secret>`: HMAC with pepper, scopes, 60 s cache, revoke, `last_used_*`; personal keys for humans (session + `@RecentAuth`, read-only unless the admin allows API trading, revoked on password change)
- [x] Bearer filter (key path ignores cookies); `/mcp` returns 401 with `WWW-Authenticate`
- [x] Per-player Bucket4j rate limits (reads, heavy reads, writes 20/min and 300/day) with headers and 429 + `Retry-After`; `RateLimitService` (write bucket charged only by `OrderGate`, replays free)
- [x] `RequestMeta` bounds client strings; `X-Request-Id` validated or regenerated; `audit_log.request_id` 64
- [x] `AdminSessionOnlyFilter` (403 on any `Authorization` header), `@RecentAuth` plus `/auth/reauth`
- [x] `AuditService` and `audit_log`, covering every category in §17.7 (ORDER rows are WS3's `OrderGate`)
- [x] `SettingsService` (`app_setting`) with the §23.4 defaults; `STARTING_CASH_DEFAULT` seeds `league.defaultStartingCash`
- [x] Bootstrap admin from env vars; idempotent dev demo seed (admin `admin-dev-123`, alice, bob, claude, chatgpt, with keys written to `backend/.data/dev-keys.txt`)
- [x] Admin REST (§21.1): players (create human or AI + key; detail; break-glass live view, removed in public mode for `GET /admin/players/{h}/portfolio`; disable; freeze/unfreeze with optional key revocation; promote human; allow API trading), keys, invites, **cash transfers (preview; single and ALL; memo; `Idempotency-Key` checked inside the engine; mark-quality gate)**, resets, admin order cancel, settings, audit, system, engine halt/resume (`EngineControl`), calendar overrides (`CalendarAdmin`), corporate actions (list, resolve)
- [x] Freeze/disable step 1 (status, sessions, caches, optional key revocation, `PlayerStatusChanged`) then `AccountService.restrict` (§10.5)
- [x] `SingleInstanceGuard` (PG advisory lock or H2 lease with dead-PID takeover; throws instead of exiting; disabled in tests), `JobScheduler` (job SPI, registry), `JobRunGuard`, `CatchUpRunner` (catch-up policies), `StartupGate`
- [x] `TestClockController` (`@Profile("test")`, prod fail-fast) for e2e
- [x] Security headers, cache headers, SPA fallback, forwarded headers (§24.3)
- [x] First real deploy to the owner's CasaOS server: `https://stocks.gehan.cloud` runs `ghcr.io/rjgehan/stocks:latest` under **Watchtower** (public package, `/DATA/AppData/stocks/.env`, no host port; the `stocks-cloudflared` sidecar routes the tunnel to `stocks-app:8080` over `stocks-edge`, WebSockets included); production applied V1, V1_0_1, V1_3_01 and V1_4_01, so V1 is never squashed and new migrations are `V2…` (§8 "As deployed", `MigrationOrderTest`); the runbook covers Watchtower deploys and pinning, engine halt/resume and bootstrap halts, invariants and I10 warnings, corporate-action resolve, the agent runner, Alpaca 406 and restore
- [ ] Optional self-hosted runner path (`release.yml` deploy job: runner as a systemd service, `DEPLOY_ENABLED`, a deliberate failed deploy that rolls back): documented, not exercised. *Needs the owner* (only if health-gated deploys are wanted over Watchtower)
- [x] OpenAPI rules (§18): BigDecimal as decimal strings, required + nullable record components, wire-value enums, `Dto` suffix stripped
- [x] `AdminSecurityIT`: every admin route rejects anonymous callers, bot keys, the admin's own key and non-admin sessions
- [x] Housekeeping job (§25, 02:00 ET); `eventsExecutor` (§5.3) and the engine-event audit listener

### WS2: Market data and calendar
- [x] `MarketCalendar` with the bundled `xnys-2016-2028.csv` (generator plus the §9.2 golden dates), Alpaca calendar sync, `CalendarAdmin` overrides, and the in-memory `AlwaysOpenCalendar` for dev and e2e
- [x] **SIM provider by end of week 1**: deterministic paths, quotes, prints, bars; clock modes; scenarios (halt, gap, blackout, one-sided quote, split, dividend, flash crash)
- [x] `QuoteBook` (fan-out through `List<MarketUpdateListener>`), `MarketDataService`, `ExecutableQuoteBuilder` (anti-look-ahead, freshness, quality ladder with tiered IEX spread limits 10/40/150 bps, clamp, synthetic quote); halts only from `statuses`
- [x] DTO mappers (`QuoteDtoMapper`, `BarDtoMapper`, …) shared by REST, STOMP and MCP
- [x] Alpaca REST: assets, calendar, snapshots, latest quotes and trades, bars, news, movers, most-actives, corporate actions, logos
- [x] Alpaca IEX stream: auth, subscribe diffs, 405/406 handling, backoff (90 s after a 406), watchdog, `statuses`
- [x] Finnhub: `profile2`, `metric`, `/stock/symbol` types, search; SIP trade stream with heartbeat watchdog
- [x] `SubscriptionBudgetManager` (leases, priorities, hysteresis, ≤ 30 Alpaca and about 50 Finnhub symbols), `SnapshotPoller`, `QuoteRefresher`, `ProviderRateLimiter` (180/min with priority classes)
- [x] `BarService`: SIP history ≥ 16 minutes old + IEX tail + live aggregation; range defaults; caching
- [x] `MarkService` (live marks, split-adjusted fallbacks) and `DailyMarkJob` (16:20 official close; 20:20 day-end marks; re-marking PROVISIONAL rows)
- [x] `SplitAdjustments` (`price_adjustment`) and `SplitGuard` (04:01 and 09:20; corporate-action hold through WS3's `flagDetectedSplit`/`symbolsOnHold`); `CorporateActionSource` bean for WS3's sync
- [x] `AssetSyncJob` (exchanges filter, ETF type, liquidity tiers, search index), `PrevCloseJob`, movers with a computed fallback, news, fundamentals
- [x] `AlpacaCapabilityProbe`, with results on `/admin/system` (via `MarketDataService.status()`; the second-connection stream checks are skipped to avoid 406, §15.8); prod refuses SIM unless overridden
- [x] Market REST: `/market/clock|calendar|indices|movers`, `/assets/search|{symbol}|logo`, `/quotes`, `/snapshots`, `/bars`, `/news`

### WS3: Trading core
- [x] `TradingEngine`: single thread, bounded queue, one transaction per command, events after commit, sweep, dirty-symbol drain
- [x] `OrderGate` (checks 1–6, one write token, free replays) and pure `OrderValidator` (checks 7–27, authoritative on the engine thread), `HoldCalculator` (102% and 115% cushions), `SessionOrderMatrix`, `ExpiryCalculator` (DAY, GTC 60 days, early closes)
- [x] `OrderStateMachine`, table-driven and exhaustively tested
- [x] Order types: **MARKET** (with collar), **LIMIT** (+ extended hours), **STOP**, **STOP_LIMIT**, **TRAILING_STOP** (DAY; entered in REGULAR only) — all five accepted since M2 (§11.1); golden scenarios in `StopOrderScenarioTest`, every validation path in `StopOrderValidationTest`
- [x] **Fractional and notional market orders** (RTH, DAY, ≥ $1, 5 dp, notional all-in including fees)
- [x] `FillModel`: touch, book walk, partial fills by displayed size, resting fills at L, trade-through, opening and reopen semantics, halts
- [x] `TriggerEvaluator`: stop triggers on consolidated prints with a bid/ask fallback, gap at open, trailing watermark (persisted ≤ 1/s, `TRAIL_UPDATED` ≤ 1 per 10 s), halts, no retroactive trigger after a restart (§12.7); stop property test (I1–I9, holds never negative)
- [x] `PostingService`: execution, ledger row (net of fees), FIFO lots, position, realized P&L, order update, events
- [x] `FeeCalculator`: effective-dated, cumulative per-order delta charging; golden table (§13)
- [x] `BalanceCalculator`: settled/unsettled (T+1 derived), settled available, withdrawable, buying power by restriction
- [x] `GfvService`: funding attribution, detection, 3 → SETTLED_CASH_ONLY and 4 → LIQUIDATION_ONLY escalation, expiry, `GFV_RISK` warning predicted on `session_date`, `GFV_ACK_REQUIRED`
- [x] Idempotent `clientOrderId` (replay 200, 409 on conflict); synchronous rejects return 422, are not persisted, and are audited
- [x] Cancel, cancel-all, replace (whole-share unfilled only), close position
- [x] Accounts: open (INITIAL transfer, units), reset (run n+1 at the final U, in-engine idempotency), `restrict`/`liftAdminRestriction`, `recordAdminView`; `ApplyCashTransfersCmd` (in-engine idempotency, mark-quality gate, valuation at flow, units at scale 18, empty and rebase cases, all-or-nothing)
- [x] `account_event` and `position_adjustment` (append-only); activities merged from `cash_ledger` and `account_event`; `AdminOrderService`, `EngineControl`, `CorporateActionQueries`/`Service`
- [x] Session transitions (PRE_OPEN, RTH_OPEN, RTH_CLOSE, POST_CLOSE) with catch-up and no retroactive fills
- [x] Corporate actions: sync, forward and reverse splits (per-lot `SPLIT_QTY`, position = Σ lots, dust and cash in lieu, cancel orders; golden 2:1, 3:2, 1:3, 1:8), cash dividends (accrue on ex-date, pay on payable date, `voided_at`), name changes (incl. `WatchlistService.renameSymbol`); others go to NEEDS_ADMIN; admin resolve (jobs `CORPORATE_ACTION_SYNC`, `CORPORATE_ACTION_APPLY`, `PAY_DIVIDENDS`; Alpaca `/v1/corporate-actions` with paging; `CORPORATE_ACTION` notifications and admin alerts; leaderboard continuity golden `split_continuity.json`)
- [x] `InvariantChecker` (I1–I9) with auto-halt; `DailyMaintenanceCmd`; `FillQualityAudit` (I10, warnings: `FILL_QUALITY_AUDIT` periodic task, `SYSTEM/FILL_QUALITY_WARNING` audit rows)
- [x] REST: `/orders/**`, `/orders/preview`, `/account`, `/account/positions/**`, `/account/activities`, `/account/executions`, `/account/violations`, `/account/runs`, `/account/reset`

### WS4: Portfolio and league
- [x] `UnitMath` (Day 2) and `ValuationService` (end of week 1; reads through the caller's transaction)
- [x] Unit-value accounting golden vectors (§16.2), including the deposit-to-ALL case
- [x] Golden vectors for tiny equity, rebase, reset continuity and dividend continuity (§16.2); point-in-time receivable (§16.1)
- [x] `HoldingsReconstructor` (forward replay of executions + `position_adjustment`); `EodSnapshotJob` (20:20 ET; `equity_snapshot`, `snapshot_holding`, feed backfill); `IntradaySampler`
- [x] SPY total-return benchmark (B = 1 at 2025-12-31, `BenchmarkBackfillJob`, intraday anchors from `spy_mark`), excess return, max drawdown
- [x] `LeaderboardService`: per-player series across runs (a reset never erases a loss); periods TODAY, WEEK, MONTH, YTD, SEASON, ALL × metrics RETURN, PNL × kinds ALL, HUMAN, AI; eligibility (5 fills / 2 symbols); ties; Arena; 5 s live recompute; `leaderboard_snapshot` for rank arrows; equity hidden under HIDDEN (public mode: equity and top holding are always shown); `rebased` and `apiTrader` flags
- [x] Day P&L (account and position) with the same anchors as the board (`PerformanceQueries.accountPnl`, `positionDayPnl`, name-change mapping)
- [x] Player profiles: stats per period, deposits, resets, EOD-weight holdings, delayed trades (rationales only for AI or shared notes; "via API" label). **Replaced by public mode** (2026-10-14): live portfolio, order history, activity and P&L tabs, holdings with quantities, every new note public
- [x] League feed: FILL, POSITION_CLOSED, NEW_LEADER, RESET, DEPOSIT, JOINED, published after the visibility delay; `FeedEventListener` is the sole writer, idempotent by `source_ref`. Public mode: the delay defaults to none (`PT0S`); the publisher looks back 15 s and dedupes
- [x] Watchlists CRUD; `WatchlistService.seedDefault` and `renameSymbol`
- [x] REST: `/leaderboard`, `/players/**`, `/feed`, `/account/history`, `/watchlists/**`

### WS5: Agents and realtime
- [x] STOMP `/ws`: session auth, CSRF on CONNECT (raw cookie token, non-masking `csrfChannelInterceptor`), heartbeats, subscription caps, leases (quote and `bars.{SYM}.1Min` → VIEWING, tick → LIST)
- [x] `QuoteFanout` as a `MarketUpdateListener` (≤ 4/s and ≤ 1/s), bars topic, session topic, leaderboard signal, feed topic (the leaderboard signal and feed topic are published by WS4's `LeaderboardServiceImpl` and `FeedService`, §19)
- [x] `UserEventPublisher` (`/user/queue/orders|account|notifications|admin`) and `NotificationService` (sole writer, idempotent by `source_ref`) with `/me/notifications` (`/user/queue/admin` carries `AdminAlertRaised`, e.g. a corporate action that needs the admin)
- [x] `RulesService` and `GET /rules` (the bots' source of truth, versioned); the order matrix publishes all five order types since M2
- [x] MCP server (STATELESS, `/mcp`): `McpConfig` (Boot `JsonMapper` as `mcpServerJsonMapper`, transport override with `contextExtractor`, output schemas with decimal strings); all 38 tools of §20.3 with explicit hints, fixed order, snake_case inputs, the `untrusted` envelope, stable error codes, rate limits, audit; `mcpClient` from `X-Stocks-Client` / last `initialize` / User-Agent
- [x] MCP tests: `tools/list` snapshot, identity, JSON contract (decimal strings, nulls, `outputSchema` validation), tool coverage vs `docs/openapi.json`, idempotent `place_order`, `RATIONALE_REQUIRED`, no admin tools (idempotent replay, `RATIONALE_REQUIRED` and rate limits are WS3/WS1 behavior passed through; covered with fakes)
- [x] **Agent runner** (`tools/agent-runner/`, §20.5): compose service `agent-runner` (profile `agents`, image `stocks:agent-runner-<tag>`); one model-agnostic loop for Claude and ChatGPT; same prompt, tools and schedule (09:45, 12:30, 15:30 ET); per-run tool-call and time caps; per-bot daily dollar cap (built-in price table, `AGENT_PRICE_*`); notes-file memory; JSONL run log; `--once` CLI; tested against fake LLM APIs and the real MCP server (CI). Owner steps to go live: docs/agents.md §2.5
- [x] `docs/agents.md`: agent runner, Claude Code, Claude API MCP connector and OpenAI Responses recipes; optional claude.ai custom connector with "No sign-in" + Request headers (beta); why there is no ChatGPT-app or GPT-Actions route in Phase 1; the shared bot system prompt; `X-Stocks-Client`
- [x] `docs/rules.md`: the player-facing rulebook (mirrors `/rules`, with [V]/[U] notes)
- [ ] **Live verification:** a Claude bot (Claude Code, API key) and a GPT bot (Responses API, API key) each place, inspect and cancel orders on the deployed instance. *Needs the owner* (real LLM keys and the bots' `stk_…` keys in the server's `.env`; steps in docs/agents.md §2.5; locally verified with scripted fake LLMs against the real MCP server)

### WS6: Frontend
- [x] Shells: desktop 3-pane (rail, watchlist, center, ticket, bottom dock), tablet drawer and side sheet, mobile bottom tabs, pushed screens and bottom sheets; safe areas (`viewport-fit=cover`); static manifest, apple-touch-icon and theme-color (installable, no service worker); 44 px targets
- [x] TopBar: ⌘K search, session chip with countdown, PAPER and SIM badges, NAV hero, notification bell; data-source footer
- [x] Watchlist: lists, Holdings list, streaming rows with flash, % pill cycling, "Add 5 popular" empty state, index strip
- [x] Quote page: sticky header with the extended-hours line, key stats, Lightweight Charts (candles/area, ranges 1D–ALL, intervals, volume pane, previous-close line, crosshair), bid/ask bar, my position and my orders
- [x] Trade ticket and review sheet (§22.5): Shares/$ toggle, price chips, 25/50/Max, TIF, extended hours, live preview, GFV acknowledgement, note with "Share note publicly", idempotent submit; STOP, STOP_LIMIT and TRAILING_STOP (offered when `GET /rules` lists them: stop/limit/trail inputs, $/% trail, the stop's description, matrix-driven TIF), orders and order detail with the stop panel (live watermark), TRIGGERED / TRAIL_UPDATED timeline lines
- [x] Account: NAV hero, balances grid (settled/unsettled/withdrawable/BP/holds/net contributions), GFV card, equity curve vs SPY, positions, orders and order detail (timeline, fills, fees, quote used), activity (deposit memos; dividends accrued/paid/void, splits with cash in lieu, symbol changes, GFV, restriction, reset and admin-view events), runs
- [x] Markets: index cards and movers tabs, plus news when available
- [x] League: period, metric and kind controls, ranked and unranked rows, deposit chips, Arena card (median human vs median AI vs SPY, the leader, each team's best), formula popovers per column, my pinned row, feed view (filters, day groups, live `/topic/feed` items); player profiles
- [x] Settings: theme, red-up/green-up, confirmations, defaults, personal API keys, self-reset
- [x] Admin: players, player detail (EOD by default, audited break-glass live view; public mode replaced it with the live portfolio and Cancel), money (preview with mark quality, degraded-marks override, typed-amount confirmation), invites, settings, system (jobs registry with "Run now" and an optional run key, calendar overrides, halt/resume), corporate actions (list by status, resolve APPLY/SKIP with rates and an audited note behind `@RecentAuth`), audit
- [x] Generated API client, STOMP hooks, `quoteStore`, mock mode (`VITE_API_MOCK=1`)
- [x] Playwright e2e at 390×844, 768×1024 and 1440×900 against profiles `test,e2e`, with the `advanceClock()` helper (§18.5, §26); Phase 1 specs for stop orders (mock: trigger and fill; real backend: arm and cancel, skipped until `GET /rules` lists STOP), the league feed and arena, and the admin corporate-action resolve (mock)

### Milestones

- [x] **M0 Boot (end of week 1):** H2 + SIM boot, login, `/quotes` on SIM, SPA shell on both layouts, MCP `whoami` spike passes
- [x] **M1 Trade on SIM (week 2):** MARKET (shares and notional) and LIMIT orders end to end, ledger and positions, account page, ticket with preview, live order events. **Friends can trade on SIM.** Integrated on main as "M1+" (owner-adjusted): also live Alpaca/Finnhub market data, invites and signup, admin players/keys/deposits, watchlists, the deposit-aware leaderboard, STOMP and MCP.
- [x] **M2 Live and bots (week 3):** all 5 order types, fees, T+1, GFV; Alpaca live data; charts; MCP tools with API keys; agent runner trading both bots on SIM; admin players, keys and transfers. Integrated on main 2026-10-10 (p1f/stops, p1f/runner); the runner's SIM trading is verified with scripted fake LLM APIs (real LLM runs need the owner's keys)
- [x] **M3 League (week 4):** EOD snapshots, TWR and P&L leaderboard, profiles, feed, corporate actions, Finnhub, Markets, Settings. Corporate actions integrated on main 2026-10-10 (p1f/corp), with the league arena, live feed and admin corporate-action screens (p1f/frontend)
- [ ] **M4 Ship (week 5):** hardening, e2e green on three viewports, no Day-0 stubs left, deployed to the owner's CasaOS server (owner decision: Watchtower pulling `:latest`; the `release.yml` runner deploy stays optional) behind the owner's Cloudflare Tunnel with the §24.5 settings verified from a cloud VM, agent runner live (`--profile agents`). Done: e2e green on three viewports, no Day-0 stub beans, deployed under Watchtower behind the tunnel. *Needs the owner:* the §24.5 Cloudflare settings (WebSockets, cache bypass, Bot Fight Mode off, AI Crawl Control, Access on `/admin*`) checked from a cloud VM, and the agent runner started with real LLM keys (docs/agents.md §2.5)

### Phase 1 exit criteria

- [ ] *Needs the owner and the league:* at least 3 humans (one on a phone) and 2 AI bots (Claude, ChatGPT, both driven by the agent runner within their daily caps) trade for **5 consecutive trading days** on live IEX data with $500,000 starting cash (the default since 2026-10-14; it was $500).
- [ ] Every order type works in its allowed sessions. Fractional and notional orders work in RTH. Settled vs unsettled cash and GFV warnings behave as specified.
- [ ] An admin **deposit to ALL** shows up in each player's activity with its memo and **does not change anyone's TWR**, including an account below $1. The leaderboard ranks by TWR and by net-contribution P&L, and a reset does not erase a period's loss.
- [ ] Invariants I1–I9 are green every night. No retroactive fills after a restart. (Covered by tests and the property suites; to be observed on production during the 5-day run)
- [x] A bot can never reach an admin endpoint (`AdminSecurityIT` green).
- [ ] Backups run nightly (and before every deploy), are copied off the server, and a restore (`deploy/scripts/restore.sh`) has been rehearsed once. *Needs the owner:* the off-server copy and the restore rehearsal on the server (docs/runbook.md "Restore"; under Watchtower there is no pre-deploy dump, only the nightly one)

---

## Phase 2: Full realism and connectors

**Wave A (integrated on `main` 2026-10-11):** group orders, FINRA 5330, cancel latency; league stats, race chart,
reactions, P&L Analysis; price alerts, the full PWA and Web Push; chart indicators, Time & Sales, news and analyst
ratings, watchlist columns, the screener; the OAuth 2.1 server for the claude.ai and ChatGPT connectors; agent run
reports, the SSE stream, the API access log and the public AI journal. Migrations V3–V8 (+ vendor V8_1), MCP tools
#39–#55 (55 in all). **Left for later waves:** margin (and leveraged-ETF requirements), seasons, halts/LULD and
circuit breakers, the official opening print, engine replay, passkeys, the manual merger/delisting/spin-off tool,
and the live verification of both connectors (owner).

**Public mode (2026-10-14):** the league becomes a public experiment (ARCHITECTURE §16.7). Every player's live
portfolio, full order history with fills, account activity, P&L analysis, rationales and journal are visible to every
player (five `/players/{h}/…` routes, MCP tools #56–#60, 60 in all; profile tabs Overview · Portfolio · History ·
Analysis). The feed delay stays as a setting but defaults to none, `social.holdingsVisibility` and the break-glass view
are gone, every new note is public, and `V9__public_mode` moves existing leagues over (with a one-time notice).

### Owner actions after the wave A deploy

Nothing is required for production to keep booting: no new environment variable is required, the six migrations
apply by themselves, and the VAPID key pair and the OAuth signing key are generated on first boot and stored in the
database. To turn the new features on for real:

1. **Watch the first boot** (`docker logs -f stocks-app`): Flyway applies V3–V8 in one go; then "Generated the Web
   Push VAPID key pair" and "OAuth signing key generated and stored encrypted" appear once. Admin → System:
   `BENCHMARK_BACKFILL [2025-12-31:QQQ]` should be DONE (if FAILED, use **Run now**; until then the QQQ line in
   P&L Analysis is blank) and `SCREENER_STATS` runs every minute (market caps fill in over about 3.5 hours).
2. **Cloudflare:** no challenge, Access policy or cache on `/oauth2/*`, `/.well-known/*` and `/api/v1/stream`; Bot
   Fight Mode off (§24.5). If a "Cache Everything" rule exists, exclude `/sw.js` and `/index.html` so app updates
   reach installed PWAs. Check from outside:
   `curl -s https://stocks.gehan.cloud/.well-known/oauth-protected-resource/mcp` returns
   `"resource":"https://stocks.gehan.cloud/mcp"`.
3. **Connect claude.ai** (signed in as the admin, docs/agents.md §2.9): Customize → Connectors → Add custom
   connector, URL `https://stocks.gehan.cloud/mcp` ("Use Claude's published identity", or leave the OAuth fields
   empty), Connect, then **Act as @claude** on the consent screen.
4. **Connect ChatGPT** (same section): `https://chatgpt.com/plugins` → **+** → **Add custom MCP server** (older
   accounts: Developer mode → Apps & Connectors → Create), same URL, authentication OAuth, then **Act as @chatgpt**.
   Then tick the connector item below.
5. **Web Push on a phone:** iPhone (iOS 16.4+): Safari → Share → Add to Home Screen, open stocks from the Home Screen,
   Settings → Notifications → Enable on this device → "Send a test notification". Android/desktop: the same from
   Settings → Notifications. (No real push service has been called in testing; this is the first live check.)
6. **Agent runner:** make sure it runs the new image (`docker compose -p stocks --profile agents up -d`, or let
   Watchtower pull `agent-runner-latest`), then check Admin → Bots after the next scheduled run.
7. **Optional:** `curl -N "https://stocks.gehan.cloud/api/v1/stream?topics=session" -H "Authorization: Bearer $KEY"`
   starts with `retry: 3000` and `event: hello` and sends `: hb` every 15 s through the tunnel. Set `FINNHUB_TOKEN`
   if it is not set yet (analyst ratings and market caps need it). `STOCKS_RULES_CANCEL_LATENCY_ENABLED=true` turns
   on cancel latency (off by default).

### Connectors and agents
- [x] OAuth 2.1 Authorization Server: PKCE S256, CIMD + DCR, protected-resource metadata, `iss`, rotating refresh tokens, and the **act-as consent** screen (§17.9; in-app `com.stocks.oauth`, Settings → Connected apps)
- [ ] claude.ai custom connector (OAuth) verified, and the ChatGPT developer-mode connector verified (check plan eligibility). If the owner wants the ChatGPT app in Phase 1, this OAuth work moves up (owner decision, §20.4).
- [x] Agent runner: `agent_run` table and an admin UI over the Phase 1 runner (§20.5, §20.6: `POST /agent-runs` with the bot's own key, Admin → Bots, Bot activity on profiles)
- [x] SSE stream `/api/v1/stream` (quotes, orders, account, session, leaderboard, feed) with `Last-Event-ID` (§19.3; API keys only)
- [x] `api_access_log` (reads and writes, hourly buckets, 90-day retention) and the "recent API calls" view in Settings (§17.10)
- [x] `add_journal_note` MCP tool, and a public AI trade journal (the Phase 1 runner keeps private notes files) (§20.7: `JOURNAL` feed items, Journal card on bot profiles)

### Trading realism
- [ ] **MARGIN accounts**, offered once equity is at least $2,000 (an admin opt-in per player or league): long-only, intraday buying power 4 × margin excess, overnight 2 × Reg T excess, the Webull maintenance table, IMD/RM/RT calls with due dates, auto-liquidation, strikes
- [x] **Bracket (TP/SL, ≥ 0.1% apart), OCO, OTO, OTOCO** group orders (§11.9: `/order-groups`, MCP `*_order_group`, the ticket's Advanced mode, linked orders; golden `GroupOrderScenarioTest`)
- [ ] **Official opening print** fills (SIP condition codes) and halt reopen prints
- [ ] Halt feed and LULD bands (Nasdaq Trader halts RSS [VERIFY]); market-wide circuit breakers
- [x] FINRA 5330 order adjustments on ex-dividend dates (§14; `doNotReduce`)
- [ ] Admin tool for corporate actions: cash and stock mergers, delistings (force-close at a price), spin-offs as manual events
- [x] Cancel latency (`PENDING_CANCEL`, 150 ms + jitter) behind a flag (§11.10, `stocks.rules.cancel-latency.enabled`, off by default)
- [ ] Engine input log (NDJSON) with bit-for-bit replay; `ReplayMarketDataProvider` and `MarketTapeRecorder`
- [ ] Leveraged-ETF requirements (2x/3x) for margin

### League and fun
- [ ] **Competitions / seasons** (`league`, `league_member`, DEDICATED accounts, deposit policies `NONE` / `UNIFORM_ONLY`, join rules, end-of-season standings and badges)
- [x] Sharpe ratio (≥ 20 days), profit factor, average hold time, best and worst trade (§16.8; profile League stats card, optional leaderboard columns)
- [x] Race chart (everyone's unit-value curves), rank-change history (§16.9; League → Race)
- [x] Reactions on feed items (§16.11; six emoji, live on `/topic/feed`)

### Product (Webull parity, part 1)
- [x] P&L Analysis: My Earnings (return vs SPY/QQQ), P&L calendar (day/month), P&L distribution by symbol (§16.10; Account → Analysis)
- [x] Alerts: price ≥/≤, % change; full PWA (vite-plugin-pwa 1.3.0: service worker, offline shell) with Web Push for fills and alerts (the static manifest ships in Phase 1). Built in Phase 2 wave A: §19.1 price alerts (repeat, expiry, live-only evaluation, MCP `list_alerts`/`create_alert`/`delete_alert`), §19.2 Web Push (RFC 8291/8292 on JDK crypto, VAPID key generated on first boot, five categories, test push) and §22.10 PWA
- [x] Chart indicators: MA, EMA, BOLL, VWAP, MACD, RSI, KDJ; extended-hours shading; Heikin Ashi and hollow candles (§22.11)
- [x] Time & Sales (colored at bid / at ask / between) when stream data allows (§15.10: only prints a stream delivered, `source: NONE` otherwise)
- [x] News tab polish; analyst ratings and price targets if a free source exists (Finnhub recommendation trends; price targets are premium-only, so not shown)
- [x] Watchlist column chooser and sorting on desktop; drag reorder
- [ ] Passkeys for human login
- [x] Basic screener (market cap, price, % change, volume / average volume, sector) (§15.10; `/screener`, MCP `screen_stocks`)

---

## Phase 3: Deep realism

- [ ] **Shorting** (margin only): ETB/HTB lists, HTB borrow fee accrual, Reg SHO Rule 201, short maintenance tiers, payment in lieu of dividends
- [ ] Margin interest (8.74%/360 standard [U]; Premium tiers), monthly posting
- [ ] True SMA ratchet; concentration (CM) calls
- [ ] DRIP (dividend reinvestment around 10:00 on the credit date)
- [ ] Price improvement for marketable orders (optional, configurable); square-root market-impact model; queue-position modeling for resting limits
- [ ] **Overnight session** (20:00–04:00, eligible "24H" symbols, limit only, next-day trade date), only if a data feed covers it; the Dec 6, 2026 SIP 23×5 schedule and overnight LULD bands
- [ ] Half-penny ticks and price-tiered round lots (first business day of Nov 2027)
- [ ] Optional `LEGACY_25K` PDT mode for nostalgia leagues
- [ ] Conditional orders (trigger on another symbol or an index)

## Phase 4+: Extras and polish

- [ ] Desktop widget workspace with saved layouts (react-grid-layout) and templates
- [ ] Hotkeys and an Active Trade ladder; drag order lines on the chart to modify them
- [ ] Drawing tools, compare overlay, chart replay mode
- [ ] Sector heatmap, market breadth, 52-week highs and lows, earnings and economic calendars
- [ ] Advanced screener with saved screens; ⌘K commands ("buy AAPL 10")
- [ ] CSV export (orders, activity, P&L)
- [ ] Level 2 / depth only if a licensed source is affordable (never fake depth)
- [ ] `render_chart` MCP tool returning an image; agent tournaments (multiple model versions)
- [ ] Comments and reactions; weekly recap emails or digests

---

## No-code upgrade switches

- [ ] Alpaca **Algo Trader Plus** ($99/month): set `ALPACA_FEED=sip` and `MARKETDATA_PROFILE=alpaca-sip`. This gives true NBBO, unlimited stream symbols and `REALTIME_CONSOLIDATED` quality.
- [ ] Fee changes (SEC FY2027, TAF 2027, CAT 2027): add effective-dated `fee_schedule` rows through a migration.
- [ ] Rule toggles: `gtc-days` (default 60), `fractional.allow-limit`, GFV thresholds, IEX spread limits, eligibility, feed delay. (Holdings visibility is retired: public mode shows everything live.)

## Pre-launch verification checklist (from ARCHITECTURE §28)

- [x] V1 schema applied on PostgreSQL 18.6 (embedded PG, 2026-10-09); re-run `-Ppg-it` after the v1.1 schema additions
- [x] Spring Session JDBC starter artifact name in Boot 4.1.1 (`spring-boot-starter-session-jdbc`, §28 #14)
- [x] Spring AI 2.0.1: Day-1 spike confirms the `contextExtractor` transport override and that `mcpServerJsonMapper` backs off (property names are resolved in §20.1)
- [ ] Alpaca probe results recorded: quote-size units, `statuses` wildcard, movers, most-actives, news, `delayed_sip`, snapshot batch maximum
- [ ] Finnhub stream symbol cap and extended-hours prints
- [ ] Condition-code tables for eligible prints (Alpaca SIP and Finnhub)
- [ ] Good Friday settlement date matches Alpaca's calendar
- [ ] Separate Alpaca accounts for dev and prod (avoid 406)
- [ ] Cloudflare: WebSockets on; cache bypass rules (incl. `/swagger-ui/*`); Bot Fight Mode **off** for the zone (Free plan cannot skip it per path) or Pro Super Bot Fight Mode with a skip rule; AI Crawl Control allows the Agent category; no WAF challenges on `/api`, `/mcp`, `/.well-known` or `/oauth2`; Cloudflare Access with MFA on `/admin*` and `/api/v1/admin/*` only
- [ ] From a cloud VM: `/mcp` returns 401 JSON (not a challenge page); one live Claude API MCP-connector call and one OpenAI Responses MCP call run `whoami`
- [ ] CasaOS deploy path (docs/deploy.md): GHCR package pullable by the deploy job (or public / server logged in with a `read:packages` PAT); runner `casaos` online as a systemd service under an unprivileged `docker`-group user; fork PR workflows disabled and `main` protected; `DEPLOY_ENABLED=true`; one push-to-main deploy, one forced-failure rollback and one `workflow_dispatch` rollback to an older `sha-…` tag observed; cloudflared reaches `http://stocks-app:8080` over `stocks-edge` (no host port) and `/ws` upgrades through the tunnel. Production uses Watchtower instead; this item is only for the optional runner path
- [ ] claude.ai "Request headers" connector setting available on the owner's account (optional Phase 1 route)
