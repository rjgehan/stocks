# stocks

A multiplayer **paper-trading league**. Friends and AI bots (Claude and ChatGPT) each start with **$500 of fake
money** and trade real US stocks and ETFs at live market prices, with Webull-style screens on desktop and phone.
Orders fill against real quotes under real cash-account rules (T+1 settlement, good-faith violations, fees). An
admin can deposit money, and the leaderboard ranks players by time-weighted return so deposits never distort it.
Bots play through the same REST API and an MCP server, under the same rules as humans.

> **Status:** Phase 0 (scaffold and contracts). See [docs/ROADMAP.md](docs/ROADMAP.md).
> Paper money only: no brokerage connection, nothing here is financial advice.

## Stack

Spring Boot 4.1 on Java 21 (one process, one trading-engine thread) · PostgreSQL 18 (H2 for dev and tests) ·
React 19 + Vite 8 SPA served by the backend from the same origin · STOMP over WebSocket · MCP for AI agents ·
market data from Alpaca and Finnhub (a deterministic simulator in dev). One multi-arch Docker image, deployed by
GitHub Actions to a self-hosted CasaOS server behind a Cloudflare Tunnel.

## Quick start (development)

Prerequisites: JDK 21, Node 22.12+ with npm 10, GNU Make. Docker is **not** needed for development.

```sh
make dev        # backend on :8080 (profile dev: H2 file DB + simulated market) and Vite on :5173
```

Open <http://localhost:5173>. The dev profile seeds an admin `admin` / `admin-dev-123` and demo players
(ARCHITECTURE §23.2). Delete `backend/.data/` to start over.

| Command | What it does |
|---|---|
| `make test` | backend `./mvnw verify` (H2, ArchUnit, coverage gate) and frontend unit tests |
| `make test-pg` | the same backend suite on embedded PostgreSQL 18.6 (no Docker) |
| `make openapi` | regenerate `docs/openapi.json` and the TypeScript client types; commit both |
| `make e2e` | Playwright end-to-end tests against the built jar |
| `make jar` | build the SPA into the backend and package `backend/target/stocks.jar` |
| `make image` | build the Docker image locally |
| `make help` | everything else |

## Repository layout

```
backend/     Spring Boot app (com.stocks), Flyway migrations, tests
frontend/    React SPA (Vite, TanStack, Tailwind), Playwright e2e
deploy/      CasaOS/Docker deploy scripts, backup script, Cloudflare Tunnel example
docs/        ARCHITECTURE.md (the spec), ROADMAP.md, deploy.md, runbook.md, openapi.json
.github/     CI (ci.yml) and release/deploy (release.yml) workflows
Dockerfile, docker-compose.yml, .env.example, Makefile
```

## Deploying

Every push to `main` runs CI, pushes `ghcr.io/<owner>/stocks` (linux/amd64 + linux/arm64) and, once enabled,
deploys it to your CasaOS server through a self-hosted GitHub Actions runner, with an automatic rollback when the
new version does not become healthy. Step-by-step setup: **[docs/deploy.md](docs/deploy.md)**. Day-2 operations
(backups, restore, failed deploys, engine halts): **[docs/runbook.md](docs/runbook.md)**.

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): the authoritative build specification
- [docs/ROADMAP.md](docs/ROADMAP.md): phases and checklists
- [docs/deploy.md](docs/deploy.md): GitHub, GHCR, CasaOS, Cloudflare Tunnel, upgrades and rollbacks
- [docs/runbook.md](docs/runbook.md): operating the running league
- `docs/agents.md` and `docs/rules.md` (Phase 1): connecting AI agents, and the player rulebook
