// End to end against the real stocks backend: the jar in profile `dev` (H2 in a scratch directory, SIM market data,
// demo seed with the claude and chatgpt bot keys) and its real MCP server. Only the LLM APIs are fakes, scripted to
// call get_market_clock, get_quotes, place_order (with a rationale) and save_notes; the order is then read back
// over REST with the bot's key.
//
//   cd backend && ./mvnw -B -q package -DskipTests           # builds backend/target/stocks.jar
//   cd tools/agent-runner && npm run test:e2e                 # starts it on 127.0.0.1:18230 (management 18231)
//
// Environment: STOCKS_JAR (default ../../backend/target/stocks.jar), STOCKS_E2E_PORT (18230) and
// STOCKS_E2E_MANAGEMENT_PORT (port + 1). To reuse a backend that is already running in profile dev, set
// STOCKS_E2E_URL (e.g. http://127.0.0.1:18230) and STOCKS_E2E_KEYS_FILE (its .data/dev-keys.txt).

import { type ChildProcess, spawn } from 'node:child_process';
import { closeSync, existsSync, mkdtempSync, openSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { loadConfig } from '../../src/config.js';
import { Logger } from '../../src/log.js';
import { adapterFor } from '../../src/main.js';
import { connectMcp } from '../../src/mcp.js';
import { runBot, type RunnerDeps } from '../../src/runner.js';
import { systemClock } from '../../src/time.js';
import {
  anthropicServer,
  claudeReply,
  fnCall,
  message,
  openaiReply,
  openaiServer,
  type RecordedRequest,
  type ScriptedLlmServer,
  text,
  toolUse,
} from '../fakes/llm.js';
import { MemorySink, readRunLog } from '../helpers.js';

const REPO = fileURLToPath(new URL('../../../../', import.meta.url));
const JAR = process.env.STOCKS_JAR ?? join(REPO, 'backend', 'target', 'stocks.jar');
const PORT = Number(process.env.STOCKS_E2E_PORT ?? '18230');
const MANAGEMENT_PORT = Number(process.env.STOCKS_E2E_MANAGEMENT_PORT ?? String(PORT + 1));
const REUSE_URL = process.env.STOCKS_E2E_URL;

let baseUrl = REUSE_URL ?? `http://127.0.0.1:${PORT}`;
let workDir: string | null = null;
let backend: ChildProcess | null = null;
let backendLog = '';
let keys: { claude: string; chatgpt: string };
let dataDir: string;
const servers: ScriptedLlmServer[] = [];

async function waitReady(url: string, timeoutMs: number): Promise<void> {
  const until = Date.now() + timeoutMs;
  let last = '';
  while (Date.now() < until) {
    if (backend && backend.exitCode !== null) {
      throw new Error(`the backend exited (${backend.exitCode}); log: ${backendLog}\n${tail(backendLog)}`);
    }
    try {
      const res = await fetch(url);
      if (res.ok) return;
      last = `HTTP ${res.status}`;
    } catch (e) {
      last = (e as Error).message;
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error(`the backend was not ready after ${timeoutMs} ms (${last}); log: ${backendLog}\n${tail(backendLog)}`);
}

function tail(path: string): string {
  try {
    return readFileSync(path, 'utf8').split('\n').slice(-60).join('\n');
  } catch {
    return '';
  }
}

function readKeys(file: string): { claude: string; chatgpt: string } {
  const txt = readFileSync(file, 'utf8');
  const claude = /^claude \(AI\) API key: (\S+)$/m.exec(txt)?.[1];
  const chatgpt = /^chatgpt \(AI\) API key: (\S+)$/m.exec(txt)?.[1];
  if (!claude || !chatgpt) throw new Error(`no bot keys in ${file} (the dev seed writes them on a fresh database)`);
  return { claude, chatgpt };
}

beforeAll(async () => {
  dataDir = mkdtempSync(join(tmpdir(), 'agent-runner-e2e-data-'));
  if (REUSE_URL) {
    const file = process.env.STOCKS_E2E_KEYS_FILE;
    if (!file) throw new Error('STOCKS_E2E_URL needs STOCKS_E2E_KEYS_FILE');
    keys = readKeys(file);
    return;
  }
  if (!existsSync(JAR)) {
    throw new Error(`${JAR} is missing: build it with "cd backend && ./mvnw -B -q package -DskipTests"`);
  }
  workDir = mkdtempSync(join(tmpdir(), 'agent-runner-e2e-backend-'));
  backendLog = join(workDir, 'backend.log');
  const fd = openSync(backendLog, 'a');
  backend = spawn(
    'java',
    [
      '-jar',
      JAR,
      '--spring.profiles.active=dev',
      `--server.port=${PORT}`,
      `--management.server.port=${MANAGEMENT_PORT}`,
      '--server.address=127.0.0.1',
    ],
    { cwd: workDir, stdio: ['ignore', fd, fd], env: { ...process.env, MARKETDATA_PROFILE: 'simulated' } },
  );
  closeSync(fd);
  baseUrl = `http://127.0.0.1:${PORT}`;
  await waitReady(`http://127.0.0.1:${MANAGEMENT_PORT}/actuator/health/readiness`, 180_000);
  keys = readKeys(join(workDir, '.data', 'dev-keys.txt'));
});

afterAll(async () => {
  await Promise.all(servers.map((s) => s.close()));
  if (backend && backend.exitCode === null) {
    const exited = new Promise<void>((resolve) => backend?.once('exit', () => resolve()));
    backend.kill('SIGTERM');
    await Promise.race([exited, new Promise((r) => setTimeout(r, 20_000))]);
    if (backend.exitCode === null) backend.kill('SIGKILL');
  }
  if (workDir) rmSync(workDir, { recursive: true, force: true });
  rmSync(dataDir, { recursive: true, force: true });
});

/** The text of the tool result for `toolId` in the newest message of an Anthropic request. */
function claudeResult(req: RecordedRequest, toolId: string): string {
  const msgs = req.body.messages as { content: unknown }[];
  const last = msgs[msgs.length - 1]?.content as { type: string; tool_use_id: string; content: string }[];
  const r = last.find((b) => b.type === 'tool_result' && b.tool_use_id === toolId);
  if (!r) throw new Error(`no tool_result ${toolId}`);
  return r.content;
}

function openaiResult(req: RecordedRequest, callId: string): string {
  const items = req.body.input as { type: string; call_id: string; output: string }[];
  const r = items.find((i) => i.type === 'function_call_output' && i.call_id === callId);
  if (!r) throw new Error(`no function_call_output ${callId}`);
  return r.output;
}

/** A buy limit 3 % under the last price, on the cent, with string arithmetic (prices are decimal strings). */
function limitFrom(quotesJson: string): string {
  const q = (JSON.parse(quotesJson) as { quotes: { last: string | null; bid: string | null }[] }).quotes[0];
  const px = q?.last ?? q?.bid;
  if (!px) throw new Error(`no price in ${quotesJson}`);
  const [whole = '0', frac = ''] = px.split('.');
  const cents = BigInt(whole) * 100n + BigInt((frac + '00').slice(0, 2));
  const limit = (cents * 97n) / 100n;
  return `${limit / 100n}.${String(limit % 100n).padStart(2, '0')}`;
}

function deps(env: Record<string, string>, sink: MemorySink): RunnerDeps {
  const config = loadConfig({
    STOCKS_MCP_URL: `${baseUrl}/mcp`,
    AGENT_DATA_DIR: dataDir,
    ANTHROPIC_API_KEY: 'sk-ant-e2e-fake-key-not-real-000000',
    OPENAI_API_KEY: 'sk-e2e-fake-key-not-real-0000000000',
    STOCKS_CLAUDE_KEY: keys.claude,
    STOCKS_CHATGPT_KEY: keys.chatgpt,
    CLAUDE_MODEL: 'claude-opus-5-5',
    OPENAI_MODEL: 'gpt-5.5',
    ...env,
  });
  return {
    config,
    logger: new Logger(config.secrets, sink),
    clock: systemClock,
    connectMcp,
    adapterFor,
    version: '1.0.0-e2e',
    shutdown: new AbortController().signal,
  };
}

interface RestOrder {
  orderId: string;
  clientOrderId: string;
  symbol: string;
  side: string;
  type: string;
  qty: string;
  limitPrice: string;
  channel: string;
  rationale: string | null;
  status: string;
}

/**
 * Cancels the order the scripted run placed, so a reused backend (STOCKS_E2E_URL) does not keep its buying power
 * held: a second run against the same database would otherwise fail with INSUFFICIENT_BUYING_POWER.
 */
async function cancelOrder(key: string, orderId: string): Promise<void> {
  const res = await fetch(`${baseUrl}/api/v1/orders/${encodeURIComponent(orderId)}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${key}` },
  });
  expect([200, 202, 204]).toContain(res.status);
}

async function orderByClientId(key: string, clientOrderId: string): Promise<RestOrder> {
  const res = await fetch(`${baseUrl}/api/v1/orders/by-client-id/${encodeURIComponent(clientOrderId)}`, {
    headers: { Authorization: `Bearer ${key}` },
  });
  expect(res.status).toBe(200);
  return (await res.json()) as RestOrder;
}

describe('agent runner against the real stocks MCP server', () => {
  it('runs a scripted Claude session that places an order with a rationale and saves notes', async () => {
    const clientOrderId = `claude-e2e-${Date.now()}`;
    let limit = '';
    let placed = '';
    const llm = await anthropicServer([
      () =>
        claudeReply([
          text('Checking the clock and AAPL.'),
          toolUse('t1', 'get_market_clock', {}),
          toolUse('t2', 'get_quotes', { symbols: ['AAPL'] }),
        ]),
      (req) => {
        limit = limitFrom(claudeResult(req, 't2'));
        return claudeReply([
          toolUse('t3', 'place_order', {
            client_order_id: clientOrderId,
            symbol: 'AAPL',
            side: 'BUY',
            type: 'LIMIT',
            qty: '1',
            limit_price: limit,
            time_in_force: 'DAY',
            rationale: 'E2E: a starter AAPL position, 3% under the last price.',
          }),
        ]);
      },
      (req) => {
        placed = claudeResult(req, 't3');
        return claudeReply([toolUse('t4', 'save_notes', { text: `Bought 1 AAPL limit ${limit} (${clientOrderId}).` })]);
      },
      () => claudeReply([text('Placed one AAPL limit order and saved my notes.')]),
    ]).start();
    servers.push(llm);
    const sink = new MemorySink();
    const d = deps({ ANTHROPIC_BASE_URL: llm.url }, sink);
    const bot = d.config.bots.find((b) => b.id === 'claude')!;

    const s = await runBot(bot, d, { trigger: 'once', slot: null, requireTradingDay: false });

    expect(`${s.status}\n${sink.text}`).toMatch(/^completed\n/);
    expect(s).toMatchObject({ toolCalls: 4, toolErrors: 0, orders: 1, turns: 4 });
    expect(JSON.parse(placed)).toMatchObject({ order: { clientOrderId }, replayed: false });
    // The model saw every server tool (38 in Phase 1, more as Phase 2 adds them) plus save_notes, converted to
    // Messages API custom tools.
    const tools = llm.calls[0]?.body.tools as { name: string; input_schema: { type: string } }[];
    expect(tools.length).toBeGreaterThanOrEqual(41);
    expect(tools.at(-1)?.name).toBe('save_notes');
    expect(tools.map((t) => t.name)).toEqual(
      expect.arrayContaining(['whoami', 'place_order', 'get_league_feed', 'screen_stocks', 'get_recommendations', 'save_notes']),
    );
    expect(llm.calls[0]?.body.messages[0].content).toContain('"feed":{"profile":"simulated"');

    const order = await orderByClientId(keys.claude, clientOrderId);
    expect(order).toMatchObject({
      clientOrderId,
      symbol: 'AAPL',
      side: 'BUY',
      type: 'LIMIT',
      channel: 'MCP_KEY',
      rationale: 'E2E: a starter AAPL position, 3% under the last price.',
    });
    expect(Number(order.qty)).toBe(1);
    expect(order.limitPrice.startsWith(limit)).toBe(true);
    expect(readFileSync(join(dataDir, 'claude', 'notes.md'), 'utf8')).toContain(clientOrderId);
    const log = readRunLog(s.logPath);
    expect(log.filter((l) => l.type === 'tool_call').map((l) => [l.name, l.isError])).toEqual([
      ['get_market_clock', false],
      ['get_quotes', false],
      ['place_order', false],
      ['save_notes', false],
    ]);
    expect(readFileSync(s.logPath!, 'utf8')).not.toContain(keys.claude);
    expect(sink.text).not.toContain(keys.claude);
    await cancelOrder(keys.claude, order.orderId);
    process.stdout.write(`${sink.text}`);
  });

  it('runs a scripted ChatGPT session through the Responses API the same way', async () => {
    // NVDA, not MSFT: one MSFT share (about $527 in the SIM) can exceed the bot's $500 of buying power
    const clientOrderId = `chatgpt-e2e-${Date.now()}`;
    let limit = '';
    let placed = '';
    const llm = await openaiServer([
      () => openaiReply([fnCall('c1', 'get_market_clock', {}), fnCall('c2', 'get_quotes', { symbols: ['NVDA'] })]),
      (req) => {
        limit = limitFrom(openaiResult(req, 'c2'));
        return openaiReply([
          fnCall('c3', 'place_order', {
            client_order_id: clientOrderId,
            symbol: 'NVDA',
            side: 'BUY',
            type: 'LIMIT',
            qty: '1',
            limit_price: limit,
            rationale: 'E2E: first NVDA lot below the market.',
          }),
        ]);
      },
      (req) => {
        placed = openaiResult(req, 'c3');
        return openaiReply([fnCall('c4', 'save_notes', { text: `NVDA limit ${limit} working (${clientOrderId}).` })]);
      },
      () => openaiReply([message('One NVDA limit order placed; notes saved.')]),
    ]).start();
    servers.push(llm);
    const sink = new MemorySink();
    const d = deps({ OPENAI_BASE_URL: `${llm.url}/v1` }, sink);
    const bot = d.config.bots.find((b) => b.id === 'chatgpt')!;

    const s = await runBot(bot, d, { trigger: 'once', slot: null, requireTradingDay: false });

    expect(`${s.status}\n${sink.text}`).toMatch(/^completed\n/);
    expect(s).toMatchObject({ toolCalls: 4, toolErrors: 0, orders: 1 });
    expect(llm.calls[0]?.body.tools).toHaveLength(39);
    expect(JSON.parse(placed)).toMatchObject({ order: { clientOrderId }, replayed: false });
    expect(llm.calls[1]?.body.previous_response_id).toMatch(/^resp_fake_/);
    const order = await orderByClientId(keys.chatgpt, clientOrderId);
    expect(order).toMatchObject({ clientOrderId, symbol: 'NVDA', side: 'BUY', type: 'LIMIT', channel: 'MCP_KEY' });
    expect(order.rationale).toBe('E2E: first NVDA lot below the market.');
    expect(readFileSync(join(dataDir, 'chatgpt', 'notes.md'), 'utf8')).toContain(clientOrderId);
    // A bot key only sees its own orders.
    const other = await fetch(`${baseUrl}/api/v1/orders/by-client-id/${clientOrderId}`, {
      headers: { Authorization: `Bearer ${keys.claude}` },
    });
    expect(other.status).toBe(404);
    expect(sink.text).not.toContain(keys.chatgpt);
    await cancelOrder(keys.chatgpt, order.orderId);
    process.stdout.write(`${sink.text}`);
  });
});
