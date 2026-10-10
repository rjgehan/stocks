import { describe, expect, it } from 'vitest';
import { etDate, etTime, etToInstant, fileStamp, nextSlot, parseSchedule } from '../src/time.js';

const SLOTS = parseSchedule('09:45,12:30,15:30');

describe('schedule', () => {
  it('parses, sorts and de-duplicates AGENT_SCHEDULE_ET', () => {
    expect(parseSchedule(' 15:30, 9:45 ,12:30,09:45').map((s) => s.label)).toEqual(['09:45', '12:30', '15:30']);
    for (const bad of ['', '25:00', '9:5', 'noon', '09:60']) expect(() => parseSchedule(bad)).toThrow(/AGENT_SCHEDULE_ET/);
  });

  it('finds the next slot in ET, the same day or the next', () => {
    const s1 = nextSlot(new Date('2026-10-12T13:00:00Z'), SLOTS); // 09:00 EDT
    expect(s1).toMatchObject({ dateEt: '2026-10-12', label: '09:45' });
    expect(s1.at.toISOString()).toBe('2026-10-12T13:45:00.000Z');
    expect(nextSlot(s1.at, SLOTS)).toMatchObject({ dateEt: '2026-10-12', label: '12:30' });
    const late = nextSlot(new Date('2026-10-12T19:31:00Z'), SLOTS); // 15:31 EDT
    expect(late).toMatchObject({ dateEt: '2026-10-13', label: '09:45' });
    // 23:30 EDT on the 12th is already the 13th in UTC: still the 13th's 09:45 ET.
    expect(nextSlot(new Date('2026-10-13T03:30:00Z'), SLOTS)).toMatchObject({ dateEt: '2026-10-13', label: '09:45' });
  });

  it('follows daylight saving time (EDT → EST on 2026-11-01, EST → EDT on 2026-03-08)', () => {
    expect(nextSlot(new Date('2026-10-31T20:00:00Z'), SLOTS).at.toISOString()).toBe('2026-11-01T14:45:00.000Z');
    expect(nextSlot(new Date('2026-10-30T20:00:00Z'), SLOTS).at.toISOString()).toBe('2026-10-31T13:45:00.000Z');
    expect(nextSlot(new Date('2026-03-07T21:00:00Z'), SLOTS).at.toISOString()).toBe('2026-03-08T13:45:00.000Z');
    expect(etToInstant(2026, 3, 6, 9, 45).toISOString()).toBe('2026-03-06T14:45:00.000Z');
  });

  it('formats ET dates and file stamps', () => {
    const d = new Date('2026-10-13T03:30:15.123Z');
    expect(etDate(d)).toBe('2026-10-12');
    expect(etTime(d)).toBe('23:30');
    expect(fileStamp(d)).toBe('20261013T033015Z');
  });
});
