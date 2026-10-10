// The runner's files under AGENT_DATA_DIR (volume agent-data, host /DATA/AppData/stocks/agent-data):
//   <bot>/notes.md       the bot's memory, at most 8 KB, injected into every run, replaced by save_notes
//   <bot>/spend.json     today's (ET) LLM spend, for the daily dollar cap
//   <bot>/runs/<ts>.jsonl one run log per run

import { constants, accessSync, mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'node:fs';
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
  const tmp = `${path}.tmp-${process.pid}`;
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
