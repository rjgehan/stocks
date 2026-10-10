/**
 * Friendly aliases over the API contract (§18.3 names; springdoc strips the `Dto` suffix, §18): the generated
 * OpenAPI schema where the backend already publishes a type, the hand-written planned shape otherwise
 * (`contract.ts`). These keep working when `npm run gen:api` regenerates schema.d.ts, as long as the names match.
 */
import type { Schemas as S } from './contract'

export type Me = S['Me']
export type Preferences = S['Preferences']
export type Clock = S['Clock']
export type Quote = S['Quote']
export type ExtendedLine = S['ExtendedLine']
export type Asset = S['Asset']
export type AssetSummary = S['AssetSummary']
export type Bar = S['Bar']
export type Bars = S['Bars']
export type Movers = S['Movers']
export type Account = S['Account']
export type Position = S['Position']
export type Order = S['Order']
export type Execution = S['Execution']
export type Estimate = S['Estimate']
export type Fees = S['Fees']
export type Warning = S['Warning']
export type PlaceOrderRequest = S['PlaceOrderRequest']
export type PlaceOrderResponse = S['PlaceOrderResponse']
export type Preview = S['Preview']
export type Watchlist = S['Watchlist']
export type Leaderboard = S['Leaderboard']
export type LeaderboardRow = S['LeaderboardRow']
export type PlayerSummary = S['PlayerSummary']

export type DataQuality = Quote['quality']
export type SessionType = Clock['session']
export type Side = Order['side']
export type OrderType = Order['type']
export type OrderStatus = Order['status']
export type TimeInForce = Order['timeInForce']
export type ChartRange = '1D' | '5D' | '1M' | '3M' | '6M' | 'YTD' | '1Y' | '5Y' | 'ALL'
export type LeaderboardPeriod = Leaderboard['period']
export type LeaderboardMetric = Leaderboard['metric']
export type KindFilter = Leaderboard['kind']

/** `/topic/tick.{SYM}` payload (§19). */
export interface Tick {
  symbol: string
  last: string | null
  change: string | null
  changePct: string | null
  extended: { price: string; changePct: string } | null
  asOf: string | null
  quality: DataQuality
}

/** `/topic/market.session` payload (§19). */
export interface SessionEvent {
  session: SessionType
  tradingDate: string | null
  endsAt: string | null
  nextSession: SessionType
  nextSessionAt: string
  earlyClose: boolean
}

/** `/user/queue/orders` payload (§19). */
export interface OrderUpdateEvent {
  event: S['OrderEvent']['type']
  order: Order
  execution: Execution | null
}

export const OPEN_STATUSES: readonly OrderStatus[] = ['PENDING', 'WORKING', 'PARTIALLY_FILLED']
