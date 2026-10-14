import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { type BookContext, bookRun, cancelRun, holidaysOf, nextPendingRun, pendingRuns, takeDue } from '../src/requested.js';
import { parseSchedule } from '../src/time.js';
import { removeDir, tempDir } from './helpers.js';

const NOW = new Date('2026-10-12T13:45:00Z'); // Monday 09:45 ET
const SCHEDULE = parseSchedule('09:30,12:45,15:50');

let dir: string;
beforeEach(() => {
  dir = tempDir();
});
afterEach(() => removeDir(dir));

function ctx(extra: Partial<BookContext> = {}): BookContext {
  return { now: NOW, schedule: SCHEDULE, holidays: new Map(), perDay: 4, ...extra };
}

function book(at_et: unknown, extra: Partial<BookContext> = {}, reason: unknown = 'Re-check AAPL after the Fed minutes') {
  return bookRun(dir, { at_et, reason }, ctx(extra));
}

function refusal(r: ReturnType<typeof book>): { code: string; message: string } {
  if (r.ok) throw new Error(`expected a refusal, got ${JSON.stringify(r.run)}`);
  return r;
}

describe('booking an extra run', () => {
  it('books "HH:MM" for today (ET) and stores it', () => {
    const r = book('14:15');
    if (!r.ok) throw new Error(r.message);
    expect(r.run).toMatchObject({ at: '2026-10-12T18:15:00.000Z', atEt: '2026-10-12 14:15', reason: 'Re-check AAPL after the Fed minutes' });
    expect(r.run.id).toMatch(/^r[0-9a-f]{6}$/);
    expect(pendingRuns(dir)).toEqual([r.run]);
    expect(JSON.parse(readFileSync(join(dir, 'requested-runs.json'), 'utf8')).pending).toHaveLength(1);
  });

  it('accepts a date and time in ET, or ISO-8601 with an offset, and keeps the runs in time order', () => {
    const a = book('2026-10-13 11:00');
    const b = book('2026-10-13T10:00');
    const c = book('2026-10-13T18:00:30Z'); // 14:00 ET, seconds dropped
    expect([a, b, c].map((r) => r.ok && r.run.atEt)).toEqual(['2026-10-13 11:00', '2026-10-13 10:00', '2026-10-13 14:00']);
    expect(pendingRuns(dir).map((r) => r.atEt)).toEqual(['2026-10-13 10:00', '2026-10-13 11:00', '2026-10-13 14:00']);
    expect(nextPendingRun(dir)?.atEt).toBe('2026-10-13 10:00');
  });

  it('refuses malformed times and reasons', () => {
    for (const t of ['tomorrow', '25:00', '2026-02-30 10:00', '2026-10-13', 1415, null]) {
      expect(refusal(book(t)).code).toBe('VALIDATION_FAILED');
    }
    expect(refusal(book('14:15', {}, '')).code).toBe('VALIDATION_FAILED');
    expect(refusal(book('14:15', {}, 'x'.repeat(301))).code).toBe('VALIDATION_FAILED');
    expect(pendingRuns(dir)).toEqual([]);
  });

  it('refuses the past, the next 2 minutes and more than 7 days ahead', () => {
    expect(refusal(book('09:00')).message).toContain('less than 2 minutes from now (it is now 2026-10-12 09:45 ET)');
    expect(refusal(book('09:46')).code).toBe('RUN_TIME_NOT_ALLOWED');
    expect(book('09:48').ok).toBe(true);
    expect(refusal(book('2026-10-19 11:00')).message).toContain('more than 7 days ahead');
    expect(book('2026-10-19 09:00').ok).toBe(true);
  });

  it('refuses weekends, market holidays and times outside 04:00-20:00 ET', () => {
    expect(refusal(book('2026-10-17 10:00')).message).toBe('2026-10-17 is a Saturday; the market is closed on weekends.');
    const holidays = new Map([['2026-10-13', 'Test Day']]);
    expect(refusal(book('2026-10-13 10:00', { holidays })).message).toBe('2026-10-13 is a market holiday (Test Day).');
    expect(refusal(book('2026-10-13 03:59')).message).toContain('outside 04:00-20:00 ET');
    expect(refusal(book('2026-10-13 20:01')).code).toBe('RUN_TIME_NOT_ALLOWED');
    expect(book('2026-10-13 04:00').ok).toBe(true);
    expect(book('2026-10-13 20:00').ok).toBe(true);
  });

  it('keeps 10 minutes between a booked run and the scheduled or other booked runs', () => {
    expect(refusal(book('12:40')).message).toContain('within 10 minutes of your scheduled 12:45 ET run');
    expect(book('12:35').ok).toBe(true);
    const first = book('14:15');
    if (!first.ok) throw new Error(first.message);
    expect(refusal(book('14:20')).message).toContain(`your extra run ${first.run.id} at 2026-10-12 14:15 ET`);
  });

  it('limits the extra runs per ET day, counting the ones that already ran, and the pending runs overall', () => {
    expect(book('11:00', { perDay: 2 }).ok).toBe(true);
    takeDue(dir, new Date('2026-10-12T15:00:30Z'), 600_000); // the 11:00 run ran
    expect(book('14:00', { perDay: 2 }).ok).toBe(true);
    expect(refusal(book('15:00', { perDay: 2 }))).toMatchObject({
      code: 'TOO_MANY_RUNS',
      message: expect.stringContaining('2 extra runs on 2026-10-12 (the limit is 2 per day'),
    });
    expect(book('2026-10-13 15:00', { perDay: 2 }).ok).toBe(true);

    removeDir(dir);
    for (let i = 0; i < 10; i++) expect(book(`2026-10-13 ${String(5 + i).padStart(2, '0')}:00`, { perDay: 20 }).ok).toBe(true);
    expect(refusal(book('2026-10-14 10:00', { perDay: 20 })).message).toContain('10 pending extra runs (the limit is 10)');
  });

  it('cancels a pending run by id', () => {
    const r = book('14:15');
    if (!r.ok) throw new Error(r.message);
    expect(cancelRun(dir, 'nope', NOW)).toMatchObject({ ok: false, code: 'NOT_FOUND', hint: expect.stringContaining(r.run.id) });
    expect(cancelRun(dir, r.run.id, NOW)).toMatchObject({ ok: true, cancelled: r.run, pending: [] });
    expect(pendingRuns(dir)).toEqual([]);
  });
});

