import { describe, expect, it } from 'vitest'
import {
  abs,
  add,
  compare,
  div,
  isDecimal,
  mul,
  negate,
  parseUserDecimal,
  roundToTick,
  setScale,
  shift,
  stripTrailingZeros,
  sub,
  tickFor,
} from './decimal'

describe('decimal (string arithmetic, no floating point)', () => {
  it('adds exactly where floats do not', () => {
    expect(add('0.1', '0.2')).toBe('0.3')
    expect(add('227.4000', '-0.01')).toBe('227.3900')
    expect(sub('500.00', '125.00')).toBe('375.00')
    expect(sub('0', '0.05')).toBe('-0.05')
  })

  it('compares across scales', () => {
    expect(compare('227.4000', '227.4')).toBe(0)
    expect(compare('-1', '0.5')).toBe(-1)
    expect(compare('10', '9.9999')).toBe(1)
  })

  it('multiplies and divides with an explicit scale and rounding mode', () => {
    // §18.4: 0.95274 sh × 131.2000 = 124.999488 → principal 125.00 (PRINCIPAL = HALF_UP to cents)
    expect(mul('0.95274', '131.2000')).toBe('124.999488000')
    expect(setScale(mul('0.95274', '131.2000'), 2, 'HALF_UP')).toBe('125.00')
    expect(div('1', '3', 5, 'FLOOR')).toBe('0.33333')
    expect(div('2', '3', 2, 'HALF_UP')).toBe('0.67')
    expect(div('-2', '3', 2, 'HALF_UP')).toBe('-0.67')
    expect(div('375.00', '231.9600', 0, 'FLOOR')).toBe('1')
    expect(() => div('1', '0', 2, 'HALF_UP')).toThrow(RangeError)
  })

  it('rounds with every policy', () => {
    expect(setScale('2.345', 2, 'HALF_UP')).toBe('2.35')
    expect(setScale('2.345', 2, 'HALF_EVEN')).toBe('2.34')
    expect(setScale('2.355', 2, 'HALF_EVEN')).toBe('2.36')
    expect(setScale('-2.345', 2, 'HALF_UP')).toBe('-2.35')
    expect(setScale('-1.001', 2, 'FLOOR')).toBe('-1.01')
    expect(setScale('1.001', 2, 'CEILING')).toBe('1.01')
    expect(setScale('1.009', 2, 'DOWN')).toBe('1.00')
    expect(setScale('5', 2)).toBe('5.00')
  })

  it('shifts the decimal point exactly (fractions to percent)', () => {
    expect(shift('0.0123', 2)).toBe('1.23')
    expect(shift('0.0123456789', 2)).toBe('1.23456789')
    expect(shift('41234567', -6)).toBe('41.234567')
    expect(shift('12', 2)).toBe('1200')
  })

  it('normalises and negates', () => {
    expect(stripTrailingZeros('227.4000')).toBe('227.4')
    expect(stripTrailingZeros('10.00000')).toBe('10')
    expect(negate('3.12')).toBe('-3.12')
    expect(abs('-0.82')).toBe('0.82')
  })

  it('validates and parses user input', () => {
    expect(isDecimal('12.50')).toBe(true)
    expect(isDecimal('.5')).toBe(true)
    expect(isDecimal('1e5')).toBe(false)
    expect(isDecimal('')).toBe(false)
    expect(parseUserDecimal(' $1,234.5 ')).toBe('1234.5')
    expect(parseUserDecimal('abc')).toBeNull()
  })

  it('uses the Reg NMS tick (§6.2)', () => {
    expect(tickFor('227.41')).toBe('0.01')
    expect(tickFor('0.9999')).toBe('0.0001')
    expect(roundToTick('227.405')).toBe('227.41')
    expect(roundToTick('0.51234')).toBe('0.5123')
  })
})
