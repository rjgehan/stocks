/**
 * Friendly aliases over the API contract (§18.3 names; springdoc strips the `Dto` suffix, §18): the generated
 * OpenAPI schema where the backend already publishes a type, the hand-written planned shape otherwise
 * (`contract.ts`). These keep working when `npm run gen:api` regenerates schema.d.ts, as long as the names match.
 *
 * Response DTOs follow §18 rule 2: every non-primitive member is `T | null`. Screens handle null everywhere
 * (formatters take `Maybe<…>`); the enum aliases below strip the null so they can key lookup tables.
 */
import type { Schemas as S } from './contract'

type NN<T> = Exclude<T, null | undefined>

// identity
export type Me = S['Me']
export type Preferences = S['Preferences']
export type ApiKeyInfo = S['ApiKeyInfo']
export type ApiKeyCreated = S['ApiKeyCreated']
export type PlayerSummary = S['PlayerSummary']
export type Notification = S['Notification']
// market
export type Clock = S['Clock']
export type Quote = S['Quote']
export type ExtendedLine = S['ExtendedLine']
export type Asset = S['Asset']
export type AssetSummary = S['AssetSummary']
export type Bar = S['Bar']
export type Bars = S['Bars']
export type Movers = S['Movers']
export type MoverItem = S['MoverItem']
export type NewsItem = S['NewsItem']
// account and orders
export type Account = S['Account']
export type Position = S['Position']
export type Activity = S['Activity']
export type Order = S['Order']
export type OrderEvent = S['OrderEvent']
export type Execution = S['Execution']
export type Estimate = S['Estimate']
export type Fees = S['Fees']
export type Warning = S['Warning']
export type PlaceOrderRequest = S['PlaceOrderRequest']
export type PlaceOrderResponse = S['PlaceOrderResponse']
export type Preview = S['Preview']
export type PreviewError = S['PreviewError']
export type Run = S['Run']
export type PortfolioHistory = S['PortfolioHistory']
// league and watchlists
export type Watchlist = S['Watchlist']
export type Leaderboard = S['Leaderboard']
export type LeaderboardRow = S['LeaderboardRow']
export type PlayerProfile = S['PlayerProfile']
export type PublicTrade = S['PublicTrade']
export type PublicHistory = S['PublicHistory']
export type FeedItem = S['FeedItem']
// admin
export type AdminPlayer = S['AdminPlayer']
export type AdminPlayerDetail = S['AdminPlayerDetail']
export type AdminPlayerLiveView = S['AdminPlayerLiveView']
export type CreatePlayerRequest = S['CreatePlayerRequest']
export type CreatePlayerResponse = S['CreatePlayerResponse']
export type Settings = S['Settings']
export type UpdateSettingsRequest = S['UpdateSettingsRequest']
export type AuditRow = S['AuditRow']
export type SystemStatus = S['SystemStatus']
export type CalendarOverride = S['CalendarOverride']
export type CreateCalendarOverrideRequest = S['CreateCalendarOverrideRequest']
export type Invite = S['Invite']
export type InviteCreated = S['InviteCreated']
export type CashTransferRequest = S['CashTransferRequest']
export type CashTransferPreview = S['CashTransferPreview']
export type TransferBatchResult = S['TransferBatchResult']
export type TransferRow = S['TransferRow']

// enums (null stripped)
export type DataQuality = NN<Quote['quality']>
export type SessionType = NN<Clock['session']>
export type PlayerKind = NN<PlayerSummary['kind']>
export type Side = NN<Order['side']>
export type OrderType = NN<Order['type']>
export type OrderStatus = NN<Order['status']>
export type TimeInForce = NN<Order['timeInForce']>
export type WaitReason = NN<Order['waitReason']>
export type OrderEventType = NN<OrderEvent['type']>
export type ActivityType = NN<Activity['type']>
export type TransferType = NN<TransferRow['type']>
export type ChartRange = '1D' | '5D' | '1M' | '3M' | '6M' | 'YTD' | '1Y' | '5Y' | 'ALL'
export type HistoryRange = '1D' | '1W' | '1M' | '3M' | 'YTD' | '1Y' | 'ALL'
export type Timeframe = '1Min' | '5Min' | '15Min' | '30Min' | '1Hour' | '1Day' | '1Week' | '1Month'
export type LeaderboardPeriod = NN<Leaderboard['period']>
export type LeaderboardMetric = NN<Leaderboard['metric']>
export type KindFilter = NN<Leaderboard['kind']>
export type MoverList = 'GAINERS' | 'LOSERS' | 'ACTIVES'

/** `/topic/tick.{SYM}` payload (§19, `TickDto`). */
export interface Tick {
  symbol: string | null
  last: string | null
  change: string | null
  changePct: string | null
  extended: { price: string | null; changePct: string | null } | null
  asOf: string | null
  quality: DataQuality | null
}

/** `/topic/market.session` payload (§19, `MarketSessionDto`). */
export interface SessionEvent {
  session: SessionType | null
  tradingDate: string | null
  endsAt: string | null
  nextSession: SessionType | null
  nextSessionAt: string | null
  earlyClose: boolean
}

/** `/user/queue/orders` payload (§19, `OrderUpdateDto`). */
export interface OrderUpdateEvent {
  event: OrderEventType | null
  order: Order | null
  execution: Execution | null
}

/**
 * `Activity.detail` (`ActivityDetailDto`, sealed with `type`). Read through this loose view: the generated
 * shape of a polymorphic schema is not guaranteed to be a discriminated union.
 */
export interface ActivityDetailView {
  type?: string | null
  admin?: string | null
  reason?: string | null
  side?: string | null
  runNo?: number | null
  memo?: string | null
  oldSymbol?: string | null
  newSymbol?: string | null
  from?: string | null
  to?: string | null
}

/**
 * `CashTransferRequest` as the UI builds it (§21.1). The wire body is the same JSON; the polymorphic `target`
 * is typed locally so a regenerated schema with a different union encoding never breaks the form.
 */
export interface CashTransferBody {
  target: { scope: 'SINGLE'; handle: string } | { scope: 'ALL'; kinds?: PlayerKind[] }
  type: 'DEPOSIT' | 'WITHDRAWAL' | 'ADJUSTMENT'
  amount: string
  treatAs?: 'CONTRIBUTION' | 'PNL_CORRECTION'
  memo: string
  allOrNothing?: boolean
  acceptDegradedMarks?: boolean
}

export const OPEN_STATUSES: readonly OrderStatus[] = ['PENDING', 'WORKING', 'PARTIALLY_FILLED']

export const isOpenStatus = (s: OrderStatus | null | undefined): boolean => s != null && OPEN_STATUSES.includes(s)
