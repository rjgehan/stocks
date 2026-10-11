/**
 * The Advanced (group order) ticket's state and rules (§22.5, §11.9), kept pure so they are unit-testable:
 *  - Bracket: a buy entry (Market or Limit) + a take profit above it + a stop loss below it, at least 0.1% apart;
 *  - OTOCO: the same, with Stop / Stop Limit entries and no distance rule; OTO: an entry + one "then" order;
 *  - OCO: a limit and a stop on one side (Sell to protect shares you hold, or Buy);
 *  - whole shares and one time in force for every leg (a Market entry is Day);
 *  - the group's clientOrderId (`web-<uuid>`) is created when the ticket opens and reused on retry.
 */
import type { OrderGroupRule, OrderGroupType, PlaceOrderGroupRequest } from '@/lib/api/groupTypes'
import type { OrderType, Side, TimeInForce } from '@/lib/api/types'
import { type Dec, compare, div, isDecimal, mul, parseUserDecimal, roundToTick, setScale, sign, sub } from '@/lib/format/decimal'
import { newClientOrderId } from '../ticketState'

export type EntryType = 'MARKET' | 'LIMIT' | 'STOP' | 'STOP_LIMIT'
export type SecondaryType = 'LIMIT' | 'STOP' | 'STOP_LIMIT'

export interface GroupTicketState {
  groupType: OrderGroupType
  /** OCO only: both legs' side. */
  ocoSide: Side
  entryType: EntryType
  entryLimit: string
  entryStop: string
  tpLimit: string
  slStop: string
  /** A stop-limit stop loss when on (its limit in `slLimit`). */
  slUseLimit: boolean
  slLimit: string
  secondaryType: SecondaryType
  secondaryLimit: string
  secondaryStop: string
  qty: string
  tif: TimeInForce
  note: string
  shareNote: boolean
  doNotReduce: boolean
  gfvAck: boolean
  clientOrderId: string
}

export type GroupField =
  | 'entryLimit'
  | 'entryStop'
  | 'tpLimit'
  | 'slStop'
  | 'slLimit'
  | 'secondaryLimit'
  | 'secondaryStop'
  | 'qty'
  | 'note'

export type GroupTicketAction =
  | { type: 'groupType'; groupType: OrderGroupType }
  | { type: 'ocoSide'; side: Side }
  | { type: 'entryType'; entryType: EntryType }
  | { type: 'secondaryType'; secondaryType: SecondaryType }
  | { type: 'field'; field: GroupField; value: string }
  | { type: 'slUseLimit'; value: boolean }
  | { type: 'tif'; tif: TimeInForce }
  | { type: 'shareNote'; value: boolean }
  | { type: 'doNotReduce'; value: boolean }
  | { type: 'gfvAck'; value: boolean }
  | { type: 'submitted'; clientOrderId: string }

export const GROUP_TYPES: readonly OrderGroupType[] = ['BRACKET', 'OCO', 'OTO', 'OTOCO']

const DEFAULT_ENTRY_TYPES: Record<OrderGroupType, EntryType[]> = {
  BRACKET: ['MARKET', 'LIMIT'],
  OTOCO: ['MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT'],
  OTO: ['MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT'],
  OCO: [],
}

/** The group types `GET /rules` publishes (`orders.groups`), in the ticket's order; none before Phase 2. */
export function availableGroupTypes(groups: readonly OrderGroupRule[] | null | undefined): OrderGroupType[] {
  const listed = new Set((groups ?? []).map((g) => g.type).filter((t): t is OrderGroupType => t != null))
  return GROUP_TYPES.filter((t) => listed.has(t))
}

/** The entry types a group type accepts (the rulebook's, else the §11.9 table). */
export function entryTypesFor(groupType: OrderGroupType, groups?: readonly OrderGroupRule[] | null): EntryType[] {
  const row = groups?.find((g) => g.type === groupType)
  const listed = (row?.entryTypes ?? []).filter((t): t is EntryType => t === 'MARKET' || t === 'LIMIT' || t === 'STOP' || t === 'STOP_LIMIT')
  return listed.length ? listed : DEFAULT_ENTRY_TYPES[groupType]
}

export function initialGroupTicket(options: { groupType?: OrderGroupType; clientOrderId?: string } = {}): GroupTicketState {
  return {
    groupType: options.groupType ?? 'BRACKET',
    ocoSide: 'SELL',
    entryType: 'LIMIT',
    entryLimit: '',
    entryStop: '',
    tpLimit: '',
    slStop: '',
    slUseLimit: false,
    slLimit: '',
    secondaryType: 'LIMIT',
    secondaryLimit: '',
    secondaryStop: '',
    qty: '',
    tif: 'DAY',
    note: '',
    shareNote: false,
    doNotReduce: false,
    gfvAck: false,
    clientOrderId: options.clientOrderId ?? newClientOrderId(),
  }
}

