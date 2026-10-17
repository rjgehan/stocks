/**
 * Mock-mode group orders (§11.9, §22.9): `/order-groups` preview, place, read and cancel on top of the mock's
 * single-order engine. Legs are ordinary mock orders carrying `group`; held children wait (status PENDING,
 * `AWAITING_PARENT`) until their entry fills, then activate; the first fill of an OCO leg cancels the other; an entry
 * that ends without a fill cancels its held children. Mock fills are always whole, so there is no partial-fill sync.
 */
import { type Dec, compare, decimals, isDecimal, mul, setScale, sign, sub } from '../../format/decimal'
import type { GroupPreview, OrderGroup, OrderGroupRef, OrderGroupRole, OrderGroupStatus, PlaceOrderGroupRequest } from '../groupTypes'
import type { Order, OrderEvent, PlaceOrderRequest, Preview } from '../types'
import { isCrypto } from '../../symbols'

/** The bits of the mock server the group engine drives (kept narrow so `server.ts` only gains a few lines). */
export interface MockGroupHost {
  orders(): Order[]
  newOrder(req: PlaceOrderRequest): Order
  preview(req: PlaceOrderRequest): Preview
  buyingPower(): Dec
  pushEvent(order: Order, type: NonNullable<OrderEvent['type']>, at?: string, detail?: Record<string, unknown> | null): void
  emitOrder(event: 'ACCEPTED' | 'WORKING' | 'CANCELLED', order: Order): void
  publishAccount(): void
  activateLater(orderId: string): void
  cancel(order: Order, reason: string): void
  newGroupId(): string
  /** The persisted group rows (part of the mock state, so they survive a reload). */
  store(): StoredGroup[]
}

export interface StoredGroup {
  groupId: string
  clientOrderId: string
  hash: string
  type: NonNullable<OrderGroup['type']>
  symbol: string
  qty: Dec
  timeInForce: 'DAY' | 'GTC'
  createdAt: string
  updatedAt: string
}

const ROLE_ORDER: OrderGroupRole[] = ['ENTRY', 'TAKE_PROFIT', 'STOP_LOSS', 'SECONDARY']
const LABEL: Record<OrderGroupRole, string> = { ENTRY: 'Entry', TAKE_PROFIT: 'Take profit', STOP_LOSS: 'Stop loss', SECONDARY: 'Then order' }
const FIELD: Record<OrderGroupRole, string> = { ENTRY: 'entry', TAKE_PROFIT: 'takeProfit', STOP_LOSS: 'stopLoss', SECONDARY: 'secondary' }

