// The runner's files under AGENT_DATA_DIR (volume agent-data, host /DATA/AppData/stocks/agent-data):
//   <bot>/notes.md       the bot's memory, at most 8 KB, injected into every run, replaced by save_notes
//   <bot>/spend.json     today's (ET) LLM spend, for the daily dollar cap
//   <bot>/runs/<ts>.jsonl one run log per run
//   <bot>/run.lock       present while a run of the bot is in progress (one run per bot at a time, across processes)

import { randomUUID } from 'node:crypto';
import { constants, accessSync, mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'node:fs';
import { hostname } from 'node:os';
import { join } from 'node:path';
import { NOTES_MAX_BYTES } from './config.js';
import { formatUsd } from './money.js';

export function botDir(dataDir: string, bot: string): string {
  return join(dataDir, bot);
}

export function ensureDir(dir: string): void {
  mkdirSync(dir, { recursive: true });
}

/** Throws a readable error when the data directory cannot be written (wrong owner of the bind mount). */
export function checkWritable(dataDir: string): void {
  ensureDir(dataDir);
  accessSync(dataDir, constants.W_OK);
  const probe = join(dataDir, `.write-test-${process.pid}`);
  writeFileSync(probe, 'ok');
  unlinkSync(probe);
}

/** Write through a temporary file and rename, so a crash never leaves half a file. */
export function writeAtomic(path: string, content: string): void {
  // pid alone is not unique: two containers (`docker compose run`) both run node as PID 1
  const tmp = `${path}.tmp-${process.pid}-${randomUUID()}`;
  writeFileSync(tmp, content, { encoding: 'utf8', mode: 0o640 });
  renameSync(tmp, path);
}

function readOrNull(path: string): string | null {
  try {
    return readFileSync(path, 'utf8');
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code === 'ENOENT') return null;
    throw e;
  }
}

// ------------------------------------------------------------------------------------------------ notes

export interface Notes {
  text: string;
  bytes: number;
  /** True when a hand-edited file was larger than the limit and only its first 8 KB are shown. */
  clipped: boolean;
}

export function readNotes(dir: string): Notes {
  const raw = readOrNull(join(dir, 'notes.md')) ?? '';
  const buf = Buffer.from(raw, 'utf8');
  if (buf.length <= NOTES_MAX_BYTES) return { text: raw, bytes: buf.length, clipped: false };
  // Cut at the limit, then drop a trailing partial UTF-8 sequence (the decoder turns it into U+FFFD).
  const text = buf.subarray(0, NOTES_MAX_BYTES).toString('utf8').replace(/�+$/, '');
  return { text, bytes: Buffer.byteLength(text, 'utf8'), clipped: true };
}

export type SaveNotesResult = { ok: true; bytes: number } | { ok: false; bytes: number; message: string };

export function saveNotes(dir: string, text: string): SaveNotesResult {
  const bytes = Buffer.byteLength(text, 'utf8');
  if (bytes > NOTES_MAX_BYTES) {
    return {
      ok: false,
      bytes,
      message: `The notes are ${bytes} bytes; the limit is ${NOTES_MAX_BYTES} bytes (UTF-8). Nothing was saved.`,
    };
  }
  ensureDir(dir);
  writeAtomic(join(dir, 'notes.md'), text);
  return { ok: true, bytes };
}

// ------------------------------------------------------------------------------------------------ daily spend

export interface Spend {
  dateEt: string;
  /** Pico-dollars spent on this ET date. */
  pico: bigint;
  runs: number;
}

interface SpendFile {
  dateEt: string;
  usd: string;
  pico: string;
  runs: number;
  updatedAt: string;
}

export function readSpend(dir: string, dateEt: string): Spend {
  const raw = readOrNull(join(dir, 'spend.json'));
  if (raw) {
    try {
      const f = JSON.parse(raw) as Partial<SpendFile>;
      if (f.dateEt === dateEt && typeof f.pico === 'string' && /^\d+$/.test(f.pico)) {
        return { dateEt, pico: BigInt(f.pico), runs: typeof f.runs === 'number' ? f.runs : 0 };
      }
    } catch {
      // A corrupt file counts as nothing spent yet today; it is rewritten on the next model response.
    }
  }
  return { dateEt, pico: 0n, runs: 0 };
}

/**
 * Adds this response's cost (and run count) to the file's current total for {@code dateEt} and returns the new total.
 * It re-reads the file instead of writing an in-memory total, so a concurrent writer's spend is never overwritten
 * (the run lock already keeps a bot's runs apart; this is the second line of defence).
 */
export function addSpend(dir: string, dateEt: string, pico: bigint, runs: number, now: Date): Spend {
  const current = readSpend(dir, dateEt);
  const next: Spend = { dateEt, pico: current.pico + pico, runs: current.runs + runs };
  writeSpend(dir, next, now);
  return next;
}

export function writeSpend(dir: string, spend: Spend, now: Date): void {
  ensureDir(dir);
  const f: SpendFile = {
    dateEt: spend.dateEt,
    usd: formatUsd(spend.pico, 6),
    pico: spend.pico.toString(),
    runs: spend.runs,
    updatedAt: now.toISOString(),
  };
  writeAtomic(join(dir, 'spend.json'), JSON.stringify(f, null, 2) + '\n');
}

// ------------------------------------------------------------------------------------------------ run lock

interface LockFile {
  pid: number;
  host: string;
  startedAt: string;
}

export type RunLockResult =
  | { acquired: true; release: () => void }
  | { acquired: false; holder: string };

function alive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return (e as NodeJS.ErrnoException).code === 'EPERM';
  }
}

/**
 * One run per bot at a time, across processes (`docker exec … --once` next to the scheduled service, or
 * `docker compose run`): `<bot>/run.lock` is created with O_EXCL (flag `wx`) and holds the pid, host and start time.
 * A lock older than {@code staleAfterMs}, or left by a dead process on this host, is stale and taken over.
 */
export function acquireRunLock(dir: string, now: Date, staleAfterMs: number): RunLockResult {
  ensureDir(dir);
  const path = join(dir, 'run.lock');
  const mine: LockFile = { pid: process.pid, host: hostname(), startedAt: now.toISOString() };
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      writeFileSync(path, JSON.stringify(mine) + '\n', { encoding: 'utf8', flag: 'wx', mode: 0o640 });
      let released = false;
      return {
        acquired: true,
        release: () => {
          if (released) return;
          released = true;
          try {
            unlinkSync(path);
          } catch {
            // already gone
          }
        },
      };
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code !== 'EEXIST') throw e;
    }
    let held: Partial<LockFile> = {};
    try {
      held = JSON.parse(readFileSync(path, 'utf8')) as Partial<LockFile>;
    } catch {
      // unreadable or half written: judged by its age below
    }
    const since = typeof held.startedAt === 'string' ? Date.parse(held.startedAt) : Number.NaN;
    const old = Number.isNaN(since) || now.getTime() - since > staleAfterMs;
    const dead =
      held.host === mine.host && typeof held.pid === 'number' && held.pid !== process.pid && !alive(held.pid);
    if (attempt === 0 && (old || dead)) {
      try {
        unlinkSync(path);
      } catch {
        // another process took it over first: the retry decides
      }
      continue;
    }
    return {
      acquired: false,
      holder: `pid ${held.pid ?? '?'} on ${held.host ?? '?'} since ${held.startedAt ?? '?'}`,
    };
  }
  return { acquired: false, holder: 'unknown' };
}
