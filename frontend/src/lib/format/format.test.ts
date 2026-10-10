import { describe, expect, it } from 'vitest'
import { formatMoney, formatMoneyCompact } from './money'
import { abbreviate, arrow, direction, formatChange, formatInteger, formatPct, formatPrice, formatQty } from './number'
import { formatCountdown, formatDateEt, formatTimeEt } from './time'

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
})