const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
function problem(status: number, code: string, detail: string, field: string | null = null): Response {
  return new Response(
    JSON.stringify({ type: 'about:blank', title: code, status, detail, code, hint: null, fields: field ? [{ field, code, message: detail }] : [], requestId: 'req_mock' }),
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}
const iso = (): string => new Date().toISOString()
const isOpen = (o: Order): boolean => o.status === 'PENDING' || o.status === 'WORKING' || o.status === 'PARTIALLY_FILLED'

interface LegPlan {
  role: OrderGroupRole
  req: PlaceOrderRequest
  held: boolean
}

export class MockGroups {
  constructor(private readonly host: MockGroupHost) {}

  private get groups(): StoredGroup[] {
    return this.host.store()
  }

  // ------------------------------------------------------------------ requests

  private legs(r: PlaceOrderGroupRequest): LegPlan[] | { field: string; message: string } {
    const type = r.type
    const qty = r.qty ?? ''
    if (!type) return { field: 'type', message: 'Choose OCO, OTO, OTOCO or BRACKET.' }
    if (isCrypto(r.symbol)) {
      // §29: a crypto pair's legs take up to 5 decimals
      if (!isDecimal(qty) || sign(qty) <= 0 || decimals(qty) > 5) return { field: 'qty', message: 'Crypto group orders take a quantity above 0 with at most 5 decimals.' }
    } else if (!isDecimal(qty) || sign(qty) <= 0 || compare(setScale(qty, 0, 'DOWN'), qty) !== 0) return { field: 'qty', message: 'Group orders take a whole number of shares.' }
    const base = { symbol: r.symbol ?? '', qty, timeInForce: r.timeInForce ?? 'DAY', rationale: r.rationale ?? null, doNotReduce: r.doNotReduce ?? false }
    const sideOf = (role: OrderGroupRole): 'BUY' | 'SELL' => (type === 'OCO' ? (r.side ?? 'SELL') : role === 'ENTRY' ? 'BUY' : 'SELL')
    const out: LegPlan[] = []
    if (type !== 'OCO') {
      const e = r.entry
      if (!e?.type) return { field: 'entry.type', message: 'The entry needs a type.' }
      out.push({ role: 'ENTRY', held: false, req: { ...base, clientOrderId: null, side: 'BUY', type: e.type, limitPrice: e.limitPrice ?? null, stopPrice: e.stopPrice ?? null, timeInForce: e.type === 'MARKET' ? 'DAY' : base.timeInForce } })
    }
    const held = type !== 'OCO'
    if (type !== 'OTO') {
      if (!r.takeProfit?.limitPrice) return { field: 'takeProfit.limitPrice', message: 'The take profit needs a limit price.' }
      if (!r.stopLoss?.stopPrice) return { field: 'stopLoss.stopPrice', message: 'The stop loss needs a stop price.' }
      out.push({ role: 'TAKE_PROFIT', held, req: { ...base, clientOrderId: null, side: sideOf('TAKE_PROFIT'), type: 'LIMIT', limitPrice: r.takeProfit.limitPrice } })
      const sl = r.stopLoss
      out.push({ role: 'STOP_LOSS', held, req: { ...base, clientOrderId: null, side: sideOf('STOP_LOSS'), type: sl.limitPrice ? 'STOP_LIMIT' : 'STOP', stopPrice: sl.stopPrice, limitPrice: sl.limitPrice ?? null } })
    } else {
      const s = r.secondary
      if (!s?.type) return { field: 'secondary.type', message: 'The then-order needs a type.' }
      out.push({ role: 'SECONDARY', held, req: { ...base, clientOrderId: null, side: 'SELL', type: s.type, limitPrice: s.limitPrice ?? null, stopPrice: s.stopPrice ?? null } })
    }
    if (type === 'BRACKET') {
      const entry = out[0]!.req
      const ref = entry.limitPrice ?? null
      const tp = r.takeProfit!.limitPrice!
      const slp = r.stopLoss!.stopPrice!
      if (ref && compare(tp, ref) <= 0) return { field: 'takeProfit.limitPrice', message: `The take profit (${tp}) must be above the entry price (${ref}).` }
      if (ref && compare(slp, ref) >= 0) return { field: 'stopLoss.stopPrice', message: `The stop loss (${slp}) must be below the entry price (${ref}).` }
      if (ref && compare(sub(tp, slp), setScale(mul(ref, '0.001'), 4, 'CEILING')) < 0)
        return { field: 'stopLoss.stopPrice', message: 'The take profit and the stop loss must be at least 0.1% of the entry price apart.' }
    }
    return out
  }

  preview(r: PlaceOrderGroupRequest): GroupPreview {
    const bp = this.host.buyingPower()
    const plan = this.legs(r)
    if (!Array.isArray(plan)) {
      return { valid: false, errors: [{ code: 'ORDER_GROUP_INVALID', field: plan.field, message: plan.message, hint: null }], warnings: [], legs: [], buyingPowerBefore: bp, buyingPowerAfter: null, sessionNote: null }
    }
    let after = bp
    const legs: NonNullable<GroupPreview['legs']> = []
    for (const p of plan) {
      if (p.held || (r.type === 'OCO' && p.role === 'STOP_LOSS')) {
        legs.push({ role: p.role, type: p.req.type ?? null, side: p.req.side ?? null, estimate: null })
        continue
      }
      const pv = this.host.preview(p.req)
      const first = pv.errors?.[0]
      if (!pv.valid && first) {
        return {
          valid: false,
          errors: [{ code: first.code, field: first.field ? `${FIELD[p.role]}.${first.field}` : null, message: `${LABEL[p.role]}: ${first.message ?? ''}`, hint: first.hint ?? null }],
          warnings: [],
          legs: [],
          buyingPowerBefore: bp,
          buyingPowerAfter: null,
          sessionNote: null,
        }
      }
      if (p.req.side === 'BUY' && pv.estimate?.hold) after = sub(after, pv.estimate.hold)
      legs.push({ role: p.role, type: p.req.type ?? null, side: p.req.side ?? null, estimate: pv.estimate ?? null })
    }
    return { valid: true, errors: [], warnings: [], legs, buyingPowerBefore: bp, buyingPowerAfter: after, sessionNote: null }
  }

  place(r: PlaceOrderGroupRequest): Response {
    if (!r.clientOrderId || !/^[A-Za-z0-9._:-]{1,64}$/.test(r.clientOrderId)) return problem(422, 'CLIENT_ORDER_ID_INVALID', 'Invalid clientOrderId', 'clientOrderId')
    const hash = JSON.stringify([r.type, r.symbol?.toUpperCase(), r.side, r.qty, r.timeInForce, r.entry, r.takeProfit, r.stopLoss, r.secondary])
    const existing = this.groups.find((g) => g.clientOrderId === r.clientOrderId)
    if (existing) {
      if (existing.hash !== hash) return problem(409, 'CLIENT_ORDER_ID_CONFLICT', 'This clientOrderId was used for a different group.', 'clientOrderId')
      return json({ group: this.dto(existing), estimate: null, buyingPowerBefore: this.host.buyingPower(), buyingPowerAfter: this.host.buyingPower(), warnings: [], replayed: true })
    }
    const pv = this.preview(r)
    if (!pv.valid) {
      const e = pv.errors![0]!
      return problem(422, e.code ?? 'ORDER_GROUP_INVALID', e.message ?? 'Invalid order group', e.field ?? null)
    }
    const plan = this.legs(r) as LegPlan[]
    const groupId = this.host.newGroupId()
    const now = iso()
    const g: StoredGroup = { groupId, clientOrderId: r.clientOrderId, hash, type: r.type!, symbol: (r.symbol ?? '').toUpperCase(), qty: r.qty!, timeInForce: r.timeInForce ?? 'DAY', createdAt: now, updatedAt: now }
    this.groups.push(g)
    let entryId: string | null = null
    for (const p of plan) {
      const order = this.host.newOrder({ ...p.req, clientOrderId: `${groupId}:${p.role}` })
      const ref: OrderGroupRef = { groupId, type: g.type, role: p.role, parentOrderId: p.role === 'ENTRY' || g.type === 'OCO' ? null : entryId, held: p.held }
      order.group = ref
      if (p.role === 'ENTRY') entryId = order.orderId
      const est = pv.legs?.find((l) => l.role === p.role)?.estimate
      const holdsHere = !p.held && !(g.type === 'OCO' && p.role === 'STOP_LOSS')
      order.reservedCash = holdsHere && order.side === 'BUY' ? (est?.hold ?? '0.00') : '0.00'
      order.reservedQty = holdsHere && order.side === 'SELL' ? g.qty : '0'
      if (p.held) order.waitReason = 'AWAITING_PARENT'
      this.host.orders().push(order)
      this.host.pushEvent(order, 'ACCEPTED', now, { group: groupId, role: p.role, held: p.held })
      this.host.emitOrder('ACCEPTED', order)
      if (!p.held) this.host.activateLater(order.orderId!)
    }
    this.host.publishAccount()
    return json({ group: this.dto(g), estimate: pv.legs?.[0]?.estimate ?? null, buyingPowerBefore: pv.buyingPowerBefore, buyingPowerAfter: pv.buyingPowerAfter, warnings: [], replayed: false }, 201)
  }

  get(groupId: string): Response {
    const g = this.groups.find((x) => x.groupId === groupId)
    return g ? json(this.dto(g)) : problem(404, 'NOT_FOUND', `No order group ${groupId}.`)
  }

  cancel(groupId: string): Response {
    const g = this.groups.find((x) => x.groupId === groupId)
    if (!g) return problem(404, 'NOT_FOUND', `No order group ${groupId}.`)
    const legs = this.legsOf(g).filter(isOpen).sort((a, b) => Number(a.group?.role === 'ENTRY') - Number(b.group?.role === 'ENTRY'))
    for (const o of legs) if (isOpen(o)) this.host.cancel(o, 'USER')
    g.updatedAt = iso()
    return json(this.dto(g))
  }

  // ------------------------------------------------------------------ engine hooks

  /** A held child does not activate until its entry fills. */
  isHeld(order: Order): boolean {
    return Boolean(order.group?.held)
  }

  /** After a fill: the entry releases its children; an OCO leg cancels its sibling. */
  afterFill(order: Order): void {
    const ref = order.group
    if (!ref?.groupId) return
    const g = this.groups.find((x) => x.groupId === ref.groupId)
    if (!g) return
    const legs = this.legsOf(g)
    if (ref.role === 'ENTRY') {
      for (const child of legs.filter((o) => o.group?.parentOrderId === order.orderId && isOpen(o) && o.group?.held)) {
        child.group = { ...child.group!, held: false }
        child.waitReason = 'LATENCY'
        child.reservedQty = child.side === 'SELL' && (child.group.role === 'TAKE_PROFIT' || child.group.role === 'SECONDARY') ? child.qty : '0'
        this.host.pushEvent(child, 'ACTIVATED', iso(), { entryOrderId: order.orderId, workingQty: child.qty })
        this.host.activateLater(child.orderId!)
      }
    } else if (ref.role === 'TAKE_PROFIT' || ref.role === 'STOP_LOSS') {
      for (const sib of legs.filter((o) => o.orderId !== order.orderId && isOpen(o) && (o.group?.role === 'TAKE_PROFIT' || o.group?.role === 'STOP_LOSS'))) {
        this.host.cancel(sib, 'OCO_FILLED')
      }
    }
    g.updatedAt = iso()
  }

  /** After an entry ends without a fill: its held children are cancelled. */
  afterEnd(order: Order): void {
    const ref = order.group
    if (!ref?.groupId || ref.role !== 'ENTRY') return
    const g = this.groups.find((x) => x.groupId === ref.groupId)
    if (!g) return
    for (const child of this.legsOf(g).filter((o) => o.group?.parentOrderId === order.orderId && isOpen(o))) this.host.cancel(child, 'PARENT_NOT_FILLED')
    g.updatedAt = iso()
  }

  // ------------------------------------------------------------------ DTOs

  private legsOf(g: StoredGroup): Order[] {
    return this.host.orders().filter((o) => o.group?.groupId === g.groupId)
  }

  private dto(g: StoredGroup): OrderGroup {
    const legs = this.legsOf(g)
      .filter((o) => o.status !== 'REPLACED')
      .sort((a, b) => ROLE_ORDER.indexOf(a.group!.role!) - ROLE_ORDER.indexOf(b.group!.role!))
    let status: OrderGroupStatus = 'CANCELLED'
    if (legs.some(isOpen)) status = 'ACTIVE'
    else if (legs.some((o) => o.filledQty && sign(o.filledQty) > 0)) status = 'COMPLETED'
    else if (legs[0]?.status === 'EXPIRED') status = 'EXPIRED'
    return {
      groupId: g.groupId,
      clientOrderId: g.clientOrderId,
      type: g.type,
      symbol: g.symbol,
      qty: g.qty,
      timeInForce: g.timeInForce,
      status,
      createdAt: g.createdAt,
      updatedAt: g.updatedAt,
      legs: legs.map((o) => ({ ...o, executions: null, events: null })),
    }
  }
}
