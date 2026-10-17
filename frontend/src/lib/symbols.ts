/**
 * Symbol spellings. Crypto pairs are canonically `BTC/USD` (what the server returns in JSON), but a URL path
 * segment or a STOMP topic cannot carry `/` (the firewall refuses `%2F`), so those use the dash spelling
 * `BTC-USD`; the server maps it back. Stock symbols are the same in both spellings.
 */

/** The crypto pairs the league trades (24/7). */
export const CRYPTO_PAIRS: readonly string[] = [
  'BTC/USD',
  'ETH/USD',
  'SOL/USD',
  'XRP/USD',
  'DOGE/USD',
  'LTC/USD',
  'BCH/USD',
  'LINK/USD',
  'AVAX/USD',
  'DOT/USD',
  'UNI/USD',
  'AAVE/USD',
]

const CANONICAL = /^[A-Z0-9]{2,10}\/USD$/
const DASHED = /^[A-Z0-9]{2,10}-USD$/

/** True for a crypto pair in either spelling (`BTC/USD`, `BTC-USD`, any case). */
export function isCrypto(symbol: string | null | undefined): boolean {
  if (!symbol) return false
  const s = symbol.toUpperCase()
  return CANONICAL.test(s) || DASHED.test(s)
}

/** The URL-path / STOMP-topic spelling: `BTC/USD` → `BTC-USD`; stocks unchanged. */
export function toPath(symbol: string): string {
  return CANONICAL.test(symbol.toUpperCase()) ? symbol.replace('/', '-') : symbol
}

/** Back from a URL path segment to the canonical upper-case symbol: `btc-usd` → `BTC/USD`; stocks just upper-cased. */
export function fromPath(segment: string): string {
  const s = segment.toUpperCase()
  return DASHED.test(s) ? s.replace('-', '/') : s
}

/** The coin a pair trades: `BTC/USD` → `BTC`; a stock symbol is returned as is. */
export function baseAsset(symbol: string): string {
  return isCrypto(symbol) ? symbol.toUpperCase().slice(0, -4) : symbol
}

/** An upper-case stock ticker (`BRK.B`) or crypto pair (`BTC/USD`, `BTC-USD`). */
export function isSymbolLike(symbol: string): boolean {
  return /^[A-Z0-9.]{1,10}$/.test(symbol) || CANONICAL.test(symbol) || DASHED.test(symbol)
}
