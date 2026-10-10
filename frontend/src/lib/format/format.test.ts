import { describe, expect, it } from 'vitest'
import { formatMoney, formatMoneyCompact } from './money'
import { abbreviate, arrow, changeDirection, direction, formatChange, formatInteger, formatPct, formatPrice, formatQty, moneyDirection, pctDirection } from './number'
import { etWallTimeToIso, formatCountdown, formatDateEt, formatSessionStartEt, formatTimeEt, serverNowMs } from './time'

const MINUS = '−'

describe('formatPrice (§22.7)', () => {
  it('uses 2 decimals, or 4 below $1, with grouping', () => {
    expect(formatPrice('227.4000')).toBe('227.40')
    expect(formatPrice('1234.5')).toBe('1,234.50')
    expect(formatPrice('0.51234')).toBe('0.5123')
    expect(formatPrice('1.0000')).toBe('1.00')
  })
  it('shows -- when missing', () => {
    expect(formatPrice(null)).toBe('--')
    expect(formatPrice(undefined)).toBe('--')
    expect(formatPrice('n/a')).toBe('--')
  })
})

describe('signed values', () => {
  it('uses an explicit plus and a real minus sign (U+2212)', () => {
    expect(formatChange('3.12')).toBe('+3.12')
    expect(formatChange('-0.82')).toBe(`${MINUS}0.82`)
    expect(formatChange('0.0000')).toBe('0.00')
    expect(formatChange('-0.0123', '0.5123')).toBe(`${MINUS}0.0123`)
  })
  it('formats fractions as percentages', () => {
    expect(formatPct('0.0123')).toBe('+1.23%')
    expect(formatPct('-0.00825')).toBe(`${MINUS}0.83%`)
    expect(formatPct('0')).toBe('0.00%')
    expect(formatPct('0.25', { signed: false })).toBe('25.00%')
    expect(formatPct(null)).toBe('--')
  })
  it('pairs color with an arrow', () => {
    expect(direction('1.2')).toBe('up')
    expect(direction('-0.01')).toBe('down')
    expect(direction('0.00')).toBe('flat')
    expect(arrow('-3')).toBe('▼')
    expect(arrow('3')).toBe('▲')
    expect(arrow(null)).toBe('')
  })
})

describe('formatMoney', () => {
  it('formats cash', () => {
    expect(formatMoney('1234.56')).toBe('$1,234.56')
    expect(formatMoney('1234.56', { signed: true })).toBe('+$1,234.56')
    expect(formatMoney('-12.3')).toBe(`${MINUS}$12.30`)
    expect(formatMoney('0')).toBe('$0.00')
    expect(formatMoney(null)).toBe('--')
  })
  it('abbreviates large amounts', () => {
    expect(formatMoneyCompact('3420000000000')).toBe('$3.42T')
  })
})

describe('abbreviate', () => {
  it('keeps 3 significant digits', () => {
    expect(abbreviate(41_234_567)).toBe('41.2M')
    expect(abbreviate('3420000000000.00')).toBe('3.42T')
    expect(abbreviate(123_456_789)).toBe('123M')
    expect(abbreviate(1_500)).toBe('1.5K')
    expect(abbreviate(999)).toBe('999')
    expect(abbreviate(999_960_000)).toBe('1B')
    expect(abbreviate(null)).toBe('--')
  })
})

describe('quantities and integers', () => {
  it('keeps significant decimals only', () => {
    expect(formatQty('10.00000')).toBe('10')
    expect(formatQty('0.95274')).toBe('0.95274')
    expect(formatQty('1200')).toBe('1,200')
    expect(formatInteger(41234567)).toBe('41,234,567')
  })
})

describe('time (ET)', () => {
  it('formats ET times with a suffix', () => {
    expect(formatTimeEt('2026-10-09T20:12:00.000Z')).toBe('4:12 PM ET')
    expect(formatDateEt('2026-10-13')).toBe('Oct 13, 2026')
  })
  it('formats countdowns', () => {
    expect(formatCountdown(((2 * 60 + 14) * 60 + 7) * 1000)).toBe('2:14:07')
    expect(formatCountdown(65_000)).toBe('1:05')
    expect(formatCountdown(-5)).toBe('0:00')
  })
  it('runs countdowns on the server clock (an offset SIM clock, a wrong device clock)', () => {
    // the server says 14:00Z (e.g. the e2e anchor) while the device thinks it is a day later
    const fetchedAt = Date.parse('2026-10-10T14:00:00Z')
    const now = serverNowMs('2026-10-09T14:00:00.000Z', fetchedAt, fetchedAt + 65_000)
    expect(new Date(now).toISOString()).toBe('2026-10-09T14:01:05.000Z')
    expect(formatCountdown(Date.parse('2026-10-09T20:00:00Z') - now)).toBe('5:58:55')
    expect(serverNowMs('garbage', fetchedAt, 42)).toBe(42) // unusable clock: fall back to the device
  })
  it('names the day of a session start that is not today (ET)', () => {
    const fridayEvening = Date.parse('2026-10-09T22:00:00Z') // Fri 6:00 PM ET
    expect(formatSessionStartEt('2026-10-12T08:00:00Z', fridayEvening)).toBe('Mon 4:00 AM ET')
    expect(formatSessionStartEt('2026-10-09T23:30:00Z', fridayEvening)).toBe('7:30 PM ET')
    // 03:00Z on Oct 10 is still Friday evening in New York
    expect(formatSessionStartEt('2026-10-10T01:00:00Z', Date.parse('2026-10-10T03:00:00Z'))).toBe('9:00 PM ET')
    expect(formatSessionStartEt(null, fridayEvening)).toBe('--')
  })
})

describe('etWallTimeToIso (calendar overrides are entered in ET)', () => {
  it('uses EST in winter and EDT in summer', () => {
    expect(etWallTimeToIso('2026-11-27', '13:00')).toBe('2026-11-27T18:00:00.000Z')
    expect(etWallTimeToIso('2026-07-02', '13:00')).toBe('2026-07-02T17:00:00.000Z')
    expect(etWallTimeToIso('2026-12-24', '17:00')).toBe('2026-12-24T22:00:00.000Z')
  })

  it('rejects malformed input', () => {
    expect(etWallTimeToIso('2026-7-2', '13:00')).toBeNull()
    expect(etWallTimeToIso('2026-07-02', '1pm')).toBeNull()
  })
})

// Review regression (§22.7): a value that rounds to zero was colored by its raw sign ("0.00%" in red or green).
describe('direction as displayed', () => {
  it('is flat when the shown value rounds to zero', () => {
    expect(formatPct('-0.00004')).toBe('0.00%')
    expect(direction('-0.00004')).toBe('down')
    expect(pctDirection('-0.00004')).toBe('flat')
    expect(pctDirection('-0.00005')).toBe('down')
    expect(pctDirection('0.0123')).toBe('up')
    expect(moneyDirection('-0.004')).toBe('flat')
    expect(moneyDirection('-0.02')).toBe('down')
    expect(changeDirection('0.004', '227.41')).toBe('flat')
    expect(changeDirection('0.0004', '0.5123')).toBe('up') // 4 decimals below $1
    expect(pctDirection(null)).toBe('flat')
  })
})
