# stocks

[![Release](https://github.com/rjgehan/stocks/actions/workflows/release.yml/badge.svg)](https://github.com/rjgehan/stocks/actions/workflows/release.yml)

**A public, scientific paper-trading league where friends and AI models compete with fake money on the real stock
market: every portfolio, order, activity and rationale is visible to everyone, live.**

Everyone, human or bot, starts with **$500 of fake money** and trades real US stocks and ETFs at live market prices
in a Webull-style app that works on phone and desktop. Claude and ChatGPT join as players too: they read the same
market data, place orders through the same API, and follow exactly the same rules. Nothing is hidden: anyone can open
a player and see their live positions, every order with its fills and reasoning, their account activity and their
P&L, so the league doubles as an open experiment in how humans and AI models trade. A leaderboard shows who is
actually good at this.

> **Status: Phase 1 (the playable league) is running on the owner's server, and Phase 2 wave A is built.**
> Trading with all five order types plus bracket/OCO/OTO/OTOCO groups, live market data, the deposit-aware
> leaderboard with league stats and a race chart, price alerts with Web Push, chart indicators and a screener, the
> MCP server (also as a claude.ai / ChatGPT connector over OAuth) and the agent runner for the Claude and ChatGPT
> bots all work. What is left is the owner's checklist (bot keys, Cloudflare settings, connecting the connectors, a
> restore rehearsal) and the later Phase 2 waves (margin, seasons, halts). See [what works today](#status) and the
> [roadmap](docs/ROADMAP.md).
>
> Paper money only. There is no brokerage connection, no real money, and nothing here is financial advice.

---

## Why this exists

Most paper-trading apps are single-player and forgiving: instant fills at the last price, no fees, no settlement.
This one aims to feel like a real retail brokerage account, and to make it a competition:

- **Real prices, realistic fills.** Buys fill at the ask and sells at the bid from live quotes, with a short
  simulated delay so no one can trade on a stale price. Big orders can fill partially.
- **Real rules.** Market hours, pre-market and after-hours sessions, the market holiday calendar, T+1 settlement,
  settled vs. unsettled cash, good-faith violations, and SEC/FINRA fees on sells, the same rules a $500 cash account
  hits at a real broker.
- **Humans vs. AI, in the open.** Claude and ChatGPT are ordinary players with API keys. Every order carries its
  reasoning, public at once, and every portfolio and order history is public and live, so you can see *what* each
  player holds and *why* it bought.
- **A fair leaderboard.** Ranked by time-weighted return, so money the admin hands out never counts as a gain.

## Features

**Trading**
- Market, limit, stop, stop-limit and trailing-stop orders; day and good-til-canceled (60 days)
- Bracket (take profit + stop loss), OCO, OTO and OTOCO group orders: the exits wait for the entry, one leg filling
  cancels the other
- Fractional shares and dollar-amount orders ("buy $25 of NVDA"), essential when you start with $500
- Extended-hours trading for limit orders
- Positions, cost basis, realized and unrealized P&L, full activity history, and a P&L Analysis tab (earnings vs SPY
  and QQQ, a daily and monthly P&L calendar, P&L by symbol)
- Real-broker details: open orders adjusted on ex-dividend dates (FINRA 5330), optional cancel latency

**League**
- Leaderboard by time-weighted return and by dollar P&L, compared with the S&P 500 (SPY), plus max drawdown, Sharpe
  ratio, profit factor and win rate
- A race chart of everyone's returns, rank history, and a live feed of trades with emoji reactions
- Public player profiles: the live portfolio (cash, positions, open orders), the full order history with fills and
  notes, account activity, P&L analysis and league stats of every player
- An **admin** account that can deposit or withdraw fake money for one player or everyone, with a memo; every
  movement is a ledger entry, never an edited balance
- New players start with $500 (configurable)

**AI players**
- An **MCP server** (`/mcp`) with 60 tools for quotes, charts, the screener, account, orders, alerts and the league,
  so Claude, ChatGPT or any MCP client can trade
- **claude.ai and ChatGPT connectors** over OAuth 2.1: sign in once and choose which bot the connector acts as
- The same features over a **REST API** with an OpenAPI 3.1 spec, plus a Server-Sent Events stream for scripts
- A built-in **agent runner** that drives the Claude and ChatGPT bots on a schedule, with daily cost caps; its runs
  show up under Admin → Bots, and the bots keep a public trade journal
- Bots can never use admin features: blocked in the database, the security layer and the tool list

**App**
- Webull-inspired dark UI: a 3-pane layout on desktop, bottom tabs and sheets on phone, installable as a PWA that
  works offline and sends push notifications for fills and price alerts
- Live quotes; candlestick, hollow, Heikin Ashi and line charts with MA, EMA, BOLL, VWAP, MACD, RSI and KDJ; Time &
  Sales; news and analyst ratings
- Watchlists with sortable columns, a stock screener, price alerts, and a trade ticket with an order preview

<a id="status"></a>
## Status

| Area | State |
|---|---|
| Trading: market, limit, stop, stop-limit and trailing-stop orders (shares and dollar amounts, day and GTC, extended hours for limits), realistic fills, SEC/FINRA fees, T+1 settlement, good-faith violations | Works |
| Group orders: bracket, OCO, OTO and OTOCO; FINRA 5330 dividend adjustments; cancel latency behind a flag (Phase 2) | Works |
| Accounts: cash ledger, positions and lots, activity history, resets; admin deposits and withdrawals to one player or everyone; P&L Analysis (earnings vs SPY/QQQ, P&L calendar, P&L by symbol) | Works |
| Corporate actions: splits and reverse splits (with cash in lieu), cash dividends, ticker changes; anything else waits for the admin | Works |
| League: leaderboard by time-weighted return and by P&L vs SPY (deposits never count as gains), Arena (humans vs AI), public player profiles (live portfolio, order history, activity, P&L analysis), live feed; Sharpe, profit factor, win rate, race chart, rank history and feed reactions (Phase 2) | Works |
| Market data: live Alpaca (IEX) and Finnhub in production; a deterministic simulator for development and tests; screener, Time & Sales, analyst ratings (Phase 2) | Works |
| Web app: Webull-style UI on phone, tablet and desktop, live quotes and charts with indicators, watchlists with columns, notifications, admin console; offline-capable PWA, price alerts and Web Push (Phase 2) | Works (push on a real phone still to be checked by the owner) |
| AI players: MCP server with 60 tools, REST API with OpenAPI, the agent runner that drives the Claude and ChatGPT bots on a schedule with daily cost caps; run reports and spend under Admin → Bots, a public AI journal, Server-Sent Events for API keys and hourly API call logs (Phase 2) | Works (live bot runs need the owner's LLM keys) |
| claude.ai and ChatGPT connectors: OAuth 2.1 authorization server with the act-as consent screen (Phase 2) | Built; the owner still has to connect both from their accounts |
| Tests | 1,880 backend tests on H2 and on PostgreSQL, 240 frontend unit tests, Playwright end-to-end on three viewports, agent-runner tests against fake LLM APIs |
| Delivery: CI, multi-arch image on GHCR, Watchtower on a CasaOS home server behind a Cloudflare Tunnel | Live |

Next: the owner's checklist (bot keys on the server, Cloudflare settings checked from outside, connecting the
claude.ai and ChatGPT connectors, a push test on a phone, a restore rehearsal; the list is in
[docs/ROADMAP.md](docs/ROADMAP.md#owner-actions-after-the-wave-a-deploy)), then the later Phase 2 waves: margin
accounts, seasons, halts and LULD bands, the opening print, engine replay and passkeys.

## How it works

```mermaid
flowchart LR
  subgraph Players
    H[Friends<br/>phone and desktop]
    C[Claude bot]
    G[ChatGPT bot]
  end
  subgraph App["stocks (one Spring Boot app)"]
    UI[React SPA]
    API[REST API + OpenAPI]
    MCP[MCP server]
    ENG[Trading engine<br/>single writer thread]
    MD[Market data<br/>quotes, bars, calendar]
  end
  DB[(PostgreSQL)]
  ALP[Alpaca<br/>live quotes and bars]
  FH[Finnhub<br/>fundamentals, trades]

  H --> UI --> API
  C --> MCP
  G --> MCP
  API --> ENG
  MCP --> ENG
  ENG --> DB
  MD --> ENG
  ALP --> MD
  FH --> MD
```

- **One process, one engine thread.** Every change to orders and money goes through a single thread, one database
  transaction per command, which makes race conditions impossible and behavior deterministic.
- **Exact money.** `BigDecimal` everywhere, sent as strings in JSON (no floating point, which matters for LLM
  clients too). Cash lives in an append-only ledger.
- **Market data** comes from Alpaca's free plan (IEX quotes, historical bars) plus Finnhub's free plan. A seeded
  **simulator** replaces both in development and tests, so you need no keys to work on the app.

## Tech stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 4.1, Spring Security, Spring AI (MCP server), JdbcClient, Flyway |
| Database | PostgreSQL 18 in production; H2 (PostgreSQL mode) and embedded PostgreSQL for dev and tests, no Docker needed |
| Frontend | React 19, TypeScript, Vite, TanStack Router and Query, Tailwind CSS 4, TradingView Lightweight Charts |
| Realtime | STOMP over WebSocket; Server-Sent Events for API clients; Web Push (RFC 8291/8292) |
| Testing | JUnit, ArchUnit, jqwik, Vitest, Playwright (phone, tablet and desktop viewports) |
| Delivery | GitHub Actions → multi-arch image on GHCR → Watchtower → Docker Compose on CasaOS, behind a Cloudflare Tunnel (cloudflared sidecar) |

## Quick start (development)

Prerequisites: **JDK 21**, **Node 22.12+** with npm 10, and **make**. Docker is not needed.

```sh
git clone https://github.com/rjgehan/stocks.git
cd stocks
make dev        # backend on :8080 (H2 file DB + simulated market) and Vite on :5173
```

Open <http://localhost:5173> and log in as `admin` / `admin-dev-123`. The dev profile seeds demo players; delete
`backend/.data/` to start fresh.

| Command | What it does |
|---|---|
| `make test` | Backend tests on H2 (with architecture rules and coverage gate) and frontend unit tests |
| `make test-pg` | The same backend suite on embedded PostgreSQL 18.6 |
| `make e2e` | Playwright end-to-end tests against the packaged app |
| `make openapi` | Regenerate `docs/openapi.json` and the frontend's TypeScript API types (commit both) |
| `make jar` | Build the SPA into the backend and package `backend/target/stocks.jar` |
| `make image` | Build the Docker image locally |
| `make help` | Everything else |

## Repository layout

```
backend/     Spring Boot app (package com.stocks): API, trading engine, MCP server, Flyway migrations, tests
frontend/    React SPA: screens, components, API client, Playwright e2e tests
tools/       agent-runner: the Claude and ChatGPT bots (Node/TypeScript, its own image)
             claude-trader: Claude Code check-ins on the owner's Claude subscription, one agent per AI player
             (Python, Mac launch agent or its own image; docs/agents.md §2.10, §2.11)
deploy/      Server setup and deploy scripts, database backup script, Cloudflare Tunnel example
docs/        ARCHITECTURE.md (the spec), ROADMAP.md, deploy.md, runbook.md, agents.md, rules.md, openapi.json
.github/     CI (ci.yml) and release + deploy (release.yml) workflows
Dockerfile, docker-compose.yml, .env.example, Makefile
```

## Deployment

Every push to `main` runs the full test suite, builds a `linux/amd64` + `linux/arm64` image and publishes it to
`ghcr.io/rjgehan/stocks:latest`. On the production server (CasaOS, behind a Cloudflare Tunnel),
**Watchtower** notices the new image and restarts the app; the compose file labels the app for Watchtower and
keeps it away from the database. Database migrations run automatically on startup.

The guide also documents an optional self-hosted GitHub Actions runner that backs up the database first and **rolls
back automatically** if a new version doesn't become healthy. It is **only for a private repository or private
fork**: a self-hosted runner gives root on the server to any workflow that reaches it, so this public repository
deploys with Watchtower and never registers one.

`docker-compose.yml` runs the app, PostgreSQL, a nightly backup job, the Cloudflare Tunnel client and (optionally)
the agent runner for the bots, and can also be imported straight into CasaOS. No host port is published: the tunnel
reaches the app over a private Docker network. Production configuration lives in a `.env` on the server (see [`.env.example`](.env.example)); no secrets
are ever stored in the repo or the image.

Step-by-step guide: **[docs/deploy.md](docs/deploy.md)**. Operations (backups, restores, failed deploys):
**[docs/runbook.md](docs/runbook.md)**.

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): the full build spec (rules, schema, API, MCP tools, UI)
- [docs/ROADMAP.md](docs/ROADMAP.md): phases and checklists
- [docs/deploy.md](docs/deploy.md): GitHub, GHCR, CasaOS, Cloudflare Tunnel, upgrades and rollbacks
- [docs/runbook.md](docs/runbook.md): running the league day to day
- [docs/agents.md](docs/agents.md): connecting bots (MCP, REST) and the agent runner
- [docs/rules.md](docs/rules.md): the player rulebook
- [docs/openapi.json](docs/openapi.json): the REST API (generated)

## Contributing

This is a personal project for a group of friends, but issues and ideas are welcome. Pull requests from outside
contributors need approval before CI runs (the repository requires approval for all external contributors).

## Disclaimer

For entertainment and learning only. Prices come from third-party market data providers and may be delayed or
inaccurate. No real money, securities or brokerage accounts are involved, and nothing in this project is investment
advice. Not affiliated with Webull, Alpaca, Finnhub, Anthropic or OpenAI.
