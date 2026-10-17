// An in-memory stand-in for the stocks MCP server: the real tools/list (fixture) and scripted tool handlers.

import { readFileSync } from 'node:fs';
import type { ConnectMcp, McpConnectOptions, McpConnection, ToolOutcome } from '../../src/mcp.js';
import type { JsonObject, McpToolInfo } from '../../src/tools.js';

export const FIXTURE_TOOLS: McpToolInfo[] = (
  JSON.parse(readFileSync(new URL('../fixtures/tools-list.json', import.meta.url), 'utf8')) as { tools: McpToolInfo[] }
).tools;

export type Handler = (args: JsonObject) => ToolOutcome;

export const ok = (value: unknown): ToolOutcome => ({
  isError: false,
  text: JSON.stringify(value),
  code: null,
  retryAfterS: null,
});

export const fail = (code: string, message: string, retryAfterS: number | null = null): ToolOutcome => ({
  isError: true,
  text: JSON.stringify({ code, message, hint: 'fake hint', fields: [], retry_after_s: retryAfterS }),
  code,
  retryAfterS,
});

export const TRADING_CLOCK = {
  now: '2026-10-12T13:45:00.000Z',
  nowEt: '2026-10-12T09:45:00-04:00',
  session: 'REGULAR',
  tradingDate: '2026-10-12',
  sessionEndsAt: '2026-10-12T20:00:00.000Z',
  nextSession: 'POST',
};

export const CLOSED_CLOCK = { ...TRADING_CLOCK, session: 'CLOSED', tradingDate: null };

/** Saturday 2026-10-17 10:00 ET: the stock market is closed, crypto pairs trade (24/7). */
export const WEEKEND_CRYPTO_CLOCK = {
  now: '2026-10-17T14:00:00.000Z',
  nowEt: '2026-10-17T10:00:00-04:00',
  session: 'CLOSED',
  tradingDate: null,
  sessionEndsAt: null,
  nextSession: 'PRE',
  crypto: { enabled: true, pairs: ['BTC/USD', 'ETH/USD'] },
};

export interface FakeMcp {
  connect: ConnectMcp;
  calls: { name: string; args: JsonObject }[];
  connects: McpConnectOptions[];
  closed: number;
}

export function fakeMcp(handlers: Record<string, Handler>, clock: unknown = TRADING_CLOCK): FakeMcp {
  const state: FakeMcp = {
    calls: [],
    connects: [],
    closed: 0,
    connect: async (opts) => {
      state.connects.push(opts);
      const conn: McpConnection = {
        tools: FIXTURE_TOOLS,
        async call(name, args) {
          state.calls.push({ name, args });
          const h = handlers[name];
          if (h) return h(args);
          if (name === 'get_market_clock') return ok(clock);
          return ok({ tool: name, echo: args });
        },
        async close() {
          state.closed++;
        },
      };
      return conn;
    },
  };
  return state;
}
