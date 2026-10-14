// Run reports (ARCHITECTURE §20.6): after every run, skipped ones included, the runner posts the run's summary to the
// app with the bot's own key (POST /api/v1/agent-runs). The app records it for the key's player, so a bot can only ever
// report its own runs; the runner still never touches the database. A failed report is logged and never changes the
// run's outcome or the exit code.

import { randomBytes } from 'node:crypto';
import type { BotConfig, RunnerConfig } from './config.js';
import { errorMessage, type Logger } from './log.js';
import { formatUsd } from './money.js';
import type { RunOptions, RunSummary } from './runner.js';

export const REPORT_TIMEOUT_MS = 10_000;
export const REPORT_RETRIES = 2;
const TOOL = /^[a-z][a-z0-9_]{0,63}$/;
const CODE = /^[A-Z][A-Z0-9_]{0,47}$/;
const MAX_TOOL_ENTRIES = 200;
const MESSAGE_MAX = 500;

/** Per tool, the calls per result code: `OK`, the error result's `code`, `ERROR`, `RUN_TOOL_BUDGET_EXHAUSTED`. */
export type ToolCounts = Map<string, Map<string, number>>;

export function countTool(counts: ToolCounts, tool: string, code: string | null | undefined, ok: boolean): void {
  const name = TOOL.test(tool) ? tool : 'unknown_tool';
  const result = ok ? 'OK' : code && CODE.test(code) ? code : 'ERROR';
  const byCode = counts.get(name) ?? new Map<string, number>();
  byCode.set(result, (byCode.get(result) ?? 0) + 1);
  counts.set(name, byCode);
}

export interface RunReport {
  runRef: string;
  trigger: 'SCHEDULE' | 'ONCE';
  slot: string | null;
  model: string;
  status: string;
  startedAt: string;
  endedAt: string;
  turns: number;
  toolCalls: number;
  toolErrors: number;
  orders: number;
  tokens: { input: number; cacheRead: number; cacheWrite: number; output: number };
  costUsd: string;
  daySpentUsd: string;
  dailyCapUsd: string;
  message: string | null;
  runnerVersion: string;
  tools: { tool: string; code: string; calls: number }[];
}

/** `20261012T134500Z-claude-a1b2c3`: the run's start (UTC), the bot and 6 random characters (the report's id). */
export function newRunRef(started: Date, bot: string): string {
  const ts = started.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}Z$/, 'Z');
  return `${ts}-${bot}-${randomBytes(3).toString('hex')}`;
}

export function buildReport(
  runRef: string,
  summary: RunSummary,
  opts: RunOptions,
  started: Date,
  config: RunnerConfig,
  version: string,
): RunReport {
  const u = summary.usage;
  const tools: RunReport['tools'] = [];
  for (const [tool, byCode] of summary.tools) {
    for (const [code, calls] of byCode) {
      if (tools.length < MAX_TOOL_ENTRIES) tools.push({ tool, code, calls });
    }
  }
  const message = summary.message ? summary.message.slice(0, MESSAGE_MAX) : null;
  return {
    runRef,
    trigger: opts.trigger === 'once' ? 'ONCE' : 'SCHEDULE', // a booked run reports as SCHEDULE at its time
    slot: opts.slot,
    model: summary.model,
    status: summary.status,
    startedAt: started.toISOString(),
    endedAt: new Date(started.getTime() + Math.max(0, summary.durationMs)).toISOString(),
    turns: summary.turns,
    toolCalls: summary.toolCalls,
    toolErrors: summary.toolErrors,
    orders: summary.orders,
    tokens: { input: u.input, cacheRead: u.cacheRead, cacheWrite: u.cacheWrite5m + u.cacheWrite1h, output: u.output },
    costUsd: formatUsd(summary.cost),
    daySpentUsd: formatUsd(summary.day),
    dailyCapUsd: config.dailyUsdCapText,
    message,
    runnerVersion: version.slice(0, 40),
    tools,
  };
}

/** `AGENT_REPORT_URL`, else `STOCKS_MCP_URL` with the path replaced by `/api/v1/agent-runs`. */
export function reportUrl(mcpUrl: string, override: string | undefined): string {
  if (override) return override;
  const u = new URL(mcpUrl);
  u.pathname = '/api/v1/agent-runs';
  u.search = '';
  u.hash = '';
  return u.toString();
}

export interface ReportDeps {
  logger: Logger;
  fetch: typeof fetch;
  /** Test hook: waits between attempts (default 1 s, 3 s). */
  sleep?: (ms: number) => Promise<void>;
}

/**
 * Posts `report` with the bot's key: a 10 s timeout per attempt and up to two retries on network errors and 5xx (the
 * same runRef, so the app stores it once). Returns true when the app answered 200 or 201. Never throws.
 */
export async function postReport(url: string, bot: BotConfig, report: RunReport, deps: ReportDeps): Promise<boolean> {
  const sleep = deps.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
  const body = JSON.stringify(report);
  let last = '';
  for (let attempt = 0; attempt <= REPORT_RETRIES; attempt++) {
    if (attempt > 0) await sleep(attempt === 1 ? 1000 : 3000);
    try {
      const res = await deps.fetch(url, {
        method: 'POST',
        headers: {
          authorization: `Bearer ${bot.stocksKey}`,
          'content-type': 'application/json',
          'x-stocks-client': `agent-runner/${bot.id}`,
        },
        body,
        signal: AbortSignal.timeout(REPORT_TIMEOUT_MS),
      });
      if (res.status === 200 || res.status === 201) return true;
      const text = (await res.text().catch(() => '')).slice(0, 300);
      last = `HTTP ${res.status} ${text}`;
      if (res.status < 500) break;                      // 4xx: retrying the same body cannot help
    } catch (e) {
      last = errorMessage(e);
    }
  }
  deps.logger.warn(`report failed: ${last} (run ${report.runRef}, status ${report.status})`, bot.id);
  return false;
}
