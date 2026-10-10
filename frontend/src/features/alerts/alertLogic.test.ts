import { describe, expect, it } from 'vitest'
import type { Alert, Quote } from '@/lib/api/types'
import {
  defaultInput,
  describeCondition,
  endOfDayEt,
  expiryFor,
  fromWireThreshold,
  liveView,
  sortAlerts,
  statusLine,
  thresholdError,
  toWireThreshold,
  wouldFireNow,
} from './alertLogic'

const quote = (over: Partial<Quote>): Quote =>
  ({
    symbol: 'AAPL', bid: null, bidSize: null, ask: null, askSize: null, last: '229.4000', lastSize: null, lastAt: null,
    prevClose: '225.0000', change: '4.4000', changePct: '0.0195555556', dayOpen: null, dayHigh: null, dayLow: null, volume: null,
    volumeIsPartial: false, extended: null, asOf: null, ageMs: null, quality: 'REALTIME_SINGLE_VENUE', source: 'test', halted: false,
    session: 'REGULAR', ...over,
  }) as Quote

const alert = (over: Partial<Alert>): Alert =>
  ({
    id: 'alr_1', symbol: 'AAPL', condition: 'PRICE_GTE', threshold: '230.0000', repeat: false, status: 'ACTIVE', armed: true, note: null,
    expiresAt: null, triggerCount: 0, lastTriggeredAt: null, lastTriggerPrice: null, createdAt: '2026-10-09T14:00:00.000Z',
    updatedAt: '2026-10-09T14:00:00.000Z', ...over,
  }) as Alert

describe('price alert form (§19.1, §22.10)', () => {
  it('shows a change in percent and sends a fraction, exactly', () => {
    expect(toWireThreshold('CHANGE_PCT_GTE', '5')).toBe('0.05')
    expect(toWireThreshold('CHANGE_PCT_LTE', '-3.25%')).toBe('-0.0325')
    expect(toWireThreshold('CHANGE_PCT_LTE', '−3')).toBe('-0.03')
    expect(toWireThreshold('PRICE_GTE', '$1,230.50')).toBe('1230.5')
    expect(toWireThreshold('PRICE_GTE', 'abc')).toBeNull()
    expect(fromWireThreshold('CHANGE_PCT_GTE', '0.050000')).toBe('5')
    expect(fromWireThreshold('CHANGE_PCT_LTE', '-0.032500')).toBe('-3.25')
    expect(fromWireThreshold('PRICE_GTE', '230.0000')).toBe('230.00')
    expect(fromWireThreshold('PRICE_LTE', '0.4256')).toBe('0.4256')
    expect(fromWireThreshold('PRICE_LTE', null)).toBe('')
  })

  it('validates thresholds like the server', () => {
    expect(thresholdError('PRICE_GTE', '')).toBe('Enter a price.')
    expect(thresholdError('PRICE_GTE', '0')).toMatch(/above \$0/)
    expect(thresholdError('PRICE_GTE', '1000000')).toMatch(/below/)
    expect(thresholdError('PRICE_GTE', '1.23456')).toMatch(/4 decimals/)
    expect(thresholdError('PRICE_GTE', '230.5')).toBeNull()
    expect(thresholdError('CHANGE_PCT_LTE', '-100')).toMatch(/100%/)
    expect(thresholdError('CHANGE_PCT_GTE', '1001')).toMatch(/1000%/)
    expect(thresholdError('CHANGE_PCT_GTE', '5')).toBeNull()
    expect(thresholdError('CHANGE_PCT_GTE', 'x')).toBe('Enter a number.')
  })

  it('starts 5% away from the live price, or at ±5% for a change, so a new alert does not fire at once', () => {
    const live = liveView(quote({}))
    expect(defaultInput('PRICE_GTE', live)).toBe('240.87')
    expect(defaultInput('PRICE_LTE', live)).toBe('217.93')
    expect(defaultInput('CHANGE_PCT_GTE', live)).toBe('5')
    expect(defaultInput('CHANGE_PCT_LTE', live)).toBe('-5')
    expect(defaultInput('PRICE_LTE', { price: null, changePct: null })).toBe('')
  })

  it('says when the condition already holds', () => {
    const live = liveView(quote({}))
    expect(wouldFireNow('PRICE_GTE', '229.40', live)).toBe(true)
    expect(wouldFireNow('PRICE_GTE', '229.41', live)).toBe(false)
    expect(wouldFireNow('PRICE_LTE', '230', live)).toBe(true)
    expect(wouldFireNow('CHANGE_PCT_GTE', '0.0195', live)).toBe(true)
    expect(wouldFireNow('CHANGE_PCT_LTE', '-0.01', live)).toBe(false)
    expect(wouldFireNow('PRICE_GTE', null, live)).toBe(false)
  })

  it('uses the extended line outside the regular session', () => {
    const pre = liveView(quote({ extended: { session: 'PRE', price: '233.00', change: '3.60', changePct: '0.0156931124', at: null } }))
    expect(pre).toEqual({ price: '233.00', changePct: '0.0156931124' })
    // after hours the day's change runs from the previous close, not from today's close
    const post = liveView(quote({ extended: { session: 'POST', price: '236.25', change: '6.85', changePct: '0.0298', at: null } }))
    expect(post.price).toBe('236.25')
    expect(post.changePct).toBe('0.0500000000')
  })

  it('describes conditions with display formatting', () => {
    expect(describeCondition('PRICE_GTE', '230.0000')).toBe('Price ≥ $230.00')
    expect(describeCondition('CHANGE_PCT_LTE', '-0.030000')).toBe('Day change ≤ −3.00%')
    expect(describeCondition(null, null)).toBe('Alert')
  })

  it('expires at the end of the ET day, across DST', () => {
    // Fri 2026-10-09 10:00 ET (EDT, UTC-4) → 23:59:59 EDT = 03:59:59Z next day
    expect(endOfDayEt(new Date('2026-10-09T14:00:00Z')).toISOString()).toBe('2026-10-10T03:59:59.000Z')
    // Sat 2026-11-07 22:00 ET (EST after the change on Nov 1) → 04:59:59Z
    expect(endOfDayEt(new Date('2026-11-08T03:00:00Z')).toISOString()).toBe('2026-11-08T04:59:59.000Z')
    const now = new Date('2026-10-09T14:00:00Z')
    expect(expiryFor('NEVER', now)).toBeNull()
    expect(expiryFor('WEEK', now)).toBe('2026-10-16T14:00:00.000Z')
    expect(expiryFor('MONTH', now)).toBe('2026-11-08T14:00:00.000Z')
  })

  it('orders alerts by status, newest first, and summarizes their state', () => {
    const list = sortAlerts([
      alert({ id: 'a', status: 'EXPIRED' }),
      alert({ id: 'b', status: 'ACTIVE', createdAt: '2026-10-09T14:00:00Z' }),
      alert({ id: 'c', status: 'TRIGGERED', lastTriggerPrice: '230.1500' }),
      alert({ id: 'd', status: 'ACTIVE', createdAt: '2026-10-09T15:00:00Z' }),
      alert({ id: 'e', status: 'PAUSED' }),
    ])
    expect(list.map((a) => a.id)).toEqual(['d', 'b', 'e', 'c', 'a'])
    expect(statusLine(alert({ status: 'TRIGGERED', lastTriggerPrice: '230.1500' }))).toBe('Fired at $230.15')
    expect(statusLine(alert({ repeat: true, armed: false, triggerCount: 2 }))).toMatch(/^Fired 2×/)
    expect(statusLine(alert({ status: 'PAUSED' }))).toBe('Paused')
  })
})
