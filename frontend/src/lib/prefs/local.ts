import { fromPath } from '../symbols'

/** Small per-device UI preferences in localStorage (never money, never server state). */
export function readLocal(key: string, fallback: string): string {
  try {
    return localStorage.getItem(key) ?? fallback
  } catch {
    return fallback
  }
}

export function writeLocal(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    /* ignore */
  }
}

const LAST_SYMBOL = 'stocks.lastSymbol'

/** The last symbol viewed, canonical (`BTC/USD` for a pair): `/` redirects to it on desktop (§22.2, default SPY). */
export const getLastSymbol = (): string => fromPath(readLocal(LAST_SYMBOL, 'SPY'))
export const setLastSymbol = (symbol: string): void => writeLocal(LAST_SYMBOL, fromPath(symbol))
