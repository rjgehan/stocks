import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { runSchedule, runSlot } from '../src/scheduler.js';
import { nextSlot } from '../src/time.js';
import { anthropicServer, claudeReply, fnCall, message, openaiReply, openaiServer, type ScriptedLlmServer, text } from './fakes/llm.js';
import { fakeMcp } from './fakes/mcp.js';
import { removeDir, tempDir, testConfig, testDeps } from './helpers.js';

let dataDir: string;
let servers: ScriptedLlmServer[];

beforeEach(() => {
  dataDir = tempDir();
  servers = [];
});
afterEach(async () => {
  await Promise.all(servers.map((s) => s.close()));
  removeDir(dataDir);
});

async function fakes(delayMs: number): Promise<{ claude: ScriptedLlmServer; gpt: ScriptedLlmServer }> {
  const claude = await anthropicServer([() => ({ ...claudeReply([text('ok')]), delayMs })]).start();
  const gpt = await openaiServer([
    () => ({ ...openaiReply([fnCall('c', 'get_account', {})]), delayMs }),
    () => openaiReply([message('ok')]),
  ]).start();
  servers.push(claude, gpt);
  return { claude, gpt };
}

describe('scheduler', () => {
  it('starts both bots in the same minute, concurrently', async () => {
    const { claude, gpt } = await fakes(1000);
    const config = testConfig({ dataDir, anthropicUrl: claude.url, openaiUrl: gpt.url });
    const deps = testDeps(config, fakeMcp({}).connect);
    const slot = nextSlot(new Date('2026-10-12T13:00:00Z'), config.schedule);
    const results = await runSlot(config.bots, deps, slot);
    expect(results.map((r) => [r.bot, r.status])).toEqual([
      ['claude', 'completed'],
      ['chatgpt', 'completed'],
    ]);
    // Each fake answers after 1 s: run one after the other, the second bot's first request would come >= 1 s later.
    const first = [claude.calls[0]?.receivedAt ?? 0, gpt.calls[0]?.receivedAt ?? 0];
    expect(Math.abs((first[0] ?? 0) - (first[1] ?? 0))).toBeLessThan(800);
    expect(deps.sink.text).toContain('trigger=schedule@09:45');
  });

  it('waits for the next ET slot, runs it, and stops on shutdown', async () => {
    const { claude, gpt } = await fakes(0);
    const config = testConfig({ dataDir, anthropicUrl: claude.url, openaiUrl: gpt.url });
    // A clock 150 ms before the 2026-10-12 09:45 ET slot, running at wall-clock speed.
    const slotAt = new Date('2026-10-12T13:45:00Z').getTime();
    const offset = slotAt - 150 - Date.now();
    const clock = { now: () => new Date(Date.now() + offset) };
    const shutdown = new AbortController();
    const deps = testDeps(config, fakeMcp({}).connect, { clock, shutdown: shutdown.signal });
    const done = runSchedule(config.bots, deps);
    for (let i = 0; i < 100 && !deps.sink.text.includes('run bot=chatgpt'); i++) {
      await new Promise((r) => setTimeout(r, 50));
    }
    shutdown.abort();
    await done;
    expect(deps.sink.text).toContain('next runs: 2026-10-12 09:45 ET (2026-10-12T13:45:00.000Z) for claude, chatgpt');
    expect(deps.sink.text).toMatch(/run bot=claude .*trigger=schedule@09:45 status=completed/);
    expect(deps.sink.text).toMatch(/run bot=chatgpt .*trigger=schedule@09:45 status=completed/);
    expect(deps.sink.text).toContain('next runs: 2026-10-12 12:30 ET');
    expect(claude.calls).toHaveLength(1);
  });

  it('skips a slot it reaches more than 10 minutes late', async () => {
    const config = testConfig({ dataDir });
    const clockTimes = [new Date('2026-10-12T13:44:59Z'), new Date('2026-10-12T14:00:00Z')];
    let i = 0;
    const clock = { now: () => clockTimes[Math.min(i++, clockTimes.length - 1)] as Date };
    const shutdown = new AbortController();
    const mcp = fakeMcp({});
    const deps = testDeps(config, mcp.connect, { clock, shutdown: shutdown.signal });
    const done = runSchedule(config.bots, deps);
    for (let n = 0; n < 50 && !deps.sink.text.includes('skipping'); n++) await new Promise((r) => setTimeout(r, 20));
    shutdown.abort();
    await done;
    expect(deps.sink.text).toContain('skipping the 2026-10-12 09:45 ET runs: 15 min late');
    expect(mcp.connects).toHaveLength(0);
  });
});