describe('taking due runs', () => {
  it('returns runs at or after their time, drops the ones more than the limit late', () => {
    const a = book('14:15');
    const b = book('15:00');
    if (!a.ok || !b.ok) throw new Error('booking failed');
    expect(takeDue(dir, new Date('2026-10-12T18:14:59Z'), 600_000)).toEqual({ due: [], missed: [] });
    expect(takeDue(dir, new Date('2026-10-12T18:15:00Z'), 600_000)).toEqual({ due: [a.run], missed: [] });
    expect(takeDue(dir, new Date('2026-10-12T19:20:00Z'), 600_000)).toEqual({ due: [], missed: [b.run] });
    expect(pendingRuns(dir)).toEqual([]);
    const done = JSON.parse(readFileSync(join(dir, 'requested-runs.json'), 'utf8')).done;
    expect(done.map((d: { outcome: string }) => d.outcome)).toEqual(['ran', 'missed']);
  });

  it('treats a missing or corrupt file as no booked runs', () => {
    expect(pendingRuns(join(dir, 'missing'))).toEqual([]);
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, 'requested-runs.json'), '{not json');
    expect(pendingRuns(dir)).toEqual([]);
    expect(book('14:15').ok).toBe(true);
  });
});

describe('holidaysOf', () => {
  it('reads upcomingHolidays from the market clock and ignores malformed entries', () => {
    const h = holidaysOf({
      upcomingHolidays: [{ date: '2026-11-26', name: 'Thanksgiving Day' }, { date: 'soon' }, null, { date: '2026-12-25' }],
    });
    expect([...h]).toEqual([
      ['2026-11-26', 'Thanksgiving Day'],
      ['2026-12-25', 'market holiday'],
    ]);
    expect(holidaysOf({}).size).toBe(0);
  });
});
