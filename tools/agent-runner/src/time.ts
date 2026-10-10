// America/New_York wall-clock helpers (the league runs on ET; the container's TZ is never relied on).

export const ET = 'America/New_York';

export interface Clock {
  now(): Date;
}

export const systemClock: Clock = { now: () => new Date() };

const partsFormat = new Intl.DateTimeFormat('en-US', {
  timeZone: ET,
  hourCycle: 'h23',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
});

export interface EtParts {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
}

export function etParts(d: Date): EtParts {
  const out: Record<string, number> = {};
  for (const p of partsFormat.formatToParts(d)) {
    if (p.type !== 'literal') out[p.type] = Number(p.value);
  }
  return {
    year: out.year ?? 0,
    month: out.month ?? 0,
    day: out.day ?? 0,
    hour: out.hour ?? 0,
    minute: out.minute ?? 0,
    second: out.second ?? 0,
  };
}

const pad = (n: number, w = 2): string => String(n).padStart(w, '0');

/** The ET calendar date of `d`, "YYYY-MM-DD". */
export function etDate(d: Date): string {
  const p = etParts(d);
  return `${pad(p.year, 4)}-${pad(p.month)}-${pad(p.day)}`;
}

/** "HH:MM" ET of `d`. */
export function etTime(d: Date): string {
  const p = etParts(d);
  return `${pad(p.hour)}:${pad(p.minute)}`;
}

/** The instant at which the ET wall clock reads `y-m-d h:mi` (for a skipped DST hour, the instant one hour later). */
export function etToInstant(y: number, m: number, d: number, h: number, mi: number): Date {
  const wall = Date.UTC(y, m - 1, d, h, mi);
  let guess = wall;
  for (let i = 0; i < 3; i++) {
    const p = etParts(new Date(guess));
    const seen = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
    const diff = wall - seen;
    if (diff === 0) break;
    guess += diff;
  }
  return new Date(guess);
}

export interface SlotTime {
  hour: number;
  minute: number;
  label: string;
}

/** Parses AGENT_SCHEDULE_ET ("09:45,12:30,15:30"): sorted, de-duplicated, at least one slot. */
export function parseSchedule(text: string): SlotTime[] {
  const slots = new Map<string, SlotTime>();
  for (const raw of text.split(',')) {
    const t = raw.trim();
    if (!t) continue;
    const m = /^(\d{1,2}):(\d{2})$/.exec(t);
    const hour = m ? Number(m[1]) : Number.NaN;
    const minute = m ? Number(m[2]) : Number.NaN;
    if (!m || hour > 23 || minute > 59) {
      throw new Error(`AGENT_SCHEDULE_ET: "${t}" is not an ET time like 09:45`);
    }
    const label = `${pad(hour)}:${pad(minute)}`;
    slots.set(label, { hour, minute, label });
  }
  if (slots.size === 0) throw new Error('AGENT_SCHEDULE_ET: no times given');
  return [...slots.values()].sort((a, b) => a.hour * 60 + a.minute - (b.hour * 60 + b.minute));
}

export interface Slot {
  at: Date;
  dateEt: string;
  label: string;
}

/** The first slot strictly after `after` (any day of the week: the market clock decides whether it trades). */
export function nextSlot(after: Date, slots: SlotTime[]): Slot {
  const start = etParts(after);
  // Walk ET calendar days from `after`'s ET date; noon UTC of that date keeps Date arithmetic away from DST edges.
  const base = Date.UTC(start.year, start.month - 1, start.day, 12);
  for (let dayOffset = 0; dayOffset < 8; dayOffset++) {
    const day = new Date(base + dayOffset * 86_400_000);
    const y = day.getUTCFullYear();
    const m = day.getUTCMonth() + 1;
    const d = day.getUTCDate();
    for (const s of slots) {
      const at = etToInstant(y, m, d, s.hour, s.minute);
      if (at.getTime() > after.getTime()) {
        return { at, dateEt: `${pad(y, 4)}-${pad(m)}-${pad(d)}`, label: s.label };
      }
    }
  }
  throw new Error('no schedule slot within 8 days');
}

/** Compact UTC timestamp for file names: 20261012T134500Z. */
export function fileStamp(d: Date): string {
  return d.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}Z$/, 'Z');
}
