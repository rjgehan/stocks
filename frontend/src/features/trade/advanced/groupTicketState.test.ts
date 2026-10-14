import { describe, expect, it } from 'vitest'
import type { OrderGroupRule } from '@/lib/api/groupTypes'
import { groupChipLabel, heldLabel } from '@/features/groups/groupLabels'
import {
  availableGroupTypes,
  buildGroupRequest,
  defaultPrices,
  entryReference,
  entryTypesFor,
  groupInputFor,
  groupTicketReducer,
  initialGroupTicket,
  pctFrom,
  rewardToRisk,
  wholeShares,
} from './groupTicketState'

const rules: OrderGroupRule[] = [
  { type: 'BRACKET', legs: '', entryTypes: ['MARKET', 'LIMIT'], timeInForce: ['DAY', 'GTC'], notes: '' },
  { type: 'OCO', legs: '', entryTypes: [], timeInForce: ['DAY', 'GTC'], notes: '' },
  { type: 'OTOCO', legs: '', entryTypes: ['MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT'], timeInForce: ['DAY', 'GTC'], notes: '' },
]

function bracket() {
  let s = initialGroupTicket({ clientOrderId: 'web-g-1' })
  s = groupTicketReducer(s, { type: 'field', field: 'qty', value: '10' })
  s = groupTicketReducer(s, { type: 'field', field: 'entryLimit', value: '100.00' })
  s = groupTicketReducer(s, { type: 'field', field: 'tpLimit', value: '110.00' })
  s = groupTicketReducer(s, { type: 'field', field: 'slStop', value: '95.00' })
  return s
}

