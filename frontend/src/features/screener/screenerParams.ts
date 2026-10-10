/**
 * Screener search params (§22.10) ↔ `GET /market/screener` query (§15.10), pure. The URL keeps short, readable
 * filter values ("cap=LARGE", "chg=UP2"); this module turns them into the API's numbers (fractions for changes,
 * dollars for market caps).
 */
import type { ScreenOrder, ScreenParams, ScreenPreset, ScreenSort, Sector } from '@/lib/api/hooks/marketExtras'

export const PRESETS: readonly { value: ScreenPreset; label: string; hint: string }[] = [
  { value: 'TOP_GAINERS', label: 'Top gainers', hint: 'Stocks up the most today' },
  { value: 'TOP_LOSERS', label: 'Top losers', hint: 'Stocks down the most today' },
  { value: 'MOST_ACTIVE', label: 'Most active', hint: 'The highest volume today' },
  { value: 'UNUSUAL_VOLUME', label: 'Unusual volume', hint: 'At least 2× their 20-day average volume' },
  { value: 'LARGE_CAPS_UNDER_50', label: 'Large caps under $50', hint: 'Market cap ≥ $10B, price ≤ $50' },
  { value: 'MEGA_CAPS', label: 'Mega caps', hint: 'Market cap ≥ $200B' },
  { value: 'ETFS', label: 'ETFs', hint: 'Exchange-traded funds by volume' },
]

export const SECTORS: readonly { value: Sector; label: string }[] = [
  { value: 'COMMUNICATION_SERVICES', label: 'Communication Services' },
  { value: 'CONSUMER_DISCRETIONARY', label: 'Consumer Discretionary' },
  { value: 'CONSUMER_STAPLES', label: 'Consumer Staples' },
  { value: 'ENERGY', label: 'Energy' },
  { value: 'FINANCIALS', label: 'Financials' },
  { value: 'HEALTH_CARE', label: 'Health Care' },
  { value: 'INDUSTRIALS', label: 'Industrials' },
  { value: 'INFORMATION_TECHNOLOGY', label: 'Information Technology' },
  { value: 'MATERIALS', label: 'Materials' },
  { value: 'REAL_ESTATE', label: 'Real Estate' },
  { value: 'UTILITIES', label: 'Utilities' },
]

export const SECTOR_LABEL: Record<string, string> = Object.fromEntries(SECTORS.map((s) => [s.value, s.label]))

/** Market-cap buckets (dollars, inclusive lower bound). */
export const CAPS = {
  MEGA: { label: 'Mega (≥ $200B)', min: '200000000000', max: undefined },
  LARGE: { label: 'Large ($10B–$200B)', min: '10000000000', max: '200000000000' },
  MID: { label: 'Mid ($2B–$10B)', min: '2000000000', max: '10000000000' },
  SMALL: { label: 'Small ($300M–$2B)', min: '300000000', max: '2000000000' },
  MICRO: { label: 'Micro (< $300M)', min: undefined, max: '300000000' },
} as const
export type CapBucket = keyof typeof CAPS

/** Change filters (fractions). */
export const CHANGES = {
  UP: { label: 'Up today', min: '0.000001', max: undefined },
  DOWN: { label: 'Down today', min: undefined, max: '-0.000001' },
  UP2: { label: 'Up > 2%', min: '0.02', max: undefined },
  DOWN2: { label: 'Down > 2%', min: undefined, max: '-0.02' },
  UP5: { label: 'Up > 5%', min: '0.05', max: undefined },
  DOWN5: { label: 'Down > 5%', min: undefined, max: '-0.05' },
} as const
export type ChangeBucket = keyof typeof CHANGES

export const REL_VOLUMES = ['1.5', '2', '3'] as const
export type TypeFilter = 'STOCKS' | 'ETF'

/** The screener's URL search params. */
export interface ScreenerSearch {
  preset?: ScreenPreset
  minPrice?: string
  maxPrice?: string
  cap?: CapBucket
  chg?: ChangeBucket
  rvol?: (typeof REL_VOLUMES)[number]
  sectors?: string
  type?: TypeFilter
  sort?: ScreenSort
  order?: ScreenOrder
}

const SORTS: readonly ScreenSort[] = ['SYMBOL', 'PRICE', 'CHANGE_PCT', 'VOLUME', 'AVG_VOLUME', 'REL_VOLUME', 'MARKET_CAP']
const PRICE = /^\d{1,7}(\.\d{1,4})?$/
const price = (v: unknown): string | undefined => ((typeof v === 'string' || typeof v === 'number') && PRICE.test(String(v)) ? String(v) : undefined)

const pick = <T extends string>(v: unknown, allowed: readonly T[]): T | undefined => {
  const s = typeof v === 'string' ? v.toUpperCase() : String(v ?? '').toUpperCase()
  return allowed.find((a) => a.toUpperCase() === s)
}

/** Validates raw search params (unknown values are dropped, never an error). */
export function parseScreenerSearch(raw: Record<string, unknown>): ScreenerSearch {
  const sectors = typeof raw.sectors === 'string'
    ? raw.sectors.split(',').map((s) => pick(s.trim(), SECTORS.map((x) => x.value))).filter(Boolean).join(',')
    : ''
  return {
    preset: pick(raw.preset, PRESETS.map((p) => p.value)),
    minPrice: price(raw.minPrice),
    maxPrice: price(raw.maxPrice),
    cap: pick(raw.cap, Object.keys(CAPS) as CapBucket[]),
    chg: pick(raw.chg, Object.keys(CHANGES) as ChangeBucket[]),
    rvol: pick(String(raw.rvol ?? ''), REL_VOLUMES),
    sectors: sectors || undefined,
    type: pick(raw.type, ['STOCKS', 'ETF'] as const),
    sort: pick(raw.sort, SORTS),
    order: pick(raw.order, ['ASC', 'DESC'] as const),
  }
}

/** The API query of a screen (a filter left unset keeps the preset's value on the server). */
export function toScreenParams(s: ScreenerSearch): ScreenParams {
  const cap = s.cap ? CAPS[s.cap] : undefined
  const chg = s.chg ? CHANGES[s.chg] : undefined
  return {
    preset: s.preset,
    minPrice: s.minPrice,
    maxPrice: s.maxPrice,
    minMarketCap: cap?.min,
    maxMarketCap: cap?.max,
    minChangePct: chg?.min,
    maxChangePct: chg?.max,
    minRelVolume: s.rvol,
    sectors: s.sectors ? (s.sectors.split(',') as Sector[]) : undefined,
    types: s.type === 'ETF' ? ['ETF'] : s.type === 'STOCKS' ? ['COMMON_STOCK', 'ADR'] : undefined,
    sort: s.sort,
    order: s.order,
  }
}

/** Header click: a new column sorts descending (symbol ascending); the same column flips; a third click clears. */
export function nextSort(current: { sort?: ScreenSort; order?: ScreenOrder }, column: ScreenSort, effective: { sort: ScreenSort; order: ScreenOrder }): { sort?: ScreenSort; order?: ScreenOrder } {
  const first: ScreenOrder = column === 'SYMBOL' ? 'ASC' : 'DESC'
  if (effective.sort !== column) return { sort: column, order: first }
  if (current.sort === column && effective.order !== first) return { sort: undefined, order: undefined }
  return { sort: column, order: effective.order === 'ASC' ? 'DESC' : 'ASC' }
}

/** How many filters (beyond the preset and the sort) are set. */
export function activeFilterCount(s: ScreenerSearch): number {
  return [s.minPrice, s.maxPrice, s.cap, s.chg, s.rvol, s.sectors, s.type].filter(Boolean).length
}
