/**
 * Mock option chains (§30) for mock mode: deterministic expirations (the next 4 Fridays plus the next 3 monthly
 * third Fridays), strikes around the underlying's price and Black–Scholes prices off the fake market's random walk,
 * with a small spread. The feed is `simulated`; quotes are SIMULATED like every other mock quote.
 */
import { type OptionRight, formatOption } from '../../options'

const DAY_MS = 86_400_000
const YEAR_MS = 365 * DAY_MS
/** The risk-free rate the mock prices with. */
const RATE = 0.04
/** Strikes on each side of the money. */
const STRIKES_EACH_SIDE = 10

/** ET calendar date (YYYY-MM-DD) of an instant. */
const etDate = (ms: number): string => new Date(ms).toLocaleDateString('en-CA', { timeZone: 'America/New_York' })

/** 16:00 ET on a date (20:00Z in EDT, 21:00Z in EST): when a contract stops trading and settles. */
export function expiryMs(date: string): number {
  const edt = Date.parse(`${date}T20:00:00Z`)
  const hour = new Date(edt).toLocaleTimeString('en-US', { timeZone: 'America/New_York', hour12: false, hour: '2-digit' })
  return hour.startsWith('16') ? edt : edt + 3_600_000
}

const addDays = (date: string, n: number): string => new Date(Date.parse(`${date}T12:00:00Z`) + n * DAY_MS).toISOString().slice(0, 10)
const weekday = (date: string): number => new Date(`${date}T12:00:00Z`).getUTCDay()

/** The third Friday of a month (`YYYY-MM`). */
function thirdFriday(month: string): string {
  const first = `${month}-01`
  return addDays(first, ((5 - weekday(first) + 7) % 7) + 14)
}

/**
 * The listed expirations at `nowMs`, nearest first: the next 4 Fridays (today's, until its 16:00 ET close) and then
 * the next 3 monthly third Fridays after them.
 */
export function mockExpirations(nowMs: number): string[] {
  let day = etDate(nowMs)
  if (weekday(day) === 5 && nowMs >= expiryMs(day)) day = addDays(day, 1)
  const fridays: string[] = []
  let f = addDays(day, (5 - weekday(day) + 7) % 7)
  while (fridays.length < 4) {
    fridays.push(f)
    f = addDays(f, 7)
  }
  const last = fridays[fridays.length - 1]!
  const monthlies: string[] = []
  let y = Number(last.slice(0, 4))
  let m = Number(last.slice(5, 7))
  while (monthlies.length < 3) {
    const tf = thirdFriday(`${y}-${String(m).padStart(2, '0')}`)
    if (tf > last) monthlies.push(tf)
    m += 1
    if (m > 12) {
      m = 1
      y += 1
    }
  }
  return [...fridays, ...monthlies]
}

/** The strike spacing at an underlying price ($0.50 under $5 … $25 above $1,000). */
export function strikeStep(price: number): number {
  return price < 5 ? 0.5 : price < 25 ? 1 : price < 100 ? 2.5 : price < 250 ? 5 : price < 1000 ? 10 : 25
}

/** 21 strikes centred on the one nearest the underlying price, ascending (none at or below 0). */
export function mockStrikes(price: number): number[] {
  const step = strikeStep(price)
  const atm = Math.round(price / step) * step
  const out: number[] = []
  for (let i = -STRIKES_EACH_SIDE; i <= STRIKES_EACH_SIDE; i++) {
    const k = Math.round((atm + i * step) * 1000) / 1000
    if (k > 0) out.push(k)
  }
  return out
}

/** Standard normal CDF (Abramowitz–Stegun 26.2.17, |error| < 7.5e-8). */
function normCdf(x: number): number {
  const t = 1 / (1 + 0.2316419 * Math.abs(x))
  const poly = t * (0.31938153 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
  const tail = Math.exp((-x * x) / 2) / Math.sqrt(2 * Math.PI) * poly
  return x >= 0 ? 1 - tail : tail
}

/** The annualized volatility of a mock symbol: 25% for a typical large cap (`vol` 1). */
export const impliedVol = (vol: number): number => Math.min(1.5, Math.max(0.1, 0.25 * vol))

/**
 * Black–Scholes value and delta per share. At or after expiry (t ≤ 0) the value is the intrinsic value and the delta
 * 1 / 0 (−1 / 0 for a put).
 */
export function blackScholes(right: OptionRight, spot: number, strike: number, years: number, sigma: number): { price: number; delta: number } {
  const call = right === 'CALL'
  if (years <= 0 || sigma <= 0 || spot <= 0) {
    const intrinsic = Math.max(0, call ? spot - strike : strike - spot)
    return { price: intrinsic, delta: intrinsic > 0 ? (call ? 1 : -1) : 0 }
  }
  const sd = sigma * Math.sqrt(years)
  const d1 = (Math.log(spot / strike) + (RATE + (sigma * sigma) / 2) * years) / sd
  const d2 = d1 - sd
  const disc = Math.exp(-RATE * years)
  return call
    ? { price: spot * normCdf(d1) - strike * disc * normCdf(d2), delta: normCdf(d1) }
    : { price: strike * disc * normCdf(-d2) - spot * normCdf(-d1), delta: normCdf(d1) - 1 }
}

/** Years from `nowMs` to an expiration's close (0 once it has passed). */
export const yearsTo = (expiration: string, nowMs: number): number => Math.max(0, (expiryMs(expiration) - nowMs) / YEAR_MS)

/** A per-share value in whole cents, as ten-thousandths (the fake market's unit). */
const centsUnits = (dollars: number): number => Math.max(0, Math.round(dollars * 100)) * 100

/**
 * The bid, ask and mid of a contract in ten-thousandths: the model value ± 3% (at least a cent each side), never a
 * bid under $0.01, so the mock always has a two-sided quote.
 */
export function optionBook(value: number): { bid: number; ask: number; mid: number } {
  const half = Math.max(0.01, value * 0.03)
  const bid = Math.max(100, centsUnits(value - half))
  const ask = Math.max(bid + 100, centsUnits(value + half))
  return { bid, ask, mid: Math.round((bid + ask) / 200) * 100 }
}

/** The OCC symbols of one expiration's strikes, calls then puts. */
export function chainSymbols(underlying: string, expiration: string, price: number): { calls: string[]; puts: string[] } {
  const strikes = mockStrikes(price)
  const occ = (right: OptionRight) => strikes.map((k) => formatOption({ underlying, expiration, right, strike: k.toFixed(3) }))
  return { calls: occ('CALL'), puts: occ('PUT') }
}
