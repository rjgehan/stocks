import { existsSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { hostname } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { runBot } from '../src/runner.js';
import { addSpend, readSpend } from '../src/store.js';
import {
  anthropicServer,
  claudeReply,
  fnCall,
  lastToolResults,
  message,
  openaiReply,
  openaiServer,
  type ScriptedLlmServer,
  text,
  toolUse,
} from './fakes/llm.js';
import { CLOSED_CLOCK, fail, fakeMcp, FIXTURE_TOOLS, ok } from './fakes/mcp.js';
import {
  bot,
  CHATGPT_STOCKS_KEY,
  CLAUDE_LLM_KEY,
  CLAUDE_STOCKS_KEY,
  OPENAI_LLM_KEY,
  readRunLog,
  removeDir,
  runLogs,
  tempDir,
  testConfig,
  testDeps,
} from './helpers.js';

const SECRETS = [CLAUDE_LLM_KEY, OPENAI_LLM_KEY, CLAUDE_STOCKS_KEY, CHATGPT_STOCKS_KEY];
const ORDER = {
  client_order_id: 'claude-20261012-1',
  symbol: 'AAPL',
  side: 'BUY',
  type: 'LIMIT',
  qty: '1',
  limit_price: '227.40',
  rationale: 'Test rationale',
};

let dataDir: string;
let servers: ScriptedLlmServer[] = [];

beforeEach(() => {
  dataDir = tempDir();
  servers = [];
});

afterEach(async () => {
  await Promise.all(servers.map((s) => s.close()));
  removeDir(dataDir);
});

async function claudeFake(...steps: Parameters<typeof anthropicServer>[0]): Promise<ScriptedLlmServer> {
  const s = await anthropicServer(steps).start();
  servers.push(s);
  return s;
}

async function openaiFake(...steps: Parameters<typeof openaiServer>[0]): Promise<ScriptedLlmServer> {
  const s = await openaiServer(steps).start();
  servers.push(s);
  return s;
}

function expectNoSecrets(...texts: string[]): void {
  for (const t of texts) for (const s of SECRETS) expect(t).not.toContain(s);
}

describe('Claude run (Messages API)', () => {
  it('lists tools, executes the proposed calls, saves notes and logs the run', async () => {
    const llm = await claudeFake(
      () => claudeReply([text('Checking quotes.'), toolUse('tu_1', 'get_quotes', { symbols: ['AAPL'] })]),
      () =>
        claudeReply([
          toolUse('tu_2', 'place_order', ORDER),
          toolUse('tu_3', 'save_notes', { text: 'Bought 1 AAPL at 227.40 limit.' }),
        ]),
      () => claudeReply([text('Placed one limit order and saved notes.')], { input_tokens: 2000, output_tokens: 50 }),
    );
    const mcp = fakeMcp({
      get_quotes: () => ok({ quotes: [{ symbol: 'AAPL', bid: '227.30', ask: '227.50' }] }),
      place_order: () => ok({ order: { id: 'ord_1', clientOrderId: ORDER.client_order_id }, replayed: false }),
    });
    const config = testConfig({ dataDir, anthropicUrl: llm.url });
    const deps = testDeps(config, mcp.connect);

    const s = await runBot(bot(config, 'claude'), deps, { trigger: 'schedule', slot: '09:45', requireTradingDay: true });

    expect(s.status).toBe('completed');
    expect(s.turns).toBe(3);
    expect(s.toolCalls).toBe(3);
    expect(s.orders).toBe(1);
    expect(s.toolErrors).toBe(0);
    // claude-opus-5-5: $4 in / $20 out per MTok → 1000 in + 100 out = $0.006 twice, 2000 in + 50 out = $0.009
    expect(s.cost).toBe(21_000_000_000n);
    expect(readSpend(join(dataDir, 'claude'), '2026-10-12')).toMatchObject({ pico: 21_000_000_000n, runs: 1 });

    // The MCP connection: the bot's own key and client name; the clock first, then the model's calls in order.
    expect(mcp.connects[0]).toMatchObject({ stocksKey: CLAUDE_STOCKS_KEY, bot: 'claude' });
    expect(mcp.calls.map((c) => c.name)).toEqual(['get_market_clock', 'get_quotes', 'place_order']);
    expect(mcp.calls[2]?.args).toEqual(ORDER);
    expect(mcp.closed).toBe(1);

    // The requests: all 40 MCP tools + schedule_run, cancel_scheduled_run and save_notes as plain custom tools, the
    // shared system prompt, caching on.
    const first = llm.calls[0];
    expect(first?.headers['x-api-key']).toBe(CLAUDE_LLM_KEY);
    expect(first?.body.model).toBe('claude-opus-5-5');
    expect(first?.body.system).toBe('SYSTEM PROMPT (test)');
    expect(first?.body.cache_control).toEqual({ type: 'ephemeral' });
    expect(first?.body.tools).toHaveLength(FIXTURE_TOOLS.length + 3);
    expect(first?.body.tools.map((t: { name: string }) => t.name)).toContain('save_notes');
    for (const t of first?.body.tools ?? []) {
      expect(t.input_schema.type).toBe('object');
      expect(t.input_schema.$schema).toBeUndefined();
    }
    const opening = first?.body.messages[0].content as string;
    expect(opening).toContain('"tradingDate":"2026-10-12"');
    expect(opening).toContain('at most 40 tool calls and 300 seconds');
    expect(opening).toContain('You have no notes yet');
    // Turn 2 carries the get_quotes result verbatim; turn 3 both results, the assistant turns unchanged.
    expect(lastToolResults(llm.calls[1]!)).toEqual([
      expect.objectContaining({ tool_use_id: 'tu_1', is_error: false, content: expect.stringContaining('227.30') }),
    ]);
    const results3 = lastToolResults(llm.calls[2]!);
    expect(results3.map((r: { tool_use_id: string }) => r.tool_use_id)).toEqual(['tu_2', 'tu_3']);
    expect(results3[1].content).toContain('"saved":true');
    expect(llm.calls[2]?.body.messages).toHaveLength(5);

    expect(readFileSync(join(dataDir, 'claude', 'notes.md'), 'utf8')).toBe('Bought 1 AAPL at 227.40 limit.');

    const log = readRunLog(s.logPath);
    expect(log.map((l) => l.type)).toEqual([
      'run_start',
      'market_clock',
      'llm_response',
      'tool_call',
      'llm_response',
      'tool_call',
      'tool_call',
      'llm_response',
      'run_end',
    ]);
    expect(log[0]).toMatchObject({ bot: 'claude', model: 'claude-opus-5-5', trigger: 'schedule', slot: '09:45', tools: 43 });
    expect(log[0]?.promptSha256).toMatch(/^[0-9a-f]{64}$/);
    expect(log[3]).toMatchObject({ name: 'get_quotes', isError: false, code: null, executed: true });
    expect(log[8]).toMatchObject({ status: 'completed', costUsd: '0.021000', orders: 1, summary: expect.stringContaining('Placed') });
    expect(deps.sink.text).toMatch(/run bot=claude model=claude-opus-5-5 trigger=schedule@09:45 status=completed .*cost=\$0\.021000/);
    expectNoSecrets(deps.sink.text, readFileSync(s.logPath!, 'utf8'));

    // §20.6: the run's summary went to the app with the bot's own key
    expect(deps.reports).toHaveLength(1);
    const r = deps.reports[0]!;
    expect(r.url).toBe('http://127.0.0.1:9/api/v1/agent-runs');
    expect(r.headers.authorization).toBe(`Bearer ${CLAUDE_STOCKS_KEY}`);
    expect(r.headers['x-stocks-client']).toBe('agent-runner/claude');
    expect(r.body).toMatchObject({
      trigger: 'SCHEDULE',
      slot: '09:45',
      model: 'claude-opus-5-5',
      status: 'completed',
      turns: 3,
      toolCalls: 3,
      toolErrors: 0,
      orders: 1,
      costUsd: '0.021000',
      daySpentUsd: '0.021000',
      dailyCapUsd: '1.00',
      runnerVersion: '1.0.0-test',
      tokens: { input: 4000, cacheRead: 0, cacheWrite: 0, output: 250 },
    });
    expect(r.body.runRef).toMatch(/^20261012T134500Z-claude-[0-9a-f]{6}$/);
    expect(r.body.tools).toEqual([
      { tool: 'get_quotes', code: 'OK', calls: 1 },
      { tool: 'place_order', code: 'OK', calls: 1 },
      { tool: 'save_notes', code: 'OK', calls: 1 },
    ]);
    expect(Date.parse(r.body.endedAt)).toBeGreaterThanOrEqual(Date.parse(r.body.startedAt));
  });

  it('reports error results with is_error and keeps going; unknown tools and bad arguments are errors too', async () => {
    const llm = await claudeFake(
      () =>
        claudeReply([
          toolUse('a', 'place_order', { ...ORDER, rationale: '' }),
          toolUse('b', 'no_such_tool', {}),
          toolUse('c', 'save_notes', { text: 42 }),
        ]),
      () => claudeReply([text('Done.')]),
    );
    const mcp = fakeMcp({ place_order: () => fail('RATIONALE_REQUIRED', 'AI players must send a rationale') });
    const config = testConfig({ dataDir, anthropicUrl: llm.url });
    const s = await runBot(bot(config, 'claude'), testDeps(config, mcp.connect), {
      trigger: 'once',
      slot: null,
      requireTradingDay: false,
    });
    expect(s.status).toBe('completed');
    expect(s.toolErrors).toBe(3);
    expect(s.orders).toBe(0);
    const results = lastToolResults(llm.calls[1]!);
    expect(results.map((r: { is_error: boolean }) => r.is_error)).toEqual([true, true, true]);
    expect(results[0].content).toContain('RATIONALE_REQUIRED');
    expect(results[1].content).toContain('UNKNOWN_TOOL');
    expect(results[2].content).toContain('VALIDATION_FAILED');
    expect(mcp.calls.map((c) => c.name)).toEqual(['get_market_clock', 'place_order']);
    const codes = readRunLog(s.logPath)
      .filter((l) => l.type === 'tool_call')
      .map((l) => l.code);
    expect(codes).toEqual(['RATIONALE_REQUIRED', 'UNKNOWN_TOOL', 'VALIDATION_FAILED']);
  });

  it('retries 429 and 5xx answers with backoff (SDK retries) and logs them', async () => {
    const llm = await claudeFake(
      () => ({ status: 429, headers: { 'retry-after-ms': '5' }, body: { type: 'error', error: { type: 'rate_limit_error', message: 'slow down' } } }),
      () => ({ status: 529, headers: { 'retry-after-ms': '5' }, body: { type: 'error', error: { type: 'overloaded_error', message: 'busy' } } }),
      () => claudeReply([text('Nothing to do today.')]),
    );
    const config = testConfig({ dataDir, anthropicUrl: llm.url });
    const deps = testDeps(config, fakeMcp({}).connect);
    const s = await runBot(bot(config, 'claude'), deps, { trigger: 'once', slot: null, requireTradingDay: false });
    expect(s.status).toBe('completed');
    expect(llm.calls).toHaveLength(3);
    const http = readRunLog(s.logPath).filter((l) => l.type === 'llm_http');
    expect(http.map((l) => l.status)).toEqual([429, 529]);
    expect(http[0]?.path).toBe('/v1/messages');
    expect(deps.sink.text).toContain('LLM API answered 429; retrying with backoff');
  });

  it('stops at the tool-call cap: extra calls are not executed and the final turn forbids tools', async () => {
    const llm = await claudeFake(
      () =>
        claudeReply([
          toolUse('1', 'get_account', {}),
          toolUse('2', 'get_positions', {}),
          toolUse('3', 'get_orders', {}),
        ]),
      () => claudeReply([text('Out of budget; summary.')]),
    );
    const mcp = fakeMcp({});
    const config = testConfig({ dataDir, anthropicUrl: llm.url, extra: { AGENT_MAX_TOOL_CALLS: '2' } });
    const s = await runBot(bot(config, 'claude'), testDeps(config, mcp.connect), {
      trigger: 'once',
      slot: null,
      requireTradingDay: false,
    });
    expect(s.status).toBe('tool_cap');
    expect(s.toolCalls).toBe(2);
    expect(mcp.calls.map((c) => c.name)).toEqual(['get_market_clock', 'get_account', 'get_positions']);
    const final = llm.calls[1]!;
    expect(final.body.tool_choice).toEqual({ type: 'none' });
    const results = lastToolResults(final);
    expect(results[2]).toMatchObject({ tool_use_id: '3', is_error: true });
    expect(results[2].content).toContain('RUN_TOOL_BUDGET_EXHAUSTED');
    const last = final.body.messages[final.body.messages.length - 1].content;
    expect(last[last.length - 1]).toMatchObject({ type: 'text', text: expect.stringContaining('budget') });
  });

  it('stops at the time cap', async () => {
    const llm = await claudeFake(() => ({ ...claudeReply([text('late')]), delayMs: 3000 }));
    const config = testConfig({ dataDir, anthropicUrl: llm.url });
    const t0 = Date.now();
    const s = await runBot(bot(config, 'claude'), testDeps(config, fakeMcp({}).connect, { runTimeoutMs: 300 }), {
      trigger: 'once',
      slot: null,
      requireTradingDay: false,
    });
    expect(s.status).toBe('time_cap');
    expect(Date.now() - t0).toBeLessThan(2500);
    expect(readRunLog(s.logPath).at(-1)).toMatchObject({ type: 'run_end', status: 'time_cap' });
  });

  it('ends as aborted on SIGTERM (shutdown signal) without waiting for the model', async () => {
    const llm = await claudeFake(() => ({ ...claudeReply([text('late')]), delayMs: 3000 }));
    const config = testConfig({ dataDir, anthropicUrl: llm.url });
    const shutdown = new AbortController();
    setTimeout(() => shutdown.abort(new Error('SIGTERM')), 100);
    const s = await runBot(bot(config, 'claude'), testDeps(config, fakeMcp({}).connect, { shutdown: shutdown.signal }), {
      trigger: 'once',
      slot: null,
      requireTradingDay: false,
    });
    expect(s.status).toBe('aborted');
  });

  it('enforces the daily dollar cap: mid-run it stops before executing calls, later runs are skipped', async () => {
    const llm = await claudeFake(
      () => claudeReply([toolUse('1', 'place_order', ORDER)], { input_tokens: 100_000, output_tokens: 1000 }),
    );
    const mcp = fakeMcp({});
    // $0.40 + $0.02 = $0.42 for the first response, over a $0.25 cap.
    const config = testConfig({ dataDir, anthropicUrl: llm.url, extra: { AGENT_DAILY_USD_CAP: '0.25' } });
    const deps = testDeps(config, mcp.connect);
    const s = await runBot(bot(config, 'claude'), deps, { trigger: 'schedule', slot: '09:45', requireTradingDay: true });
    expect(s.status).toBe('daily_cap');
    expect(s.cost).toBe(420_000_000_000n);
    expect(mcp.calls.map((c) => c.name)).toEqual(['get_market_clock']);

    const again = await runBot(bot(config, 'claude'), deps, { trigger: 'schedule', slot: '12:30', requireTradingDay: true });
    expect(again.status).toBe('skipped_daily_cap');
    expect(llm.calls).toHaveLength(1);
    expect(deps.sink.text).toContain('daily cap reached: $0.420000 of $0.25 spent on 2026-10-12 (ET)');
  });

  it('a new ET day starts from zero', async () => {
    mkdirSync(join(dataDir, 'claude'), { recursive: true });
    writeFileSync(
      join(dataDir, 'claude', 'spend.json'),
      JSON.stringify({ dateEt: '2026-10-09', usd: '5.000000', pico: '5000000000000', runs: 3 }),
    );
    const llm = await claudeFake(() => claudeReply([text('ok')]));
    const config = testConfig({ dataDir, anthropicUrl: llm.url });
    const s = await runBot(bot(config, 'claude'), testDeps(config, fakeMcp({}).connect), {
      trigger: 'schedule',
      slot: '09:45',
      requireTradingDay: true,
    });
    expect(s.status).toBe('completed');
    expect(readSpend(join(dataDir, 'claude'), '2026-10-12')).toMatchObject({ pico: 6_000_000_000n, runs: 1 });
  });

  it('skips a non-trading day on schedule without calling the model; a manual run still runs', async () => {
    const llm = await claudeFake(() => claudeReply([text('Market closed: planning only.')]));
    const mcp = fakeMcp({}, CLOSED_CLOCK);
    const config = testConfig({ dataDir, anthropicUrl: llm.url });
    const deps = testDeps(config, mcp.connect);
    const s = await runBot(bot(config, 'claude'), deps, { trigger: 'schedule', slot: '09:45', requireTradingDay: true });
    expect(s.status).toBe('skipped_non_trading_day');
    expect(llm.calls).toHaveLength(0);
    expect(runLogs(dataDir, 'claude')).toEqual([]);
    expect(mcp.closed).toBe(1);

    const manual = await runBot(bot(config, 'claude'), deps, { trigger: 'once', slot: null, requireTradingDay: false });
    expect(manual.status).toBe('completed');
    expect(llm.calls).toHaveLength(1);
  });

  it('retries RATE_LIMITED tool results once after retry_after_s', async () => {
    const llm = await claudeFake(
      () => claudeReply([toolUse('1', 'get_quotes', { symbols: ['SPY'] })]),
      () => claudeReply([text('ok')]),
    );
    let n = 0;
    const mcp = fakeMcp({ get_quotes: () => (++n === 1 ? fail('RATE_LIMITED', 'slow down', 2) : ok({ quotes: [] })) });
    const config = testConfig({ dataDir, anthropicUrl: llm.url });
    const slept: number[] = [];
    const deps = testDeps(config, mcp.connect, {
      sleep: async (ms) => {
        slept.push(ms);
      },
    });
    const s = await runBot(bot(config, 'claude'), deps, { trigger: 'once', slot: null, requireTradingDay: false });
    expect(s.status).toBe('completed');
    expect(slept).toEqual([2000]);
    expect(s.toolCalls).toBe(1);
    expect(lastToolResults(llm.calls[1]!)[0].is_error).toBe(false);
    expect(readRunLog(s.logPath).find((l) => l.type === 'tool_call')).toMatchObject({ rateLimitRetry: true });
  });

  it('injects the notes into the next run and refuses notes over 8 KB', async () => {
    const big = 'x'.repeat(8193);
    const llm = await claudeFake(
      () => claudeReply([toolUse('1', 'save_notes', { text: big })]),
      () => claudeReply([toolUse('2', 'save_notes', { text: 'Plan: watch NVDA. ✓' })]),
      () => claudeReply([text('saved')]),
      () => claudeReply([text('second run')]),
    );
    const config = testConfig({ dataDir, anthropicUrl: llm.url });
    const deps = testDeps(config, fakeMcp({}).connect);
    await runBot(bot(config, 'claude'), deps, { trigger: 'once', slot: null, requireTradingDay: false });
    const tooBig = lastToolResults(llm.calls[1]!)[0];
    expect(tooBig.is_error).toBe(true);
    expect(tooBig.content).toContain('NOTES_TOO_LARGE');
    expect(tooBig.content).toContain('8193 bytes');

    await runBot(bot(config, 'claude'), deps, { trigger: 'once', slot: null, requireTradingDay: false });
    const opening = llm.calls[3]?.body.messages[0].content as string;
    expect(opening).toContain('<<<NOTES\nPlan: watch NVDA. ✓\nNOTES>>>');
    expect(opening).toContain('notes.md, 21 of 8192 bytes');
  });

  it('cuts very large tool results and says so', async () => {
    const llm = await claudeFake(
      () => claudeReply([toolUse('1', 'get_bars', { symbol: 'SPY', timeframe: '1Min', limit: 1000 })]),
      () => claudeReply([text('ok')]),
    );
    const mcp = fakeMcp({ get_bars: () => ok({ rows: 'y'.repeat(60_000) }) });
    const config = testConfig({ dataDir, anthropicUrl: llm.url });
    await runBot(bot(config, 'claude'), testDeps(config, mcp.connect), { trigger: 'once', slot: null, requireTradingDay: false });
    const content = lastToolResults(llm.calls[1]!)[0].content as string;
    expect(content.length).toBeLessThan(41_000);
    expect(content).toContain('the agent runner cut this result');
  });

  it('a failed MCP connection ends the run as an error without a model call', async () => {
    const llm = await claudeFake(() => claudeReply([text('never')]));
    const config = testConfig({ dataDir, anthropicUrl: llm.url });
    const deps = testDeps(config, async () => {
      throw new Error(`HTTP 401 for key ${CLAUDE_STOCKS_KEY}`);
    });
    const s = await runBot(bot(config, 'claude'), deps, { trigger: 'once', slot: null, requireTradingDay: false });
    expect(s.status).toBe('error');
    expect(llm.calls).toHaveLength(0);
    expect(deps.sink.text).toContain('status=error');
    expectNoSecrets(deps.sink.text);
  });
});

describe('ChatGPT run (Responses API)', () => {
  it('chains responses with previous_response_id and sends function outputs', async () => {
    const llm = await openaiFake(
      () => openaiReply([fnCall('call_1', 'get_quotes', { symbols: ['MSFT'] })]),
      () =>
        openaiReply(
          [fnCall('call_2', 'place_order', { ...ORDER, client_order_id: 'chatgpt-20261012-1' }), fnCall('call_3', 'get_asset', { symbol: '' })],
          { input_tokens: 3000, output_tokens: 200, cached_tokens: 2000 },
        ),
      () => openaiReply([message('Bought one MSFT share.')]),
    );
    const mcp = fakeMcp({
      get_quotes: () => ok({ quotes: [{ symbol: 'MSFT', last: '512.10' }] }),
      get_asset: () => fail('SYMBOL_NOT_FOUND', 'unknown symbol'),
    });
    const config = testConfig({ dataDir, openaiUrl: llm.url });
    const deps = testDeps(config, mcp.connect);
    const s = await runBot(bot(config, 'chatgpt'), deps, { trigger: 'schedule', slot: '12:30', requireTradingDay: true });

    expect(s.status).toBe('completed');
    expect(s.toolCalls).toBe(3);
    expect(s.orders).toBe(1);
    expect(s.toolErrors).toBe(1);
    // gpt-5.5: $5 in / $0.50 cached / $30 out per MTok. 1000/100: $0.008 (×2); 1000 uncached + 2000 cached + 200 out:
    // $0.005 + $0.001 + $0.006 = $0.012.
    expect(s.cost).toBe(28_000_000_000n);
    expect(mcp.connects[0]).toMatchObject({ stocksKey: CHATGPT_STOCKS_KEY, bot: 'chatgpt' });

    const [r1, r2, r3] = llm.calls;
    expect(r1?.headers.authorization).toBe(`Bearer ${OPENAI_LLM_KEY}`);
    expect(r1?.body).toMatchObject({ model: 'gpt-5.5', instructions: 'SYSTEM PROMPT (test)', store: true });
    expect(r1?.body.previous_response_id).toBeUndefined();
    expect(r1?.body.input[0]).toMatchObject({ role: 'user' });
    expect(r1?.body.tools).toHaveLength(FIXTURE_TOOLS.length + 3);
    for (const t of r1?.body.tools ?? []) {
      expect(t).toMatchObject({ type: 'function', strict: false });
      expect(t.parameters.type).toBe('object');
      expect(t.parameters.$schema).toBeUndefined();
    }
    expect(r2?.body.previous_response_id).toMatch(/^resp_fake_/);
    expect(r2?.body.instructions).toBe('SYSTEM PROMPT (test)');
    expect(r2?.body.input).toEqual([
      { type: 'function_call_output', call_id: 'call_1', output: expect.stringContaining('512.10') },
    ]);
    expect(r3?.body.input.map((i: { call_id: string }) => i.call_id)).toEqual(['call_2', 'call_3']);
    expect(r3?.body.input[1].output).toMatch(/^Tool error \(isError=true\): \{"code":"SYMBOL_NOT_FOUND"/);
    expect(readRunLog(s.logPath).at(-1)).toMatchObject({ type: 'run_end', summary: 'Bought one MSFT share.' });
    expectNoSecrets(deps.sink.text, readFileSync(s.logPath!, 'utf8'));
  });

  it('retries a 500 and ends a truncated response as max_tokens without executing its calls', async () => {
    const llm = await openaiFake(
      () => ({ status: 500, headers: { 'retry-after-ms': '5' }, body: { error: { message: 'oops', type: 'server_error' } } }),
      () => openaiReply([fnCall('c1', 'place_order', ORDER)], undefined, 'incomplete'),
    );
    const mcp = fakeMcp({});
    const config = testConfig({ dataDir, openaiUrl: llm.url });
    const s = await runBot(bot(config, 'chatgpt'), testDeps(config, mcp.connect), {
      trigger: 'once',
      slot: null,
      requireTradingDay: false,
    });
    expect(s.status).toBe('max_tokens');
    expect(mcp.calls.map((c) => c.name)).toEqual(['get_market_clock']);
    expect(readRunLog(s.logPath).filter((l) => l.type === 'llm_http').map((l) => l.status)).toEqual([500]);
  });

  it('the final turn after the budget uses tool_choice none and a user note', async () => {
    const llm = await openaiFake(
      () => openaiReply([fnCall('c1', 'get_account', {}), fnCall('c2', 'get_positions', {})]),
      () => openaiReply([message('summary')]),
    );
    const config = testConfig({ dataDir, openaiUrl: llm.url, extra: { AGENT_MAX_TOOL_CALLS: '1' } });
    const s = await runBot(bot(config, 'chatgpt'), testDeps(config, fakeMcp({}).connect), {
      trigger: 'once',
      slot: null,
      requireTradingDay: false,
    });
    expect(s.status).toBe('tool_cap');
    const final = llm.calls[1]!;
    expect(final.body.tool_choice).toBe('none');
    expect(final.body.input[1].output).toContain('RUN_TOOL_BUDGET_EXHAUSTED');
    expect(final.body.input[2]).toMatchObject({ role: 'user', content: expect.stringContaining('budget') });
  });
});

describe('booking extra runs', () => {
  it('schedule_run and cancel_scheduled_run book, list and cancel the bot\'s own extra runs', async () => {
    const llm = await claudeFake(
      () =>
        claudeReply([
          toolUse('b1', 'schedule_run', { at_et: '14:15', reason: 'NVDA reports at 14:00; re-check the position' }),
          toolUse('b2', 'schedule_run', { at_et: '12:40', reason: 'too close to the 12:45 run' }),
          toolUse('b3', 'cancel_scheduled_run', { id: 'rnope00' }),
        ]),
      () => claudeReply([text('Booked one extra run.')]),
    );
    const mcp = fakeMcp({});
    const config = testConfig({ dataDir, anthropicUrl: llm.url });
    const s = await runBot(bot(config, 'claude'), testDeps(config, mcp.connect), {
      trigger: 'schedule',
      slot: '09:30',
      requireTradingDay: true,
    });

    expect(s.status).toBe('completed');
    expect(s.toolErrors).toBe(2);
    expect(mcp.calls.map((c) => c.name)).toEqual(['get_market_clock']); // runner-local: never sent to the server
    const opening = llm.calls[0]?.body.messages[0].content as string;
    expect(opening).toContain('Scheduled runs happen at 09:30, 12:45, 15:50 ET on trading days. To act at another time, book an extra run with schedule_run (at most 4 per day).');
    expect(opening).toContain('Your pending extra runs: none.');
    const [booked, tooClose, unknown] = lastToolResults(llm.calls[1]!);
    expect(booked.is_error).toBe(false);
    const result = JSON.parse(booked.content as string);
    expect(result.scheduled).toMatchObject({ at_et: '2026-10-12 14:15', reason: 'NVDA reports at 14:00; re-check the position' });
    expect(result.pending).toHaveLength(1);
    expect(tooClose).toMatchObject({ is_error: true, content: expect.stringContaining('"code":"RUN_TIME_NOT_ALLOWED"') });
    expect(unknown).toMatchObject({ is_error: true, content: expect.stringContaining('"code":"NOT_FOUND"') });
    const file = JSON.parse(readFileSync(join(dataDir, 'claude', 'requested-runs.json'), 'utf8'));
    expect(file.pending).toEqual([expect.objectContaining({ id: result.scheduled.id, atEt: '2026-10-12 14:15' })]);

    // The next run lists it; cancelling it empties the list.
    const llm2 = await claudeFake(
      () => claudeReply([toolUse('c1', 'cancel_scheduled_run', { id: result.scheduled.id })]),
      () => claudeReply([text('Cancelled.')]),
    );
    const config2 = testConfig({ dataDir, anthropicUrl: llm2.url });
    await runBot(bot(config2, 'claude'), testDeps(config2, fakeMcp({}).connect), { trigger: 'once', slot: null, requireTradingDay: false });
    expect(llm2.calls[0]?.body.messages[0].content).toContain(
      `Your pending extra runs: ${result.scheduled.id} at 2026-10-12 14:15 ET ("NVDA reports at 14:00; re-check the position").`,
    );
    expect(JSON.parse(lastToolResults(llm2.calls[1]!)[0].content as string)).toMatchObject({ pending: [] });
  });

  it('AGENT_EXTRA_RUNS_PER_DAY=0 offers neither tool and treats a call as unknown', async () => {
    const llm = await claudeFake(
      () => claudeReply([toolUse('b1', 'schedule_run', { at_et: '14:15', reason: 'x' })]),
      () => claudeReply([text('ok')]),
    );
    const config = testConfig({ dataDir, anthropicUrl: llm.url, extra: { AGENT_EXTRA_RUNS_PER_DAY: '0' } });
    await runBot(bot(config, 'claude'), testDeps(config, fakeMcp({}).connect), { trigger: 'once', slot: null, requireTradingDay: false });
    expect(llm.calls[0]?.body.tools).toHaveLength(FIXTURE_TOOLS.length + 1);
    expect(llm.calls[0]?.body.messages[0].content).not.toContain('schedule_run');
    expect(lastToolResults(llm.calls[1]!)[0]).toMatchObject({ is_error: true, content: expect.stringContaining('UNKNOWN_TOOL') });
  });
});

describe('isolation', () => {
  it('each bot has its own notes, spend and run logs', async () => {
    const claude = await claudeFake(() => claudeReply([toolUse('1', 'save_notes', { text: 'claude notes' })]), () => claudeReply([text('ok')]));
    const gpt = await openaiFake(() => openaiReply([fnCall('1', 'save_notes', { text: 'gpt notes' })]), () => openaiReply([message('ok')]));
    const config = testConfig({ dataDir, anthropicUrl: claude.url, openaiUrl: gpt.url });
    const deps = testDeps(config, fakeMcp({}).connect);
    await Promise.all([
      runBot(bot(config, 'claude'), deps, { trigger: 'once', slot: null, requireTradingDay: false }),
      runBot(bot(config, 'chatgpt'), deps, { trigger: 'once', slot: null, requireTradingDay: false }),
    ]);
    expect(readFileSync(join(dataDir, 'claude', 'notes.md'), 'utf8')).toBe('claude notes');
    expect(readFileSync(join(dataDir, 'chatgpt', 'notes.md'), 'utf8')).toBe('gpt notes');
    expect(runLogs(dataDir, 'claude')).toHaveLength(1);
    expect(runLogs(dataDir, 'chatgpt')).toHaveLength(1);
    expect(existsSync(join(dataDir, 'claude', 'spend.json'))).toBe(true);
    expect(existsSync(join(dataDir, 'chatgpt', 'spend.json'))).toBe(true);
  });
});

describe('one run per bot at a time', () => {
  const opts = { trigger: 'schedule', slot: '09:45', requireTradingDay: true } as const;
  const once = { trigger: 'once', slot: null, requireTradingDay: false } as const;

  it('a second run of the same bot while one is in progress is skipped, so the daily cap and the spend hold', async () => {
    // $0.42 per response under a $1.00 cap: two overlapping runs used to both pass the check and lose a spend
    const usage = { input_tokens: 100_000, output_tokens: 1000 };
    const llm = await claudeFake(() => claudeReply([text('first')], usage), () => claudeReply([text('third')], usage));
    const config = testConfig({ dataDir, anthropicUrl: llm.url, extra: { AGENT_DAILY_USD_CAP: '1.00' } });
    const deps = testDeps(config, fakeMcp({}).connect);
    const [a, b] = await Promise.all([runBot(bot(config, 'claude'), deps, opts), runBot(bot(config, 'claude'), deps, once)]);
    expect(a.status).toBe('completed');
    expect(b.status).toBe('skipped_busy');
    expect(b.message).toContain('another run of claude is in progress');
    expect(llm.calls).toHaveLength(1);
    expect(readSpend(join(dataDir, 'claude'), '2026-10-12')).toMatchObject({ pico: 420_000_000_000n, runs: 1 });
    expect(existsSync(join(dataDir, 'claude', 'run.lock'))).toBe(false);

    // released: the next run goes ahead and adds to the day's total
    const c = await runBot(bot(config, 'claude'), deps, once);
    expect(c.status).toBe('completed');
    expect(readSpend(join(dataDir, 'claude'), '2026-10-12')).toMatchObject({ pico: 840_000_000_000n, runs: 2 });
  });

  it('respects a live lock and takes over a stale one', async () => {
    const llm = await claudeFake(() => claudeReply([text('ok')]));
    const config = testConfig({ dataDir, anthropicUrl: llm.url });
    const deps = testDeps(config, fakeMcp({}).connect);
    const lockPath = join(dataDir, 'claude', 'run.lock');
    mkdirSync(join(dataDir, 'claude'), { recursive: true });
    // this process holds it (e.g. the scheduled service, seen from a `docker exec … --once`)
    writeFileSync(lockPath, JSON.stringify({ pid: process.pid, host: hostname(), startedAt: '2026-10-12T13:44:00Z' }));
    expect((await runBot(bot(config, 'claude'), deps, once)).status).toBe('skipped_busy');
    expect(llm.calls).toHaveLength(0);
    // older than the time cap plus a minute: a run that never finished
    writeFileSync(lockPath, JSON.stringify({ pid: process.pid, host: hostname(), startedAt: '2026-10-12T13:30:00Z' }));
    expect((await runBot(bot(config, 'claude'), deps, once)).status).toBe('completed');
    expect(existsSync(lockPath)).toBe(false);
  });

  it('addSpend adds to what another writer recorded instead of overwriting it', () => {
    const dir = join(dataDir, 'claude');
    addSpend(dir, '2026-10-12', 300_000_000_000n, 1, new Date('2026-10-12T13:45:00Z'));
    addSpend(dir, '2026-10-12', 300_000_000_000n, 1, new Date('2026-10-12T13:46:00Z'));
    expect(readSpend(dir, '2026-10-12')).toMatchObject({ pico: 600_000_000_000n, runs: 2 });
  });
});
