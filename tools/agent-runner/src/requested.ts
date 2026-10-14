// Extra runs a bot books for itself (docs/agents.md §2.5): the runner-local tools schedule_run and
// cancel_scheduled_run keep <bot>/requested-runs.json, and the scheduler runs each booked run at its time, on top of
// AGENT_SCHEDULE_ET. Both bots get the same rules (fairness, §20.5), and the daily dollar cap covers these runs too.

import { randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ensureDir, writeAtomic } from './store.js';
import { etDate, etParts, etTime, etToInstant, type SlotTime } from './time.js';

export const REQUESTED_FILE = 'requested-runs.json';
/** A run is booked at least this far ahead. */
export const MIN_LEAD_MS = 2 * 60_000;
/** And at most this far ahead. */
export const MAX_AHEAD_MS = 7 * 86_400_000;
/** Two runs of a bot (scheduled or booked) are at least this far apart. */
export const MIN_GAP_MS = 10 * 60_000;
/** Pending booked runs per bot, all days together. */
export const MAX_PENDING = 10;
export const REASON_MAX = 300;
/** The ET wall-clock window a run can be booked in: the pre-market open to the post-market close. */
export const WINDOW_ET = { from: 4 * 60, to: 20 * 60, text: '04:00-20:00' };
/** How long the outcome of a past booked run is kept (the per-day limit only looks at one date). */
const DONE_KEEP_DAYS = 8;

export interface RequestedRun {
  id: string;
  /** ISO instant. */
  at: string;
  /** "YYYY-MM-DD HH:MM", America/New_York. */
  atEt: string;
  reason: string;
  bookedAt: string;
}

interface DoneRun {
  id: string;
  atEt: string;
  outcome: 'ran' | 'missed';
}

interface RequestedFile {
  pending: RequestedRun[];
  done: DoneRun[];
}

export interface BookContext {
  now: Date;
  schedule: SlotTime[];
  /** Market holidays by ET date ("2026-11-26" → "Thanksgiving Day"), from the market clock. */
  holidays: Map<string, string>;
  perDay: number;
}

export type BookResult =
  | { ok: true; run: RequestedRun; pending: RequestedRun[] }
  | { ok: false; code: string; message: string; hint: string };

export type CancelResult =
  | { ok: true; cancelled: RequestedRun; pending: RequestedRun[] }
  | { ok: false; code: string; message: string; hint: string };

const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

function isRun(v: unknown): v is RequestedRun {
  const r = v as Partial<RequestedRun> | null;
  return (
    r !== null &&
    typeof r === 'object' &&
    typeof r.id === 'string' &&
    typeof r.at === 'string' &&
    !Number.isNaN(Date.parse(r.at)) &&
    typeof r.atEt === 'string' &&
    typeof r.reason === 'string'
  );
}

function isDone(v: unknown): v is DoneRun {
  const d = v as Partial<DoneRun> | null;
  return d !== null && typeof d === 'object' && typeof d.id === 'string' && typeof d.atEt === 'string';
}

function byTime(a: RequestedRun, b: RequestedRun): number {
  return Date.parse(a.at) - Date.parse(b.at);
}

function read(dir: string): RequestedFile {
  let raw: string;
  try {
    raw = readFileSync(join(dir, REQUESTED_FILE), 'utf8');
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code === 'ENOENT') return { pending: [], done: [] };
    throw e;
  }
  try {
    const f = JSON.parse(raw) as Partial<RequestedFile>;
    return {
      pending: (Array.isArray(f.pending) ? f.pending.filter(isRun) : []).sort(byTime),
      done: Array.isArray(f.done) ? f.done.filter(isDone) : [],
    };
  } catch {
    // A corrupt (hand-edited) file counts as no booked runs; the next booking rewrites it.
    return { pending: [], done: [] };
  }
}

function write(dir: string, f: RequestedFile, now: Date): void {
  const oldest = etDate(new Date(now.getTime() - DONE_KEEP_DAYS * 86_400_000));
  const done = f.done.filter((d) => d.atEt.slice(0, 10) >= oldest);
  ensureDir(dir);
  writeAtomic(join(dir, REQUESTED_FILE), JSON.stringify({ pending: f.pending, done }, null, 2) + '\n');
}

/** The bot's pending booked runs, earliest first. */
export function pendingRuns(dir: string): RequestedRun[] {
  return read(dir).pending;
}

/** The earliest pending booked run, or null. */
export function nextPendingRun(dir: string): RequestedRun | null {
  return read(dir).pending[0] ?? null;
}

/** What the model sees of a booked run. */
export function publicRun(r: RequestedRun): { id: string; at_et: string; reason: string } {
  return { id: r.id, at_et: r.atEt, reason: r.reason };
}