describe('advanced ticket (§11.9, §22.5)', () => {
  it('offers only the group types the rulebook lists, in ticket order', () => {
    expect(availableGroupTypes(rules)).toEqual(['BRACKET', 'OCO', 'OTOCO'])
    expect(availableGroupTypes(null)).toEqual([])
    expect(entryTypesFor('BRACKET', rules)).toEqual(['MARKET', 'LIMIT'])
    expect(entryTypesFor('OTO', rules)).toEqual(['MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT'])
  })

  it('builds a bracket request with whole shares, the group id and the legs it uses', () => {
    const req = buildGroupRequest(bracket(), 'AAPL')
    expect(req).toEqual({
      clientOrderId: 'web-g-1',
      type: 'BRACKET',
      symbol: 'AAPL',
      side: 'BUY',
      qty: '10',
      timeInForce: 'DAY',
      entry: { type: 'LIMIT', limitPrice: '100.00', stopPrice: null },
      takeProfit: { limitPrice: '110.00' },
      stopLoss: { stopPrice: '95.00', limitPrice: null, type: 'STOP' },
      secondary: null,
      rationale: null,
      doNotReduce: false,
      acknowledge: [],
    })
  })

  it('needs whole shares and every price its legs use', () => {
    expect(wholeShares('10')).toBe('10')
    expect(wholeShares('1,000')).toBe('1000')
    expect(wholeShares('2.5')).toBeNull()
    expect(wholeShares('0')).toBeNull()
    expect(buildGroupRequest(groupTicketReducer(bracket(), { type: 'field', field: 'qty', value: '2.5' }), 'AAPL')).toBeNull()
    expect(buildGroupRequest(groupTicketReducer(bracket(), { type: 'field', field: 'tpLimit', value: '' }), 'AAPL')).toBeNull()
    // a stop-limit stop loss needs its limit
    const sl = groupTicketReducer(bracket(), { type: 'slUseLimit', value: true })
    expect(buildGroupRequest(sl, 'AAPL')).toBeNull()
    const withLimit = groupTicketReducer(sl, { type: 'field', field: 'slLimit', value: '94.50' })
    expect(buildGroupRequest(withLimit, 'AAPL')?.stopLoss).toEqual({ stopPrice: '95.00', limitPrice: '94.50', type: 'STOP_LIMIT' })
    // a market entry needs no entry price
    const mkt = groupTicketReducer(groupTicketReducer(bracket(), { type: 'entryType', entryType: 'MARKET' }), { type: 'field', field: 'entryLimit', value: '' })
    expect(buildGroupRequest(mkt, 'AAPL')?.entry).toEqual({ type: 'MARKET', limitPrice: null, stopPrice: null })
  })

  it('builds an OCO on its side without an entry, and an OTO with its then-order', () => {
    let oco = groupTicketReducer(bracket(), { type: 'groupType', groupType: 'OCO' })
    oco = groupTicketReducer(oco, { type: 'ocoSide', side: 'SELL' })
    const r = buildGroupRequest(oco, 'F')
    expect(r).toMatchObject({ type: 'OCO', side: 'SELL', entry: null, secondary: null, takeProfit: { limitPrice: '110.00' } })
    let oto = groupTicketReducer(bracket(), { type: 'groupType', groupType: 'OTO' })
    oto = groupTicketReducer(oto, { type: 'secondaryType', secondaryType: 'STOP' })
    expect(buildGroupRequest(oto, 'F')).toBeNull()
    oto = groupTicketReducer(oto, { type: 'field', field: 'secondaryStop', value: '90.00' })
    expect(buildGroupRequest(oto, 'F')).toMatchObject({ type: 'OTO', takeProfit: null, stopLoss: null, secondary: { type: 'STOP', stopPrice: '90.00', limitPrice: null } })
  })

  it('a stop entry is not offered by a bracket: switching to one falls back to Limit', () => {
    let s = groupTicketReducer(initialGroupTicket(), { type: 'groupType', groupType: 'OTOCO' })
    s = groupTicketReducer(s, { type: 'entryType', entryType: 'STOP' })
    expect(groupTicketReducer(s, { type: 'groupType', groupType: 'BRACKET' }).entryType).toBe('LIMIT')
    expect(groupTicketReducer(s, { type: 'groupType', groupType: 'OTO' }).entryType).toBe('STOP')
  })

  it('a submitted group gets a new id and keeps its setup', () => {
    const s = groupTicketReducer({ ...bracket(), note: 'why', gfvAck: true }, { type: 'submitted', clientOrderId: 'web-g-2' })
    expect(s).toMatchObject({ clientOrderId: 'web-g-2', qty: '', note: '', gfvAck: false, tpLimit: '110.00', groupType: 'BRACKET' })
  })

  it('measures the legs from the entry price and shows reward to risk', () => {
    const s = bracket()
    expect(entryReference(s, { ask: '101.00', last: '100.50' })).toBe('100.00')
    expect(entryReference(groupTicketReducer(s, { type: 'entryType', entryType: 'MARKET' }), { ask: '101.00', last: '100.50' })).toBe('101.00')
    expect(pctFrom('100.00', '110.00')).toBe('+10.0%')
    expect(pctFrom('100.00', '95.00')).toBe('−5.0%')
    expect(pctFrom(null, '95.00')).toBeNull()
    expect(rewardToRisk('100.00', '110.00', '95.00')).toBe('1 : 2.0')
    expect(rewardToRisk('100.00', '110.00', '101.00')).toBeNull()
  })

  it('defaults the prices around the market on tick', () => {
    expect(defaultPrices('BRACKET', 'BUY', { bid: '99.98', ask: '100.00', last: '100.00' })).toMatchObject({ entryLimit: '99.98', tpLimit: '104.00', slStop: '97.00' })
    expect(defaultPrices('OCO', 'SELL', { last: '20.00' })).toEqual({ tpLimit: '20.80', slStop: '19.20' })
    expect(defaultPrices('OCO', 'BUY', { last: '20.00' })).toEqual({ tpLimit: '19.20', slStop: '20.80' })
    // review finding: the 5% legs rounded outward always tripped the server's 5% FAT_FINGER warning; 4% stays clear
    for (const last of ['11.97', '0.9731', '227.43', '3.01']) {
      const p = defaultPrices('BRACKET', 'BUY', { last })
      const pct = (Number(p.tpLimit) / Number(last) - 1) * 100
      expect(pct).toBeLessThanOrEqual(5)
      for (const side of ['BUY', 'SELL'] as const) {
        const o = defaultPrices('OCO', side, { last })
        for (const v of [o.tpLimit, o.slStop]) expect(Math.abs(Number(v) / Number(last) - 1) * 100).toBeLessThanOrEqual(5)
      }
    }
    expect(defaultPrices('BRACKET', 'BUY', { last: null })).toEqual({})
  })

  it('maps server fields named after a leg to the ticket inputs', () => {
    expect(groupInputFor('takeProfit.limitPrice')).toBe('tpLimit')
    expect(groupInputFor('stopLoss.stopPrice')).toBe('slStop')
    expect(groupInputFor('stopLoss.limitPrice')).toBe('slLimit')
    expect(groupInputFor('entry.limitPrice')).toBe('entryLimit')
    expect(groupInputFor('entry.type')).toBe('entryType')
    expect(groupInputFor('qty')).toBe('qty')
    expect(groupInputFor('acknowledge')).toBeNull()
  })

  it('labels a leg chip and a held child', () => {
    expect(groupChipLabel({ type: 'BRACKET', role: 'TAKE_PROFIT' })).toBe('Bracket · TP')
    expect(groupChipLabel({ type: 'OCO', role: 'STOP_LOSS' })).toBe('OCO · SL')
    expect(groupChipLabel(null)).toBeNull()
    expect(heldLabel({ held: true })).toBe('Waiting for entry')
    expect(heldLabel({ held: false })).toBeNull()
  })
})

describe('group review lines (review finding: "Buy 1 · Limit · limit 11.98")', () => {
  it('reads every leg the same way', async () => {
    const { legLine } = await import('./GroupReviewSheet')
    expect(legLine({ type: 'LIMIT', limitPrice: '11.98' })).toBe('Limit 11.98')
    expect(legLine({ type: 'MARKET' })).toBe('Market')
    expect(legLine({ type: 'STOP', stopPrice: '12.1' })).toBe('Stop 12.10')
    expect(legLine({ type: 'STOP_LIMIT', stopPrice: '12.10', limitPrice: '12.15' })).toBe('Stop 12.10 / limit 12.15')
  })
})
