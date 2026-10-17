import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { bookRun } from '../src/requested.js';
import { runSchedule } from '../src/scheduler.js';
import { anthropicServer, claudeReply, fnCall, message, openaiReply, openaiServer, type ScriptedLlmServer, text } from './fakes/llm.js';
import { CLOSED_CLOCK, fakeMcp, WEEKEND_CRYPTO_CLOCK } from './fakes/mcp.js';
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

/** A clock `msBefore` ms before `iso`, running at wall-clock speed. */
function clockBefore(iso: string, msBefore: number): { now: () => Date } {
  const offset = new Date(iso).getTime() - msBefore - Date.now();
  return { now: () => new Date(Date.now() + offset) };
}

async function until(deps: { sink: { text: string } }, needle: string, tries = 100): Promise<void> {
  for (let i = 0; i < tries && !deps.sink.text.includes(needle); i++) await new Promise((r) => setTimeout(r, 50));
}

describe('scheduler', () => {
  it('waits for the next ET slot, starts both bots in the same minute (concurrently), and stops on shutdown', async () => {
    const { claude, gpt } = await fakes(1000);
    const config = testConfig({ dataDir, anthropicUrl: claude.url, openaiUrl: gpt.url });
    const shutdown = new AbortController();
    // 150 ms before the 2026-10-12 09:30 ET slot
    const deps = testDeps(config, fakeMcp({}).connect, {
      clock: clockBefore('2026-10-12T13:30:00Z', 150),
      shutdown: shutdown.signal,
    });
    const done = runSchedule(config.bots, deps);
    await until(deps, 'run bot=chatgpt');
    await until(deps, 'run bot=claude');
    shutdown.abort();
    await done;
    expect(deps.sink.text).toContain('[claude] next run: 2026-10-12 09:30 ET (2026-10-12T13:30:00.000Z)');
    expect(deps.sink.text).toContain('[chatgpt] next run: 2026-10-12 09:30 ET (2026-10-12T13:30:00.000Z)');
    expect(deps.sink.text).toMatch(/run bot=claude .*trigger=schedule@09:30 status=completed/);
    expect(deps.sink.text).toMatch(/run bot=chatgpt .*trigger=schedule@09:30 status=completed/);
    expect(deps.sink.text).toContain('[claude] next run: 2026-10-12 12:45 ET');
    expect(claude.calls).toHaveLength(1);
    // Each fake answers after 1 s: run one after the other, the second bot's first request would come >= 1 s later.
    const first = [claude.calls[0]?.receivedAt ?? 0, gpt.calls[0]?.receivedAt ?? 0];
    expect(Math.abs((first[0] ?? 0) - (first[1] ?? 0))).toBeLessThan(800);
  });

  it('runs a weekend slot while the clock says crypto trades, and skips it otherwise', async () => {
    const claude = await anthropicServer([() => claudeReply([text('ok')])]).start();
    servers.push(claude);
    const config = testConfig({ dataDir, anthropicUrl: claude.url });
    // 150 ms before the Saturday 2026-10-17 09:30 ET slot
    for (const [clock, status] of [
      [WEEKEND_CRYPTO_CLOCK, 'completed'],
      [CLOSED_CLOCK, 'skipped_non_trading_day'],
    ] as const) {
      const shutdown = new AbortController();
      const deps = testDeps(config, fakeMcp({}, clock).connect, {
        clock: clockBefore('2026-10-17T13:30:00Z', 150),
        shutdown: shutdown.signal,
      });
      const done = runSchedule(config.bots, deps);
      await until(deps, 'run bot=claude');
      shutdown.abort();
      await done;
      expect(deps.sink.text).toContain('[claude] next run: 2026-10-17 09:30 ET');
      expect(deps.sink.text).toMatch(new RegExp(`run bot=claude .*trigger=schedule@09:30 status=${status}`));
    }
    expect(claude.calls).toHaveLength(1);
  });

  it('skips a slot it reaches more than 10 minutes late', async () => {
    const config = testConfig({ dataDir });
    // 09:29:59 ET when both bots' loops plan their first slot; when that 1 s wait ends, 09:45 (the host slept)
    const start = Date.now();
    const clock = {
      now: () => new Date(Date.now() - start < 300 ? '2026-10-12T13:29:59Z' : '2026-10-12T13:45:00Z'),
    };
    const shutdown = new AbortController();
    const mcp = fakeMcp({});
    const deps = testDeps(config, mcp.connect, { clock, shutdown: shutdown.signal });
    const done = runSchedule(config.bots, deps);
    await until(deps, '[chatgpt] skipping', 50);
    shutdown.abort();
    await done;
    expect(deps.sink.text).toContain('[claude] skipping the 2026-10-12 09:30 ET run: 15 min late');
    expect(deps.sink.text).toContain('[chatgpt] skipping the 2026-10-12 09:30 ET run: 15 min late');
    expect(mcp.connects).toHaveLength(0);
  });

  it('runs an extra run the bot booked at its time, with its reason, for that bot only', async () => {
    const claude = await anthropicServer([() => claudeReply([text('ok')])]).start();
    servers.push(claude);
    const config = testConfig({ dataDir, anthropicUrl: claude.url });
    const booked = bookRun(
      join(dataDir, 'claude'),
      { at_et: '14:15', reason: 'NVDA reports at 14:00; re-check the position' },
      { now: new Date('2026-10-12T17:00:00Z'), schedule: config.schedule, holidays: new Map(), perDay: 4 },
    );
    if (!booked.ok) throw new Error(booked.message);
    const shutdown = new AbortController();
    const deps = testDeps(config, fakeMcp({}).connect, {
      clock: clockBefore('2026-10-12T18:15:00Z', 150),
      shutdown: shutdown.signal,
    });
    const done = runSchedule(config.bots, deps);
    await until(deps, 'run bot=claude');
    shutdown.abort();
    await done;
    expect(deps.sink.text).toContain(`[claude] next run: 2026-10-12 14:15 ET (2026-10-12T18:15:00.000Z), extra run ${booked.run.id}`);
    expect(deps.sink.text).toContain('[chatgpt] next run: 2026-10-12 15:50 ET');
    expect(deps.sink.text).toMatch(/run bot=claude .*trigger=requested@14:15 status=completed/);
    expect(deps.sink.text).not.toContain('run bot=chatgpt');
    const opening = claude.calls[0]?.body.messages[0].content as string;
    expect(opening).toContain('Run: extra run you booked for 14:15 ET, 2026-10-12 14:15 ET.');
    expect(opening).toContain(`You booked this run (${booked.run.id}, at 2026-10-12 14:15 ET) for: "NVDA reports at 14:00; re-check the position"`);
    expect(opening).toContain('Your pending extra runs: none.');
    const file = JSON.parse(readFileSync(join(dataDir, 'claude', 'requested-runs.json'), 'utf8'));
    expect(file).toMatchObject({ pending: [], done: [{ id: booked.run.id, outcome: 'ran' }] });
    expect(deps.reports[0]?.body).toMatchObject({ trigger: 'SCHEDULE', slot: '14:15' });
  });

  it('drops a booked run it reaches more than 10 minutes late', async () => {
    const config = testConfig({ dataDir });
    const booked = bookRun(
      join(dataDir, 'claude'),
      { at_et: '14:15', reason: 'check' },
      { now: new Date('2026-10-12T17:00:00Z'), schedule: config.schedule, holidays: new Map(), perDay: 4 },
    );
    if (!booked.ok) throw new Error(booked.message);
    const shutdown = new AbortController();
    const mcp = fakeMcp({});
    const deps = testDeps(config, mcp.connect, { clock: clockBefore('2026-10-12T18:30:00Z', 0), shutdown: shutdown.signal });
    const done = runSchedule(config.bots, deps);
    await until(deps, 'skipping the extra run', 50);
    shutdown.abort();
    await done;
    expect(deps.sink.text).toContain(`[claude] skipping the extra run ${booked.run.id} booked for 2026-10-12 14:15 ET: 15 min late`);
    expect(mcp.connects).toHaveLength(0);
  });
});