export function groupTicketReducer(s: GroupTicketState, a: GroupTicketAction): GroupTicketState {
  switch (a.type) {
    case 'groupType': {
      const next: GroupTicketState = { ...s, groupType: a.groupType, gfvAck: false }
      if (!DEFAULT_ENTRY_TYPES[a.groupType].includes(s.entryType) && a.groupType !== 'OCO') next.entryType = 'LIMIT'
      return next
    }
    case 'ocoSide':
      return { ...s, ocoSide: a.side, gfvAck: false }
    case 'entryType':
      return { ...s, entryType: a.entryType }
    case 'secondaryType':
      return { ...s, secondaryType: a.secondaryType }
    case 'field':
      return { ...s, [a.field]: a.value }
    case 'slUseLimit':
      return { ...s, slUseLimit: a.value }
    case 'tif':
      return { ...s, tif: a.tif }
    case 'shareNote':
      return { ...s, shareNote: a.value }
    case 'doNotReduce':
      return { ...s, doNotReduce: a.value }
    case 'gfvAck':
      return { ...s, gfvAck: a.value }
    case 'submitted':
      // a new group gets a new idempotency key; the setup and prices stay, the quantity and note are cleared
      return { ...s, qty: '', note: '', shareNote: false, gfvAck: false, clientOrderId: a.clientOrderId }
  }
}

/** Which legs a type has (§11.9 table). */
export const hasEntry = (t: OrderGroupType): boolean => t !== 'OCO'
export const hasPair = (t: OrderGroupType): boolean => t !== 'OTO'
export const hasSecondary = (t: OrderGroupType): boolean => t === 'OTO'

const usesLimit = (t: OrderType): boolean => t === 'LIMIT' || t === 'STOP_LIMIT'
const usesStop = (t: OrderType): boolean => t === 'STOP' || t === 'STOP_LIMIT'
export const entryUsesLimit = (s: Pick<GroupTicketState, 'entryType'>): boolean => usesLimit(s.entryType)
export const entryUsesStop = (s: Pick<GroupTicketState, 'entryType'>): boolean => usesStop(s.entryType)
export const secondaryUsesLimit = (s: Pick<GroupTicketState, 'secondaryType'>): boolean => usesLimit(s.secondaryType)
export const secondaryUsesStop = (s: Pick<GroupTicketState, 'secondaryType'>): boolean => usesStop(s.secondaryType)

/** A whole number of shares ≥ 1, or null. */
export function wholeShares(input: string): Dec | null {
  const q = parseUserDecimal(input)
  if (!q || !isDecimal(q) || sign(q) <= 0) return null
  return compare(setScale(q, 0, 'DOWN'), q) === 0 ? setScale(q, 0, 'DOWN') : null
}

function price(input: string): Dec | null {
  const p = parseUserDecimal(input)
  return p && isDecimal(p) && sign(p) > 0 ? p : null
}

/**
 * The `POST /order-groups` body, or null while a required input is empty (whole shares; every price the legs use).
 * Prices go out as typed (the server checks the tick and reports the leg's field).
 */
export function buildGroupRequest(s: GroupTicketState, symbol: string): PlaceOrderGroupRequest | null {
  const qty = wholeShares(s.qty)
  if (!qty) return null
  const t = s.groupType
  const entry = hasEntry(t)
    ? { type: s.entryType, limitPrice: entryUsesLimit(s) ? price(s.entryLimit) : null, stopPrice: entryUsesStop(s) ? price(s.entryStop) : null }
    : null
  if (entry && ((entryUsesLimit(s) && !entry.limitPrice) || (entryUsesStop(s) && !entry.stopPrice))) return null
  const takeProfit = hasPair(t) ? { limitPrice: price(s.tpLimit) } : null
  const stopLoss = hasPair(t) ? { stopPrice: price(s.slStop), limitPrice: s.slUseLimit ? price(s.slLimit) : null } : null
  if (takeProfit && !takeProfit.limitPrice) return null
  if (stopLoss && (!stopLoss.stopPrice || (s.slUseLimit && !stopLoss.limitPrice))) return null
  const secondary = hasSecondary(t)
    ? {
        type: s.secondaryType,
        limitPrice: secondaryUsesLimit(s) ? price(s.secondaryLimit) : null,
        stopPrice: secondaryUsesStop(s) ? price(s.secondaryStop) : null,
      }
    : null
  if (secondary && ((secondaryUsesLimit(s) && !secondary.limitPrice) || (secondaryUsesStop(s) && !secondary.stopPrice))) return null
  const note = s.note.trim()
  return {
    clientOrderId: s.clientOrderId,
    type: t,
    symbol,
    side: t === 'OCO' ? s.ocoSide : 'BUY',
    qty,
    timeInForce: s.tif,
    entry,
    takeProfit,
    stopLoss: stopLoss ? { ...stopLoss, type: s.slUseLimit ? 'STOP_LIMIT' : 'STOP' } : null,
    secondary,
    rationale: note || null,
    shareNote: note ? s.shareNote : false,
    doNotReduce: s.doNotReduce,
    acknowledge: s.gfvAck ? ['GFV_RISK'] : [],
  }
}

