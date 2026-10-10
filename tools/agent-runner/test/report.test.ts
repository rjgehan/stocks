import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { loadConfig } from '../src/config.js';
import { Logger } from '../src/log.js';
import {
  buildReport,
  countTool,
  newRunRef,
  postReport,
  reportUrl,
  type RunReport,
  type ToolCounts,
} from '../src/report.js';
import { runBot, type RunSummary } from '../src/runner.js';
import { addSpend } from '../src/store.js';
import { ZERO_USAGE } from '../src/pricing.js';
import { CLOSED_CLOCK, fakeMcp } from './fakes/mcp.js';
import {
  bot,
  CLAUDE_STOCKS_KEY,
  MemorySink,
  type PostedReport,
  removeDir,
  reportFetch,
  tempDir,
  testConfig,
  testDeps,
  testEnv,
} from './helpers.js';
import { join } from 'node:path';

let dataDir: string;
beforeEach(() => {
  dataDir = tempDir();
});
afterEach(() => removeDir(dataDir));

function summary(extra: Partial<RunSummary> = {}): RunSummary {
  return {
    bot: 'claude',
    model: 'claude-opus-5-5',
    status: 'completed',
    turns: 2,
    toolCalls: 3,
    toolErrors: 1,
    orders: 1,
    usage: { ...ZERO_USAGE, input: 10, cacheRead: 20, cacheWrite5m: 3, cacheWrite1h: 4, output: 5 },
    cost: 183_400_000_000n,
    day: 1_234_567_890_123n,
    durationMs: 94_000,
    logPath: null,
    message: null,
    tools: new Map(),
    ...extra,
  };
}

