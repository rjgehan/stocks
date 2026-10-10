import { describe, expect, it } from 'vitest'
import type { CorporateAction } from '@/lib/api/types'
import { caSummary, splitEffect, splitKind, splitRatio, validRate } from './corporateActionLabels'

const ca = (over: Partial<CorporateAction>): CorporateAction => ({ type: 'FORWARD_SPLIT', symbol: 'SOFI', oldRate: null, newRate: null, cashRate: null, newSymbol: null, ...over }) as CorporateAction

describe('corporate action labels (§14: old → new rates, never divided)', () => {
  it('names splits new-for-old', () => {
    expect(splitRatio('1', '2')).toBe('2-for-1')
    expect(splitRatio('8.000000', '1.000000')).toBe('1-for-8')
    expect(splitRatio('2', '3')).toBe('3-for-2')
    expect(caSummary(ca({ oldRate: '1', newRate: '2' }))).toBe('2-for-1 split')
    expect(caSummary(ca({ type: 'REVERSE_SPLIT', oldRate: '8', newRate: '1' }))).toBe('1-for-8 reverse split')
  })

  it('summarises dividends, renames and mergers', () => {
    expect(caSummary(ca({ type: 'CASH_DIVIDEND', cashRate: '0.5100' }))).toBe('$0.51 per share')
    expect(caSummary(ca({ type: 'CASH_DIVIDEND', cashRate: '0.0125' }))).toBe('$0.0125 per share')
    expect(caSummary(ca({ type: 'NAME_CHANGE', symbol: 'SIMX', newSymbol: 'SIMA' }))).toBe('SIMX → SIMA')
    expect(caSummary(ca({ type: 'CASH_MERGER', cashRate: '1.25' }))).toBe('Cash merger at $1.25 per share')
  })

  it('validates resolve rates and tells forward from reverse', () => {
    expect(validRate('2')).toBe(true)
    expect(validRate('1.5')).toBe(true)
    expect(validRate('0')).toBe(false)
    expect(validRate('-1')).toBe(false)
    expect(validRate('abc')).toBe(false)
    expect(splitKind('1', '2')).toBe('FORWARD_SPLIT')
    expect(splitKind('3', '1')).toBe('REVERSE_SPLIT')
    expect(splitKind('2', '2.0')).toBeNull()
    expect(splitEffect('SOFI', '1', '2')).toMatch(/^Every 1 SOFI share becomes 2 \(2-for-1 split\)/)
    expect(splitEffect('XYZ', '8', '1')).toMatch(/^Every 8 XYZ shares become 1 \(1-for-8 reverse split\)/)
  })
})