/** "HH:MM" (today, ET), "YYYY-MM-DD HH:MM" / "YYYY-MM-DDTHH:MM" (ET), or ISO-8601 with Z or an offset. */
export function parseRunTime(text: string, now: Date): Date | null {
  const t = text.trim();
  let m = /^(\d{1,2}):(\d{2})$/.exec(t);
  if (m) {
    const today = etParts(now);
    return wallClock(today.year, today.month, today.day, Number(m[1]), Number(m[2]));
  }
  m = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{1,2}):(\d{2})$/.exec(t);
  if (m) return wallClock(Number(m[1]), Number(m[2]), Number(m[3]), Number(m[4]), Number(m[5]));
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})$/.test(t)) {
    const ms = Date.parse(t);
    return Number.isNaN(ms) ? null : new Date(Math.floor(ms / 60_000) * 60_000);
  }
  return null;
}

/** The instant the ET wall clock reads y-m-d h:mi, or null for a date or time that does not exist. */
function wallClock(y: number, mo: number, d: number, h: number, mi: number): Date | null {
  if (mo < 1 || mo > 12 || d < 1 || d > 31 || h > 23 || mi > 59) return null;
  const at = etToInstant(y, mo, d, h, mi);
  const p = etParts(at);
  return p.year === y && p.month === mo && p.day === d && p.hour === h && p.minute === mi ? at : null;
}

const stamp = (d: Date): string => `${etDate(d)} ${etTime(d)}`;

function refuse(code: string, message: string, hint: string): BookResult & CancelResult {
  return { ok: false, code, message, hint };
}

/** schedule_run: validates the request against the rules above and stores it. */
export function bookRun(dir: string, args: { at_et?: unknown; reason?: unknown }, ctx: BookContext): BookResult {
  const { now } = ctx;
  const reason = typeof args.reason === 'string' ? args.reason.trim() : '';
  if (reason.length < 1 || reason.length > REASON_MAX) {
    return refuse(
      'VALIDATION_FAILED',
      `schedule_run needs a \`reason\` of 1-${REASON_MAX} characters.`,
      'Say what you want to check or do in that run; it is shown to you when the run starts.',
    );
  }
  const at = typeof args.at_et === 'string' ? parseRunTime(args.at_et, now) : null;
  if (!at) {
    return refuse(
      'VALIDATION_FAILED',
      `\`at_et\` must be an America/New_York time like "${etDate(now)} 14:15" or "14:15" (today).`,
      'Send the date and time on the ET wall clock, 24-hour format.',
    );
  }
  const atEt = stamp(at);
  const dateEt = atEt.slice(0, 10);
  const nowText = `it is now ${stamp(now)} ET`;
  if (at.getTime() < now.getTime() + MIN_LEAD_MS) {
    return refuse(
      'RUN_TIME_NOT_ALLOWED',
      `${atEt} ET is less than ${MIN_LEAD_MS / 60_000} minutes from now (${nowText}).`,
      'Pick a later time; for today, "HH:MM" is enough.',
    );
  }
  if (at.getTime() > now.getTime() + MAX_AHEAD_MS) {
    return refuse(
      'RUN_TIME_NOT_ALLOWED',
      `${atEt} ET is more than ${MAX_AHEAD_MS / 86_400_000} days ahead (${nowText}).`,
      'Book it closer to the date, from a later run, and keep the plan in your notes meanwhile.',
    );
  }
  const [y, mo, d] = dateEt.split('-').map(Number) as [number, number, number];
  const weekday = new Date(Date.UTC(y, mo - 1, d)).getUTCDay();
  if (weekday === 0 || weekday === 6) {
    return refuse(
      'RUN_TIME_NOT_ALLOWED',
      `${dateEt} is a ${WEEKDAYS[weekday]}; the market is closed on weekends.`,
      'Book a trading day.',
    );
  }
  const holiday = ctx.holidays.get(dateEt);
  if (holiday) {
    return refuse('RUN_TIME_NOT_ALLOWED', `${dateEt} is a market holiday (${holiday}).`, 'Book a trading day.');
  }
  const p = etParts(at);
  const minuteOfDay = p.hour * 60 + p.minute;
  if (minuteOfDay < WINDOW_ET.from || minuteOfDay > WINDOW_ET.to) {
    return refuse(
      'RUN_TIME_NOT_ALLOWED',
      `${atEt} ET is outside ${WINDOW_ET.text} ET (the pre-market open to the post-market close).`,
      'Pick a time inside that window; orders entered overnight wait for the next session anyway.',
    );
  }
  for (const s of ctx.schedule) {
    const slotAt = etToInstant(y, mo, d, s.hour, s.minute);
    if (Math.abs(slotAt.getTime() - at.getTime()) < MIN_GAP_MS) {
      return refuse(
        'RUN_TIME_NOT_ALLOWED',
        `${atEt} ET is within ${MIN_GAP_MS / 60_000} minutes of your scheduled ${s.label} ET run, which happens anyway.`,
        `Use the scheduled run, or pick a time at least ${MIN_GAP_MS / 60_000} minutes away from it.`,
      );
    }
  }
  const f = read(dir);
  for (const r of f.pending) {
    if (Math.abs(Date.parse(r.at) - at.getTime()) < MIN_GAP_MS) {
      return refuse(
        'RUN_TIME_NOT_ALLOWED',
        `${atEt} ET is within ${MIN_GAP_MS / 60_000} minutes of your extra run ${r.id} at ${r.atEt} ET.`,
        'Use that run, or cancel it with cancel_scheduled_run first.',
      );
    }
  }
  if (f.pending.length >= MAX_PENDING) {
    return refuse(
      'TOO_MANY_RUNS',
      `You already have ${f.pending.length} pending extra runs (the limit is ${MAX_PENDING}).`,
      'Cancel one with cancel_scheduled_run first.',
    );
  }
  const sameDay =
    f.pending.filter((r) => r.atEt.startsWith(dateEt)).length +
    f.done.filter((r) => r.outcome === 'ran' && r.atEt.startsWith(dateEt)).length;
  if (sameDay >= ctx.perDay) {
    return refuse(
      'TOO_MANY_RUNS',
      `You already have ${sameDay} extra runs on ${dateEt} (the limit is ${ctx.perDay} per day, scheduled runs not counted).`,
      'Cancel one with cancel_scheduled_run, or rely on the scheduled runs that day.',
    );
  }
  const run: RequestedRun = {
    id: `r${randomBytes(3).toString('hex')}`,
    at: at.toISOString(),
    atEt,
    reason,
    bookedAt: now.toISOString(),
  };
  f.pending = [...f.pending, run].sort(byTime);
  write(dir, f, now);
  return { ok: true, run, pending: f.pending };
}