describe('run reports (§20.6)', () => {
  it('counts tool calls by tool and result code, keeping names and codes the app accepts', () => {
    const counts: ToolCounts = new Map();
    countTool(counts, 'get_quotes', null, true);
    countTool(counts, 'get_quotes', null, true);
    countTool(counts, 'place_order', 'RATE_LIMITED', false);
    countTool(counts, 'place_order', null, false);
    countTool(counts, 'place_order', 'not-a-code', false);
    countTool(counts, 'Bad Tool!', 'UNKNOWN_TOOL', false);
    expect([...counts].map(([t, c]) => [t, Object.fromEntries(c)])).toEqual([
      ['get_quotes', { OK: 2 }],
      ['place_order', { RATE_LIMITED: 1, ERROR: 2 }],
      ['unknown_tool', { UNKNOWN_TOOL: 1 }],
    ]);
  });

  it('builds the report body: upper-case trigger, decimal strings, cache writes summed', () => {
    const tools: ToolCounts = new Map([['get_quotes', new Map([['OK', 2]])]]);
    const started = new Date('2026-10-12T13:45:00.123Z');
    const config = testConfig({ dataDir });
    const r = buildReport('ref-12345678', summary({ tools, message: 'x'.repeat(600) }), {
      trigger: 'schedule',
      slot: '09:45',
      requireTradingDay: true,
    }, started, config, '1.2.0');
    expect(r).toMatchObject({
      runRef: 'ref-12345678',
      trigger: 'SCHEDULE',
      slot: '09:45',
      status: 'completed',
      startedAt: '2026-10-12T13:45:00.123Z',
      endedAt: '2026-10-12T13:46:34.123Z',
      tokens: { input: 10, cacheRead: 20, cacheWrite: 7, output: 5 },
      costUsd: '0.183400',
      daySpentUsd: '1.234568',
      dailyCapUsd: '1.00',
      runnerVersion: '1.2.0',
      tools: [{ tool: 'get_quotes', code: 'OK', calls: 2 }],
    });
    expect(r.message).toHaveLength(500);
    expect(newRunRef(started, 'chatgpt')).toMatch(/^20261012T134500Z-chatgpt-[0-9a-f]{6}$/);
  });

  it('posts to the app next to the MCP endpoint unless AGENT_REPORT_URL says otherwise, or not at all', () => {
    expect(reportUrl('http://app:8080/mcp', undefined)).toBe('http://app:8080/api/v1/agent-runs');
    expect(reportUrl('https://stocks.example/mcp?x=1', undefined)).toBe('https://stocks.example/api/v1/agent-runs');
    expect(reportUrl('http://app:8080/mcp', 'http://other/runs')).toBe('http://other/runs');
    expect(loadConfig(testEnv({ dataDir }), 'p').reportUrl).toBe('http://127.0.0.1:9/api/v1/agent-runs');
    expect(loadConfig(testEnv({ dataDir, extra: { AGENT_REPORT_RUNS: 'false' } }), 'p').reportUrl).toBeNull();
    expect(() => loadConfig(testEnv({ dataDir, extra: { AGENT_REPORT_RUNS: 'maybe' } }), 'p')).toThrow(/AGENT_REPORT_RUNS/);
    expect(() => loadConfig(testEnv({ dataDir, extra: { AGENT_REPORT_URL: 'ftp://x' } }), 'p')).toThrow(/AGENT_REPORT_URL/);
  });

  it('retries network errors and 5xx with the same runRef, gives up on 4xx, and never throws', async () => {
    const config = testConfig({ dataDir });
    const claude = bot(config, 'claude');
    const body = { runRef: 'ref-retry-001', status: 'completed' } as RunReport;
    const sink = new MemorySink();
    const logger = new Logger(config.secrets, sink);
    const seen: string[] = [];
    let n = 0;
    const flaky = (async (_url: string, init: RequestInit) => {
      seen.push(JSON.parse(String(init.body)).runRef);
      n++;
      if (n === 1) throw new TypeError('fetch failed');
      if (n === 2) return new Response('busy', { status: 503 });
      return new Response('{}', { status: 200 });
    }) as unknown as typeof fetch;
    expect(await postReport('http://x/api', claude, body, { logger, fetch: flaky, sleep: async () => undefined })).toBe(true);
    expect(seen).toEqual(['ref-retry-001', 'ref-retry-001', 'ref-retry-001']);

    const posted: PostedReport[] = [];
    expect(await postReport('http://x/api', claude, body, { logger, fetch: reportFetch(posted, 400), sleep: async () => undefined }))
      .toBe(false);
    expect(posted).toHaveLength(1);
    const down = (async () => {
      throw new TypeError('connect ECONNREFUSED');
    }) as unknown as typeof fetch;
    expect(await postReport('http://x/api', claude, body, { logger, fetch: down, sleep: async () => undefined })).toBe(false);
    expect(sink.text).toContain('report failed: HTTP 400');
    expect(sink.text).toContain('report failed: connect ECONNREFUSED');
    expect(sink.text).not.toContain(CLAUDE_STOCKS_KEY);
  });

  it('reports skipped runs too, and a failed report changes nothing', async () => {
    const config = testConfig({ dataDir });
    addSpend(join(dataDir, 'claude'), '2026-10-12', 1_000_000_000_000n, 1, new Date('2026-10-12T13:00:00Z'));
    const deps = testDeps(config, fakeMcp({}).connect);
    const s = await runBot(bot(config, 'claude'), deps, { trigger: 'schedule', slot: '09:45', requireTradingDay: true });
    expect(s.status).toBe('skipped_daily_cap');
    expect(deps.reports.map((r) => r.body.status)).toEqual(['skipped_daily_cap']);
    expect(deps.reports[0]?.body.daySpentUsd).toBe('1.000000');
    expect(deps.reports[0]?.body.tools).toEqual([]);

    const closed = testDeps(config, fakeMcp({}, CLOSED_CLOCK).connect, { fetch: reportFetch([], 500) });
    const skipped = await runBot(bot(config, 'chatgpt'), closed, { trigger: 'schedule', slot: '09:45', requireTradingDay: true });
    expect(skipped.status).toBe('skipped_non_trading_day');
    expect(closed.sink.text).toContain('report failed: HTTP 500');

    const off = testConfig({ dataDir, extra: { AGENT_REPORT_RUNS: 'false' } });
    const quiet = testDeps(off, fakeMcp({}, CLOSED_CLOCK).connect);
    await runBot(bot(off, 'chatgpt'), quiet, { trigger: 'schedule', slot: '12:30', requireTradingDay: true });
    expect(quiet.reports).toEqual([]);
  });
});
