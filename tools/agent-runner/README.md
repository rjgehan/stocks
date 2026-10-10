# stocks agent runner

Runs the league's Claude and ChatGPT bots against the stocks MCP server on a schedule, with the same prompt, tools,
caps and memory for both. Spec: [ARCHITECTURE §20.5](../../docs/ARCHITECTURE.md). Operator guide (enable, run once,
logs, costs): [docs/agents.md §2.5](../../docs/agents.md).

```
src/main.ts        CLI: schedule (default), --once [--bot claude|chatgpt|all], --check
src/config.ts      environment → configuration; bots without keys, model or price are skipped
src/scheduler.ts   AGENT_SCHEDULE_ET slots in ET; both bots start together
src/runner.ts      one run: market clock, tools, the model-agnostic loop, caps, run log, stdout summary
src/mcp.ts         MCP Streamable HTTP client (bot key, X-Stocks-Client: agent-runner/<bot>)
src/tools.ts       MCP tool schemas → Anthropic custom tools / OpenAI Responses function tools; save_notes
src/llm/           the Anthropic Messages and OpenAI Responses adapters
src/pricing.ts     built-in per-model prices, AGENT_PRICE_* overrides, exact cost (BigInt pico-dollars)
src/store.ts       notes.md, spend.json under AGENT_DATA_DIR/<bot>/
src/report.ts      Phase 2: each run's summary to POST /api/v1/agent-runs with the bot's key (AGENT_REPORT_*)
prompts/system.md  the shared system prompt (copied verbatim into docs/agents.md; a backend test checks it)
```

Development (Node 22):

```sh
npm ci
npm run lint && npm run typecheck && npm test && npm run build
npm run test:e2e        # needs backend/target/stocks.jar (cd backend && ./mvnw -B -q package -DskipTests)
node dist/main.js --check
```

Tests never call Anthropic or OpenAI: they point the SDKs at local fake Messages and Responses APIs
(`test/fakes/llm.ts`). `test:e2e` starts the backend jar in profile `dev` on 127.0.0.1:18230 (management 18231;
override with `STOCKS_E2E_PORT` / `STOCKS_E2E_MANAGEMENT_PORT`, or reuse a running dev backend with `STOCKS_E2E_URL`
and `STOCKS_E2E_KEYS_FILE`) and drives its real MCP server.

The image (`Dockerfile`) is published by `release.yml` as `ghcr.io/<owner>/stocks:agent-runner-<tag>`.

Dependencies are pinned exactly, each to a release at least 14 days old when pinned. `npm audit` reports
GHSA-6qxp-vccf-f47h for `@modelcontextprotocol/sdk` < 1.31.0: it concerns the SDK's OAuth client (`authProvider`),
which the runner does not use (it sends a static `Authorization: Bearer` header). Move to 1.31.0 or later once that
release is 14 days old (from 2026-10-12).