/** cancel_scheduled_run. */
export function cancelRun(dir: string, id: unknown, now: Date): CancelResult {
  const f = read(dir);
  const run = typeof id === 'string' ? f.pending.find((r) => r.id === id.trim()) : undefined;
  if (!run) {
    return refuse(
      'NOT_FOUND',
      `You have no pending extra run with id ${JSON.stringify(typeof id === 'string' ? id.slice(0, 40) : id ?? null)}.`,
      f.pending.length > 0 ? `Your pending extra runs: ${f.pending.map((r) => `${r.id} (${r.atEt} ET)`).join(', ')}.` : 'You have no pending extra runs.',
    );
  }
  f.pending = f.pending.filter((r) => r !== run);
  write(dir, f, now);
  return { ok: true, cancelled: run, pending: f.pending };
}

/**
 * Removes the booked runs whose time has come: `due` ones (at most `maxLateMs` late) are to run now, `missed` ones
 * (the runner was down or busy) are dropped. Both are recorded for the per-day limit.
 */
export function takeDue(dir: string, now: Date, maxLateMs: number): { due: RequestedRun[]; missed: RequestedRun[] } {
  const f = read(dir);
  const ripe = f.pending.filter((r) => Date.parse(r.at) <= now.getTime());
  if (ripe.length === 0) return { due: [], missed: [] };
  const due = ripe.filter((r) => now.getTime() - Date.parse(r.at) <= maxLateMs);
  const missed = ripe.filter((r) => !due.includes(r));
  f.pending = f.pending.filter((r) => !ripe.includes(r));
  f.done.push(
    ...due.map((r): DoneRun => ({ id: r.id, atEt: r.atEt, outcome: 'ran' })),
    ...missed.map((r): DoneRun => ({ id: r.id, atEt: r.atEt, outcome: 'missed' })),
  );
  write(dir, f, now);
  return { due, missed };
}

/** Market holidays from a get_market_clock result (`upcomingHolidays: [{date, name}]`); malformed entries ignored. */
export function holidaysOf(clock: { upcomingHolidays?: unknown }): Map<string, string> {
  const out = new Map<string, string>();
  if (!Array.isArray(clock.upcomingHolidays)) return out;
  for (const h of clock.upcomingHolidays as { date?: unknown; name?: unknown }[]) {
    if (h && typeof h.date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(h.date)) {
      out.set(h.date, typeof h.name === 'string' && h.name ? h.name : 'market holiday');
    }
  }
  return out;
}
