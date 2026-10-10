import { describe, expect, it } from 'vitest'
import { activeFilterCount, nextSort, parseScreenerSearch, toScreenParams } from './screenerParams'

describe('screener params', () => {
  it('keeps only valid search params', () => {
    expect(parseScreenerSearch({ preset: 'top_gainers', minPrice: '5', maxPrice: 'abc', cap: 'large', chg: 'up2', rvol: 2, sectors: 'energy,BOGUS,financials', type: 'etf', sort: 'market_cap', order: 'asc' })).toEqual({
      preset: 'TOP_GAINERS',
      minPrice: '5',
      maxPrice: undefined,
      cap: 'LARGE',
      chg: 'UP2',
      rvol: '2',
      sectors: 'ENERGY,FINANCIALS',
      type: 'ETF',
      sort: 'MARKET_CAP',
      order: 'ASC',
    })
    expect(parseScreenerSearch({ preset: 'nope', sort: 'x', sectors: 'x' })).toEqual({
      preset: undefined, minPrice: undefined, maxPrice: undefined, cap: undefined, chg: undefined, rvol: undefined, sectors: undefined, type: undefined, sort: undefined, order: undefined,
    })
  })

  it('maps buckets to API numbers (fractions, dollars)', () => {
    expect(toScreenParams({ cap: 'MID', chg: 'DOWN5', type: 'STOCKS', sectors: 'ENERGY', rvol: '1.5' })).toMatchObject({
      minMarketCap: '2000000000',
      maxMarketCap: '10000000000',
      minChangePct: undefined,
      maxChangePct: '-0.05',
      types: ['COMMON_STOCK', 'ADR'],
      sectors: ['ENERGY'],
      minRelVolume: '1.5',
    })
    expect(toScreenParams({ cap: 'MEGA' }).maxMarketCap).toBeUndefined()
    expect(activeFilterCount({ preset: 'ETFS', cap: 'MEGA', minPrice: '1', sort: 'PRICE' })).toBe(2)
  })

  it('cycles a header: desc → asc → back to the default', () => {
    const eff = { sort: 'MARKET_CAP' as const, order: 'DESC' as const }
    expect(nextSort({}, 'PRICE', eff)).toEqual({ sort: 'PRICE', order: 'DESC' })
    expect(nextSort({ sort: 'PRICE', order: 'DESC' }, 'PRICE', { sort: 'PRICE', order: 'DESC' })).toEqual({ sort: 'PRICE', order: 'ASC' })
    expect(nextSort({ sort: 'PRICE', order: 'ASC' }, 'PRICE', { sort: 'PRICE', order: 'ASC' })).toEqual({ sort: undefined, order: undefined })
    expect(nextSort({}, 'SYMBOL', eff)).toEqual({ sort: 'SYMBOL', order: 'ASC' })
    // the preset's own sort column flips first (it was not chosen explicitly)
    expect(nextSort({}, 'MARKET_CAP', eff)).toEqual({ sort: 'MARKET_CAP', order: 'ASC' })
  })
})
