# stocks: Roadmap

> **Companion to** [`ARCHITECTURE.md`](ARCHITECTURE.md), which is the authoritative spec. Section references (§) point there.
> **Date:** 2026-10-09.
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

- [ ] [WS1] Maven Wrapper 3.9.11, `pom.xml` on Boot 4.1.1 with every Phase 1 dependency pinned (§2.1), including `micrometer-registry-prometheus` and zonky `embedded-postgres` 2.2.2 with binaries 18.6.0. Upgrade the local JDK to Temurin 21.0.12+.
- [ ] [WS6] Vite 8 React 19 TypeScript 6.0 app with every Phase 1 dependency pinned (§2.2), **the `openapi-typescript` → `$typescript` override, and the committed `package-lock.json`**; Tailwind 4 with design tokens (§22.8); `index.html` with `viewport-fit=cover`, manifest and icons (§22.3)
- [ ] [WS1] `.gitignore` additions, `.dockerignore`, `.editorconfig`, `.env.example`, `Makefile` (`dev`, `test`, `test-pg`, `e2e`, `openapi`, `jar`, `image`)
- [ ] [WS1] Flyway `V1__init.sql` plus vendor extras **verbatim** from §8, green on H2 **and on embedded PostgreSQL 18.6** (`make test-pg`, no Docker). The original V1 was verified on PG 18.6 on 2026-10-09 (§28 #19); this run re-verifies the v1.1 additions.
- [ ] [WS1] `common/**`: enums, `ErrorCode` (incl. `NOT_IMPLEMENTED`, `INTERNAL_ERROR`), `PublicId` (ULID), `TimeSource`/`AdjustableTimeSource`, **`Jdbc` (no raw `Instant` on JDBC)**, `Money`/`Rounding`, events, problem details, Jackson (decimals as strings)
- [ ] [WS1] Contract interfaces and records from §5.3 in `com.stocks.contract.*`, every DTO from §18.3 as a `…Dto` record, the job SPI (`ScheduledJob`, `PeriodicTask`), and stub beans through `StubsAutoConfiguration` (`NoopMarketDataService` as the market-data default)
- [ ] [WS1] Security skeleton with dev login; SPA fallback; ArchUnit rules (§5.2, including the cycle and JDBC rules); test kit (`MutableTimeSource`, `ScriptedMarketData`, fixtures, `EmbeddedPgContextInitializer`); `JobScheduler` skeleton
- [ ] [WS3] `DirectEngineExecutor` in WS3 test sources (week 1, with the engine)
- [ ] [WS1] CI: backend on H2, backend on embedded PG, frontend, OpenAPI drift and assertions (no `type: number`), image build
- [ ] [WS5] **Spike:** Spring AI 2.0.1 STATELESS MCP `whoami` through the overridden transport's `contextExtractor`, with Boot's `JsonMapper` as `mcpServerJsonMapper` (§20.1, §28 #13 and #20)
- [ ] [all] Every workstream reviews and signs off the contracts PR

---

## Phase 1: MVP, the playable league (target: about 5 weeks, milestones M0–M4)

### WS1: Platform, identity and admin
- [ ] Human auth: JSON login and logout, Spring Session JDBC, `__Host-` cookie, CSRF (SPA mode), lockout, per-IP login limit
- [ ] Invite-only signup (`/auth/signup`) that funds a $500 account and seeds the "My List" watchlist
- [ ] API keys `stk_<id>_<secret>`: HMAC with pepper, scopes, 60 s cache, revoke, `last_used_*`; personal keys for humans (session + `@RecentAuth`, read-only unless the admin allows API trading, revoked on password change)
- [ ] Bearer filter (key path ignores cookies); `/mcp` returns 401 with `WWW-Authenticate`
- [ ] Per-player Bucket4j rate limits (reads, heavy reads, writes 20/min and 300/day) with headers and 429 + `Retry-After`; `RateLimitService` (write bucket charged only by `OrderGate`, replays free)
- [ ] `RequestMeta` bounds client strings; `X-Request-Id` validated or regenerated; `audit_log.request_id` 64
- [ ] `AdminSessionOnlyFilter` (403 on any `Authorization` header), `@RecentAuth` plus `/auth/reauth`
- [ ] `AuditService` and `audit_log`, covering every category in §17.7
- [ ] `SettingsService` (`app_setting`) with the §23.4 defaults; `STARTING_CASH_DEFAULT` seeds `league.defaultStartingCash`
- [ ] Bootstrap admin from env vars; idempotent dev demo seed (admin `admin-dev-123`, alice, bob, claude, chatgpt, with keys written to `backend/.data/dev-keys.txt`)
- [ ] Admin REST (§21.1): players (create human or AI + key; detail; break-glass live view; disable; freeze/unfreeze with optional key revocation; promote human; allow API trading), keys, invites, **cash transfers (preview; single and ALL; memo; `Idempotency-Key` checked inside the engine; mark-quality gate)**, resets, admin order cancel, settings, audit, system, engine halt/resume (`EngineControl`), calendar overrides (`CalendarAdmin`), corporate actions (list, resolve)
- [ ] Freeze/disable step 1 (status, sessions, caches, optional key revocation, `PlayerStatusChanged`) then `AccountService.restrict` (§10.5)
- [ ] `SingleInstanceGuard` (PG advisory lock or H2 lease with dead-PID takeover; throws instead of exiting; disabled in tests), `JobScheduler` (job SPI, registry), `JobRunGuard`, `CatchUpRunner` (catch-up policies), `StartupGate`
- [ ] `TestClockController` (`@Profile("test")`, prod fail-fast) for e2e
- [ ] Security headers, cache headers, SPA fallback, forwarded headers (§24.3)
- [ ] Production `Dockerfile`, `.dockerignore`, `docker-compose.yml` (app, db, backup, `agent-runner` under profile `agents`), `pg_backup.sh`, `docs/deploy.md` (Cloudflare ingress: Bot Fight Mode off or Pro skip rule, AI Crawl Control allows Agents, Access + MFA on admin paths), `docs/runbook.md` (incl. "delete `backend/.data/` after the V1 squash")
- [ ] OpenAPI rules (§18): BigDecimal as decimal strings, required + nullable record components, wire-value enums, `Dto` suffix stripped
- [ ] `AdminSecurityIT`: every admin route rejects anonymous callers, bot keys, the admin's own key and non-admin sessions

### WS2: Market data and calendar
- [ ] `MarketCalendar` with the bundled `xnys-2016-2028.csv` (generator plus the §9.2 golden dates), Alpaca calendar sync, `CalendarAdmin` overrides, and the in-memory `AlwaysOpenCalendar` for dev and e2e
- [ ] **SIM provider by end of week 1**: deterministic paths, quotes, prints, bars; clock modes; scenarios (halt, gap, blackout, one-sided quote, split, dividend, flash crash)
- [ ] `QuoteBook` (fan-out through `List<MarketUpdateListener>`), `MarketDataService`, `ExecutableQuoteBuilder` (anti-look-ahead, freshness, quality ladder with tiered IEX spread limits 10/40/150 bps, clamp, synthetic quote); halts only from `statuses`
- [ ] DTO mappers (`QuoteDtoMapper`, `BarDtoMapper`, …) shared by REST, STOMP and MCP
- [ ] Alpaca REST: assets, calendar, snapshots, latest quotes and trades, bars, news, movers, most-actives, corporate actions, logos
- [ ] Alpaca IEX stream: auth, subscribe diffs, 405/406 handling, backoff (90 s after a 406), watchdog, `statuses`
- [ ] Finnhub: `profile2`, `metric`, `/stock/symbol` types, search; SIP trade stream with heartbeat watchdog
- [ ] `SubscriptionBudgetManager` (leases, priorities, hysteresis, ≤ 30 Alpaca and about 50 Finnhub symbols), `SnapshotPoller`, `QuoteRefresher`, `ProviderRateLimiter` (180/min with priority classes)
- [ ] `BarService`: SIP history ≥ 16 minutes old + IEX tail + live aggregation; range defaults; caching
- [ ] `MarkService` (live marks, split-adjusted fallbacks) and `DailyMarkJob` (16:20 official close; 20:20 day-end marks; re-marking PROVISIONAL rows)
- [ ] `SplitAdjustments` (`price_adjustment`) and `SplitGuard` (04:01 and 09:20; corporate-action hold)
- [ ] `AssetSyncJob` (exchanges filter, ETF type, liquidity tiers, search index), `PrevCloseJob`, movers with a computed fallback, news, fundamentals
- [ ] `AlpacaCapabilityProbe`, with results on `/admin/system`; prod refuses SIM unless overridden
- [ ] Market REST: `/market/clock|calendar|indices|movers`, `/assets/search|{symbol}|logo`, `/quotes`, `/snapshots`, `/bars`, `/news`

### WS3: Trading core
- [ ] `TradingEngine`: single thread, bounded queue, one transaction per command, events after commit, sweep, dirty-symbol drain
- [ ] `OrderGate` (checks 1–6, one write token, free replays) and pure `OrderValidator` (checks 7–27, authoritative on the engine thread), `HoldCalculator` (102% and 115% cushions), `SessionOrderMatrix`, `ExpiryCalculator` (DAY, GTC 60 days, early closes)
- [ ] `OrderStateMachine`, table-driven and exhaustively tested
- [ ] Order types: **MARKET** (with collar), **LIMIT** (+ extended hours), **STOP**, **STOP_LIMIT**, **TRAILING_STOP** (DAY; entered in REGULAR only)
- [ ] **Fractional and notional market orders** (RTH, DAY, ≥ $1, 5 dp, notional all-in including fees)
- [ ] `FillModel`: touch, book walk, partial fills by displayed size, resting fills at L, trade-through, opening and reopen semantics, halts
- [ ] `TriggerEvaluator`: stop triggers on consolidated prints with a bid/ask fallback, gap at open, trailing watermark
- [ ] `PostingService`: execution, ledger row (net of fees), FIFO lots, position, realized P&L, order update, events
- [ ] `FeeCalculator`: effective-dated, cumulative per-order delta charging; golden table (§13)
- [ ] `BalanceCalculator`: settled/unsettled (T+1 derived), settled available, withdrawable, buying power by restriction
- [ ] `GfvService`: funding attribution, detection, 3 → SETTLED_CASH_ONLY and 4 → LIQUIDATION_ONLY escalation, expiry, `GFV_RISK` warning predicted on `session_date`, `GFV_ACK_REQUIRED`
- [ ] Idempotent `clientOrderId` (replay 200, 409 on conflict); synchronous rejects return 422, are not persisted, and are audited
- [ ] Cancel, cancel-all, replace (whole-share unfilled only), close position
- [ ] Accounts: open (INITIAL transfer, units), reset (run n+1 at the final U, in-engine idempotency), `restrict`/`liftAdminRestriction`, `recordAdminView`; `ApplyCashTransfersCmd` (in-engine idempotency, mark-quality gate, valuation at flow, units at scale 18, empty and rebase cases, all-or-nothing)
- [ ] `account_event` and `position_adjustment` (append-only); activities merged from `cash_ledger` and `account_event`; `AdminOrderService`, `EngineControl`, `CorporateActionQueries`/`Service`
- [ ] Session transitions (PRE_OPEN, RTH_OPEN, RTH_CLOSE, POST_CLOSE) with catch-up and no retroactive fills
- [ ] Corporate actions: sync, forward and reverse splits (per-lot `SPLIT_QTY`, position = Σ lots, dust and cash in lieu, cancel orders; golden 2:1, 3:2, 1:3, 1:8), cash dividends (accrue on ex-date, pay on payable date, `voided_at`), name changes (incl. `WatchlistService.renameSymbol`); others go to NEEDS_ADMIN; admin resolve
- [ ] `InvariantChecker` (I1–I9) with auto-halt; `DailyMaintenanceCmd`; `FillQualityAudit` (I10, warnings)
- [ ] REST: `/orders/**`, `/orders/preview`, `/account`, `/account/positions/**`, `/account/activities`, `/account/executions`, `/account/violations`, `/account/runs`, `/account/reset`

### WS4: Portfolio and league
- [ ] `UnitMath` (Day 2) and `ValuationService` (end of week 1; reads through the caller's transaction)
- [ ] Unit-value accounting golden vectors (§16.2), including the deposit-to-ALL case
- [ ] Golden vectors for tiny equity, rebase, reset continuity and dividend continuity (§16.2); point-in-time receivable (§16.1)
- [ ] `HoldingsReconstructor` (forward replay of executions + `position_adjustment`); `EodSnapshotJob` (20:20 ET; `equity_snapshot`, `snapshot_holding`, feed backfill); `IntradaySampler`
- [ ] SPY total-return benchmark (B = 1 at 2025-12-31, `BenchmarkBackfillJob`, intraday anchors from `spy_mark`), excess return, max drawdown
- [ ] `LeaderboardService`: per-player series across runs (a reset never erases a loss); periods TODAY, WEEK, MONTH, YTD, SEASON, ALL × metrics RETURN, PNL × kinds ALL, HUMAN, AI; eligibility (5 fills / 2 symbols); ties; Arena; 5 s live recompute; `leaderboard_snapshot` for rank arrows; equity hidden under HIDDEN; `rebased` and `apiTrader` flags
- [ ] Day P&L (account and position) with the same anchors as the board (`PerformanceQueries.accountPnl`, `positionDayPnl`, name-change mapping)
- [ ] Player profiles: stats per period, deposits, resets, EOD-weight holdings, delayed trades (rationales only for AI or shared notes; "via API" label)
- [ ] League feed: FILL, POSITION_CLOSED, NEW_LEADER, RESET, DEPOSIT, JOINED, published after the visibility delay; `FeedEventListener` is the sole writer, idempotent by `source_ref`
- [ ] Watchlists CRUD; `WatchlistService.seedDefault` and `renameSymbol`
- [ ] REST: `/leaderboard`, `/players/**`, `/feed`, `/account/history`, `/watchlists/**`

### WS5: Agents and realtime
- [ ] STOMP `/ws`: session auth, CSRF on CONNECT (raw cookie token, non-masking `csrfChannelInterceptor`), heartbeats, subscription caps, leases (quote and `bars.{SYM}.1Min` → VIEWING, tick → LIST)
- [ ] `QuoteFanout` as a `MarketUpdateListener` (≤ 4/s and ≤ 1/s), bars topic, session topic, leaderboard signal, feed topic
- [ ] `UserEventPublisher` (`/user/queue/orders|account|notifications|admin`) and `NotificationService` (sole writer, idempotent by `source_ref`) with `/me/notifications`
- [ ] `RulesService` and `GET /rules` (the bots' source of truth, versioned)
- [ ] MCP server (STATELESS, `/mcp`): `McpConfig` (Boot `JsonMapper` as `mcpServerJsonMapper`, transport override with `contextExtractor`, output schemas with decimal strings); all 38 tools of §20.3 with explicit hints, fixed order, snake_case inputs, the `untrusted` envelope, stable error codes, rate limits, audit; `mcpClient` from `X-Stocks-Client` / last `initialize` / User-Agent
- [ ] MCP tests: `tools/list` snapshot, identity, JSON contract (decimal strings, nulls, `outputSchema` validation), tool coverage vs `docs/openapi.json`, idempotent `place_order`, `RATIONALE_REQUIRED`, no admin tools
- [ ] **Agent runner** (`tools/agent-runner/`, §20.5): compose service `agent-runner` (profile `agents`); one model-agnostic loop for Claude and ChatGPT; same prompt, tools and schedule (09:45, 12:30, 15:30 ET); per-run tool-call and time caps; per-bot daily dollar cap; notes-file memory; JSONL run log
- [ ] `docs/agents.md`: agent runner, Claude Code, Claude API MCP connector and OpenAI Responses recipes; optional claude.ai custom connector with "No sign-in" + Request headers (beta); why there is no ChatGPT-app or GPT-Actions route in Phase 1; the shared bot system prompt; `X-Stocks-Client`
- [ ] `docs/rules.md`: the player-facing rulebook (mirrors `/rules`, with [V]/[U] notes)
- [ ] **Live verification:** a Claude bot (Claude Code, API key) and a GPT bot (Responses API, API key) each place, inspect and cancel orders on the deployed instance

### WS6: Frontend
- [ ] Shells: desktop 3-pane (rail, watchlist, center, ticket, bottom dock), tablet drawer and side sheet, mobile bottom tabs, pushed screens and bottom sheets; safe areas (`viewport-fit=cover`); static manifest, apple-touch-icon and theme-color (installable, no service worker); 44 px targets
- [ ] TopBar: ⌘K search, session chip with countdown, PAPER and SIM badges, NAV hero, notification bell; data-source footer
- [ ] Watchlist: lists, Holdings list, streaming rows with flash, % pill cycling, "Add 5 popular" empty state, index strip
- [ ] Quote page: sticky header with the extended-hours line, key stats, Lightweight Charts (candles/area, ranges 1D–ALL, intervals, volume pane, previous-close line, crosshair), bid/ask bar, my position and my orders
- [ ] Trade ticket and review sheet (§22.5): Shares/$ toggle, price chips, 25/50/Max, TIF, extended hours, live preview, GFV acknowledgement, note with "Share note publicly", idempotent submit
- [ ] Account: NAV hero, balances grid (settled/unsettled/withdrawable/BP/holds/net contributions), GFV card, equity curve vs SPY, positions, orders and order detail (timeline, fills, fees, quote used), activity (deposit memos), runs
- [ ] Markets: index cards and movers tabs, plus news when available
- [ ] League: period, metric and kind controls, ranked and unranked rows, deposit chips, Arena card, formula popovers, my pinned row, feed view; player profiles
- [ ] Settings: theme, red-up/green-up, confirmations, defaults, personal API keys, self-reset
- [ ] Admin: players, player detail (EOD by default, audited break-glass live view), money (preview with mark quality, degraded-marks override, typed-amount confirmation), invites, settings, system (jobs registry, calendar overrides, corporate-action resolve), audit
- [ ] Generated API client, STOMP hooks, `quoteStore`, mock mode (`VITE_API_MOCK=1`)
- [ ] Playwright e2e at 390×844, 768×1024 and 1440×900 against profiles `test,e2e`, with the `advanceClock()` helper (§18.5, §26)

### Milestones

- [ ] **M0 Boot (end of week 1):** H2 + SIM boot, login, `/quotes` on SIM, SPA shell on both layouts, MCP `whoami` spike passes
- [ ] **M1 Trade on SIM (week 2):** MARKET (shares and notional) and LIMIT orders end to end, ledger and positions, account page, ticket with preview, live order events. **Friends can trade on SIM.**
- [ ] **M2 Live and bots (week 3):** all 5 order types, fees, T+1, GFV; Alpaca live data; charts; MCP tools with API keys; agent runner trading both bots on SIM; admin players, keys and transfers
- [ ] **M3 League (week 4):** EOD snapshots, TWR and P&L leaderboard, profiles, feed, corporate actions, Finnhub, Markets, Settings
- [ ] **M4 Ship (week 5):** hardening, e2e green on three viewports, no Day-0 stubs left, deployed behind the owner's Cloudflare Tunnel with the §24.5 settings verified from a cloud VM, agent runner live

### Phase 1 exit criteria

- [ ] At least 3 humans (one on a phone) and 2 AI bots (Claude, ChatGPT, both driven by the agent runner within their daily caps) trade for **5 consecutive trading days** on live IEX data with $500 starting cash.
- [ ] Every order type works in its allowed sessions. Fractional and notional orders work in RTH. Settled vs unsettled cash and GFV warnings behave as specified.
- [ ] An admin **deposit to ALL** shows up in each player's activity with its memo and **does not change anyone's TWR**, including an account below $1. The leaderboard ranks by TWR and by net-contribution P&L, and a reset does not erase a period's loss.
- [ ] Invariants I1–I9 are green every night. No retroactive fills after a restart.
- [ ] A bot can never reach an admin endpoint (`AdminSecurityIT` green).
- [ ] Backups run nightly and a restore has been rehearsed once.

---

## Phase 2: Full realism and connectors

### Connectors and agents
- [ ] OAuth 2.1 Authorization Server: PKCE S256, CIMD + DCR, protected-resource metadata, `iss`, rotating refresh tokens, and the **act-as consent** screen (§17.9)
- [ ] claude.ai custom connector (OAuth) verified, and the ChatGPT developer-mode connector verified (check plan eligibility). If the owner wants the ChatGPT app in Phase 1, this OAuth work moves up (owner decision, §20.4).
- [ ] Agent runner: `agent_run` table and an admin UI over the Phase 1 runner (§20.5)
- [ ] SSE stream `/api/v1/stream` (quotes, orders, account, session, leaderboard) with `Last-Event-ID`
- [ ] `api_access_log` (reads, 90-day retention) and the "recent API calls" view in Settings
- [ ] `add_journal_note` MCP tool, and a public AI trade journal (the Phase 1 runner keeps private notes files)

### Trading realism
- [ ] **MARGIN accounts**, offered once equity is at least $2,000 (an admin opt-in per player or league): long-only, intraday buying power 4 × margin excess, overnight 2 × Reg T excess, the Webull maintenance table, IMD/RM/RT calls with due dates, auto-liquidation, strikes
- [ ] **Bracket (TP/SL, ≥ 0.1% apart), OCO, OTO, OTOCO** group orders
- [ ] **Official opening print** fills (SIP condition codes) and halt reopen prints
- [ ] Halt feed and LULD bands (Nasdaq Trader halts RSS [VERIFY]); market-wide circuit breakers
- [ ] FINRA 5330 order adjustments on ex-dividend dates
- [ ] Admin tool for corporate actions: cash and stock mergers, delistings (force-close at a price), spin-offs as manual events
- [ ] Cancel latency (`PENDING_CANCEL`, 150 ms + jitter) behind a flag
- [ ] Engine input log (NDJSON) with bit-for-bit replay; `ReplayMarketDataProvider` and `MarketTapeRecorder`
- [ ] Leveraged-ETF requirements (2x/3x) for margin

### League and fun
- [ ] **Competitions / seasons** (`league`, `league_member`, DEDICATED accounts, deposit policies `NONE` / `UNIFORM_ONLY`, join rules, end-of-season standings and badges)
- [ ] Sharpe ratio (≥ 20 days), profit factor, average hold time, best and worst trade
- [ ] Race chart (everyone's unit-value curves), rank-change history
- [ ] Reactions on feed items

### Product (Webull parity, part 1)
- [ ] P&L Analysis: My Earnings (return vs SPY/QQQ), P&L calendar (day/month), P&L distribution by symbol
- [ ] Alerts: price ≥/≤, % change; full PWA (vite-plugin-pwa 1.3.0: service worker, offline shell) with Web Push for fills and alerts (the static manifest ships in Phase 1)
- [ ] Chart indicators: MA, EMA, BOLL, VWAP, MACD, RSI, KDJ; extended-hours shading; Heikin Ashi and hollow candles
- [ ] Time & Sales (colored at bid / at ask / between) when stream data allows
- [ ] News tab polish; analyst ratings and price targets if a free source exists
- [ ] Watchlist column chooser and sorting on desktop; drag reorder
- [ ] Passkeys for human login
- [ ] Basic screener (market cap, price, % change, volume / average volume, sector)

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
- [ ] Rule toggles: `gtc-days` (default 60), `fractional.allow-limit`, GFV thresholds, IEX spread limits, eligibility, feed delay, holdings visibility.

## Pre-launch verification checklist (from ARCHITECTURE §28)

- [x] V1 schema applied on PostgreSQL 18.6 (embedded PG, 2026-10-09); re-run `-Ppg-it` after the v1.1 schema additions
- [ ] Spring Session JDBC starter artifact name in Boot 4.1.1
- [ ] Spring AI 2.0.1: Day-1 spike confirms the `contextExtractor` transport override and that `mcpServerJsonMapper` backs off (property names are resolved in §20.1)
- [ ] Alpaca probe results recorded: quote-size units, `statuses` wildcard, movers, most-actives, news, `delayed_sip`, snapshot batch maximum
- [ ] Finnhub stream symbol cap and extended-hours prints
- [ ] Condition-code tables for eligible prints (Alpaca SIP and Finnhub)
- [ ] Good Friday settlement date matches Alpaca's calendar
- [ ] Separate Alpaca accounts for dev and prod (avoid 406)
- [ ] Cloudflare: WebSockets on; cache bypass rules (incl. `/swagger-ui/*`); Bot Fight Mode **off** for the zone (Free plan cannot skip it per path) or Pro Super Bot Fight Mode with a skip rule; AI Crawl Control allows the Agent category; no WAF challenges on `/api`, `/mcp`, `/.well-known` or `/oauth2`; Cloudflare Access with MFA on `/admin*` and `/api/v1/admin/*` only
- [ ] From a cloud VM: `/mcp` returns 401 JSON (not a challenge page); one live Claude API MCP-connector call and one OpenAI Responses MCP call run `whoami`
- [ ] claude.ai "Request headers" connector setting available on the owner's account (optional Phase 1 route)
