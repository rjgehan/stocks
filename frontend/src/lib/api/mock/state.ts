/**
 * Mock-mode server state (§22.9) and its persistence. The state survives page reloads within a tab
 * (sessionStorage), so a reload, a deep link or a Playwright `page.goto` keeps your orders, deposits and lists.
 * Prices are not persisted: the fake market restarts its random walks on every load.
 */
import type { Dec } from '../../format/decimal'
import type {
  Activity,
  ApiKeyInfo,
  AuditRow,
  CalendarOverride,
  CorporateAction,
  Execution,
  FeedItem,
  Invite,
  Notification,
  Order,
  OrderEvent,
  Preferences,
  Settings,
  TransferRow,
  Watchlist,
} from '../types'
import type { MockReaction } from './stats'

export interface Lot {
  symbol: string
  qty: Dec
  costBasis: Dec
  openedAt: string
}

export interface MockIdentity {
  playerId: string
  handle: string
  displayName: string
  role: 'PLAYER' | 'ADMIN'
  accountId: string
  createdAt: string
}

export interface MockOtherPlayer {
  playerId: string
  handle: string
  displayName: string
  kind: 'HUMAN' | 'AI'
  aiModel: string | null
  role: 'PLAYER' | 'ADMIN'
  status: 'ACTIVE' | 'DISABLED' | 'FROZEN'
  ownerHandle: string | null
  accountId: string
  /** current unit value (§16.2) */
  u: Dec
  units: Dec
  cash: Dec
  nc: Dec
  fills: number
  symbols: number
  top: string | null
  apiTradingAllowed: boolean
  resets: number
  createdAt: string
  lastSeenAt: string | null
}

export interface MockKey extends ApiKeyInfo {
  handle: string
}

export interface MockInvite extends Invite {
  code: string
}

export interface MockState {
  v: 3
  me: MockIdentity
  apiTradingAllowed: boolean
  preferences: NonNullable<Preferences>
  /** the signed-in account */
  cash: Dec
  nc: Dec
  units: Dec
  openedAt: string
  /** U at the start of the mock session: the TODAY anchor */
  dayStartU: Dec
  lots: Record<string, Lot>
  orders: Order[]
  executions: Execution[]
  events: Record<string, OrderEvent[]>
  ledger: Activity[]
  watchlists: Watchlist[]
  others: MockOtherPlayer[]
  transfers: TransferRow[]
  invites: MockInvite[]
  keys: MockKey[]
  settings: Settings
  notifications: Notification[]
  audit: AuditRow[]
  /** Idempotency-Key → serialized response (§17.6) */
  idempotency: Record<string, { hash: string; body: unknown }>
  engineHalted: string | null
  /** §21.1 admin calendar overrides (optional: states saved by older mock builds lack it) */
  calendarOverrides?: CalendarOverride[]
  /** §14 corporate actions (admin list and resolve) */
  corporateActions: CorporateAction[]
  /** league feed items that are not derived from transfers or trades (joins, leaders, resets, live bot trades) */
  liveFeed: FeedItem[]
  /** Phase 2 (§16.11) feed reactions by item id (optional: states saved by older mock builds lack it) */
  reactions?: Record<string, MockReaction[]>
  /** epoch ms of the last password confirmation (`@RecentAuth`, 15 min) */
  reauthAt: number
  seq: number
}

const KEY = 'stocks.mock.state.v3'

export function loadState(): MockState | null {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as MockState
    return parsed.v === 3 ? parsed : null
  } catch {
    return null
  }
}

export function saveState(state: MockState): void {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(state))
  } catch {
    /* storage full or unavailable: the mock keeps working in memory */
  }
}

export function clearState(): void {
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    /* ignore */
  }
}
