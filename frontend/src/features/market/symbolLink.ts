/**
 * Where a symbol in a list links to: its quote page, or, for an option contract (§30), its underlying's quote page on
 * the Options tab with the contract selected (`/stock/AAPL?tab=OPTIONS&contract=AAPL261120C00250000`). There is no
 * quote page of its own for a contract.
 */
import { isOption, underlyingOf } from '@/lib/options'
import { toPath } from '@/lib/symbols'

export interface StockLink {
  params: { symbol: string }
  search: { tab?: 'OPTIONS'; contract?: string }
}

/** `<Link to="/stock/$symbol" {...stockLink(symbol)}>`. */
export function stockLink(symbol: string | null | undefined): StockLink {
  const s = symbol ?? ''
  return isOption(s) ? { params: { symbol: toPath(underlyingOf(s)) }, search: { tab: 'OPTIONS', contract: s } } : { params: { symbol: toPath(s) }, search: {} }
}