/**
 * The price the take profit and stop loss are measured from (§11.9 "entry price"): the entry's limit (Limit, Stop
 * Limit), its stop (Stop), or the ask for a Market entry. For an OCO, the last price.
 */
export function entryReference(s: GroupTicketState, quote: { ask?: Dec | null; last?: Dec | null } | null | undefined): Dec | null {
  if (!hasEntry(s.groupType)) return quote?.last ?? null
  if (s.entryType === 'LIMIT' || s.entryType === 'STOP_LIMIT') return price(s.entryLimit)
  if (s.entryType === 'STOP') return price(s.entryStop)
  return quote?.ask ?? quote?.last ?? null
}

/** "+5.0%" / "−3.0%": how far a leg's price is from the entry price (one decimal), or null. */
export function pctFrom(reference: Dec | null, value: string): string | null {
  const p = price(value)
  if (!reference || !p || !isDecimal(reference) || sign(reference) <= 0) return null
  const pct = setScale(mul(div(sub(p, reference), reference, 8, 'HALF_UP'), '100'), 1, 'HALF_UP')
  const signed = sign(pct)
  return `${signed > 0 ? '+' : signed < 0 ? '−' : ''}${pct.replace('-', '')}%`
}

/**
 * The bracket's reward-to-risk per share: `(take profit − entry) / (entry − stop loss)`, "1 : 2.0", or null when it
 * cannot be computed (missing prices, or a stop at or above the entry).
 */
export function rewardToRisk(reference: Dec | null, tp: string, sl: string): string | null {
  const t = price(tp)
  const l = price(sl)
  if (!reference || !t || !l) return null
  const reward = sub(t, reference)
  const risk = sub(reference, l)
  if (sign(risk) <= 0 || sign(reward) <= 0) return null
  return `1 : ${setScale(div(reward, risk, 4, 'HALF_UP'), 1, 'HALF_UP')}`
}

/** Starting prices when the ticket first sees a quote: take profit +5%, stop loss −3% around the entry (on tick). */
export function defaultPrices(groupType: OrderGroupType, side: Side, quote: { bid?: Dec | null; ask?: Dec | null; last?: Dec | null }): Partial<Record<GroupField, string>> {
  const last = quote.last ?? quote.ask ?? quote.bid ?? null
  if (!last || !isDecimal(last) || sign(last) <= 0) return {}
  const up = (pct: string): string => roundToTick(mul(last, pct), 'CEILING')
  const down = (pct: string): string => roundToTick(mul(last, pct), 'FLOOR')
  // ±4%, not 5%: rounded outward to the tick, 5% always landed just past the server's 5% FAT_FINGER warning, so an
  // untouched default ticket looked like a mistake (review finding)
  if (groupType === 'OCO') {
    // Sell: take profit above, stop below; Buy: a limit below (buy the dip), a stop above (buy the breakout)
    return side === 'SELL' ? { tpLimit: up('1.04'), slStop: down('0.96') } : { tpLimit: down('0.96'), slStop: up('1.04') }
  }
  const entry = roundToTick(quote.bid ?? last)
  return { entryLimit: entry, entryStop: up('1.01'), tpLimit: up('1.04'), slStop: down('0.97'), secondaryLimit: up('1.04'), secondaryStop: down('0.97') }
}

/**
 * Maps a server/preview error field (`takeProfit.limitPrice`, `entry.type`, `qty`, …) to the ticket input it belongs
 * to, or null for a general error.
 */
export function groupInputFor(field: string | null | undefined): GroupField | 'entryType' | 'secondaryType' | null {
  switch (field) {
    case 'qty':
      return 'qty'
    case 'entry.limitPrice':
      return 'entryLimit'
    case 'entry.stopPrice':
      return 'entryStop'
    case 'entry.type':
    case 'entry':
      return 'entryType'
    case 'takeProfit.limitPrice':
    case 'takeProfit':
      return 'tpLimit'
    case 'stopLoss.stopPrice':
    case 'stopLoss':
      return 'slStop'
    case 'stopLoss.limitPrice':
      return 'slLimit'
    case 'secondary.limitPrice':
      return 'secondaryLimit'
    case 'secondary.stopPrice':
    case 'secondary':
      return 'secondaryStop'
    case 'secondary.type':
      return 'secondaryType'
    default:
      return null
  }
}

/** The submit button's words: "Place Bracket", "Place OCO". */
export function submitLabel(groupType: OrderGroupType, symbol: string): string {
  return `Place ${groupType === 'BRACKET' ? 'Bracket' : groupType} · ${symbol}`
}
