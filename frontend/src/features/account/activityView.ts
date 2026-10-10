/**
 * How one `Activity` reads in the list (§22.4 Account · Activity, §18.2 activity merge): ledger rows (fills,
 * transfers, dividends, cash in lieu) and account events (SPLIT, NAME_CHANGE, GFV, RESET, RESTRICTION, ADMIN_VIEW),
 * each with a title, the facts that explain it, a status badge and the cash it moved. Pure, so it is unit-tested.
 */
import type { Activity, ActivityDetailView, ActivityType } from '@/lib/api/types'
import type { PillTone } from '@/components/ui/Pill'
import { compare, isDecimal, isZero, stripTrailingZeros } from '@/lib/format/decimal'
import { formatMoney } from '@/lib/format/money'
import { formatPrice, formatQty } from '@/lib/format/number'
import { formatDateEt, formatDateTimeEt } from '@/lib/format/time'

export const ACTIVITY_TYPE_LABEL: Record<ActivityType, string> = {
  FILL: 'Trade',
  INITIAL_FUNDING: 'Starting cash',
  DEPOSIT: 'Deposit',
  WITHDRAWAL: 'Withdrawal',
  ADJUSTMENT: 'Adjustment',
  DIVIDEND: 'Dividend',
  CASH_IN_LIEU: 'Cash in lieu',
  SPLIT: 'Stock split',
  NAME_CHANGE: 'Symbol change',
  GFV: 'Good-faith violation',
  RESET: 'Account reset',
  RESTRICTION: 'Restriction',
  ADMIN_VIEW: 'Admin viewed your live account',
}

const RESTRICTION_LABEL: Record<string, string> = {
  NONE: 'none',
  SETTLED_CASH_ONLY: 'settled cash only',
  LIQUIDATION_ONLY: 'sells only',
  FROZEN: 'frozen',
}

export type ActivityIcon = 'trade' | 'cash' | 'dividend' | 'split' | 'rename' | 'shield' | 'reset' | 'info'

export interface ActivityView {
  title: string
  /** Facts under the title ("1 → 2 shares · cost basis unchanged"). */
  lines: string[]
  badge: { label: string; tone: PillTone } | null
  /** Signed cash moved ("+$0.51"), or null for events that move none. */
  amount: string | null
  /** The amount is not cash yet (an accrued dividend) or no longer counts (a voided one). */
  amountState: 'posted' | 'pending' | 'void'
  icon: ActivityIcon
}

const dec = (v: string | null | undefined): string | null => (v && isDecimal(v) ? v : null)
const rate = (v: string | null | undefined): string => (v && isDecimal(v) ? stripTrailingZeros(v) : '?')
const signedMoney = (v: string | null): string | null => (v ? formatMoney(v, { signed: true }) : null)

/** "2-for-1 split", "1-for-2 reverse split" (new-for-old; §14 rates are old → new, never divided). */
export function splitTitle(d: ActivityDetailView): string {
  const reverse = d.oldRate && d.newRate && isDecimal(d.oldRate) && isDecimal(d.newRate) && compare(d.newRate, d.oldRate) < 0
  return `${rate(d.newRate)}-for-${rate(d.oldRate)} ${reverse ? 'reverse split' : 'split'}`
}

export function activityView(a: Activity): ActivityView {
  const d = (a.detail ?? {}) as ActivityDetailView
  const amount = dec(a.amount)
  const type = a.type
  const base: ActivityView = {
    title: type ? ACTIVITY_TYPE_LABEL[type] : 'Activity',
    lines: [],
    badge: null,
    amount: amount && !isZero(amount) ? signedMoney(amount) : null,
    amountState: 'posted',
    icon: 'info',
  }
  switch (type) {
    case 'FILL':
      return { ...base, title: `${a.side === 'SELL' ? 'Sold' : 'Bought'} ${formatQty(a.qty)} ${a.symbol ?? ''} @ ${formatPrice(a.price)}`, icon: 'trade' }
    case 'INITIAL_FUNDING':
    case 'DEPOSIT':
    case 'WITHDRAWAL':
    case 'ADJUSTMENT':
      return { ...base, icon: 'cash' }
    case 'DIVIDEND': {
      const status = (d.status ?? (d.type === 'DIVIDEND' ? null : 'PAID'))?.toUpperCase() ?? 'PAID'
      const qty = dec(d.qty) ?? dec(a.qty)
      const perShare = dec(d.cashRate) ?? dec(a.price)
      const lines = [
        qty && perShare ? `${formatQty(qty)} sh × ${formatMoney(perShare, { scale: Math.max(2, (stripTrailingZeros(perShare).split('.')[1] ?? '').length) })}` : null,
        d.exDate ? `ex-date ${formatDateEt(d.exDate)}` : null,
        status === 'ACCRUED' && (d.payableDate ?? a.settleDate) ? `pays ${formatDateEt(d.payableDate ?? a.settleDate)}` : null,
        status === 'PAID' && (d.payableDate ?? a.tradeDate) ? `paid ${formatDateEt(d.payableDate ?? a.tradeDate)}` : null,
      ].filter((x): x is string => Boolean(x))
      if (status === 'ACCRUED') {
        return {
          ...base,
          title: `Dividend · ${a.symbol ?? ''}`,
          lines: [...lines, 'Counts in your equity until it pays'],
          badge: { label: 'Accrued', tone: 'accent' },
          amountState: 'pending',
          icon: 'dividend',
        }
      }
      if (status === 'VOID') {
        return { ...base, title: `Dividend · ${a.symbol ?? ''}`, lines: [...lines, 'Voided by an account reset before it paid'], badge: { label: 'Void', tone: 'flat' }, amountState: 'void', icon: 'dividend' }
      }
      return { ...base, title: `Dividend · ${a.symbol ?? ''}`, lines, badge: { label: 'Paid', tone: 'up' }, icon: 'dividend' }
    }
    case 'CASH_IN_LIEU': {
      const qty = dec(a.qty)
      return {
        ...base,
        title: `Cash in lieu · ${a.symbol ?? ''}`,
        lines: [qty ? `${formatQty(qty)} sh fraction${a.price ? ` @ ${formatPrice(a.price)}` : ''}` : 'Fractional share paid in cash', a.settleDate ? `settles ${formatDateEt(a.settleDate)}` : null].filter((x): x is string => Boolean(x)),
        icon: 'cash',
      }
    }
    case 'SPLIT': {
      const cil = dec(d.cashInLieu)
      const realized = dec(d.realizedPnl)
      const lines = [
        d.qtyBefore && d.qtyAfter ? `${formatQty(d.qtyBefore)} → ${formatQty(d.qtyAfter)} shares` : null,
        d.costBasis ? `cost basis ${formatMoney(d.costBasis)}${cil && !isZero(cil) ? '' : ' (unchanged)'}` : null,
        cil && !isZero(cil) ? `cash in lieu ${formatMoney(cil)} for ${formatQty(d.dustQty)} sh${realized ? ` · realized ${formatMoney(realized, { signed: true })}` : ''}` : null,
        cil && isZero(cil) && d.dustQty && isDecimal(d.dustQty) && !isZero(d.dustQty) ? `${formatQty(d.dustQty)} sh dust forfeited (under one cent)` : null,
      ].filter((x): x is string => Boolean(x))
      return { ...base, title: `${a.symbol ?? ''} ${splitTitle(d)}`, lines, amount: null, icon: 'split' }
    }
    case 'NAME_CHANGE':
      return {
        ...base,
        title: d.oldSymbol && d.newSymbol ? `${d.oldSymbol} is now ${d.newSymbol}` : ACTIVITY_TYPE_LABEL.NAME_CHANGE,
        lines: [d.qty ? `${formatQty(d.qty)} shares moved to ${d.newSymbol ?? 'the new symbol'}` : null, 'Watchlists follow; past trades keep the old symbol'].filter((x): x is string => Boolean(x)),
        amount: null,
        icon: 'rename',
      }
    case 'GFV':
      return {
        ...base,
        lines: [
          d.unsettledAmount ? `sold before ${formatMoney(d.unsettledAmount)} of the purchase settled${d.fundingSettleDate ? ` (${formatDateEt(d.fundingSettleDate)})` : ''}` : null,
          d.activeCount != null ? `${d.activeCount} active in the last 12 months` : null,
        ].filter((x): x is string => Boolean(x)),
        badge: { label: 'GFV', tone: 'warn' },
        amount: null,
        icon: 'shield',
      }
    case 'RESET': {
      const opened = d.side === 'OPENED'
      return {
        ...base,
        title: opened ? `New run #${d.runNo ?? '?'} started` : `Run #${d.runNo ?? '?'} closed (account reset)`,
        lines: [opened && d.startingCash ? `starting cash ${formatMoney(d.startingCash)}` : null, !opened && d.finalEquity ? `final equity ${formatMoney(d.finalEquity)}` : null, d.memo ?? null].filter(
          (x): x is string => Boolean(x),
        ),
        amount: null,
        icon: 'reset',
      }
    }
    case 'RESTRICTION':
      return {
        ...base,
        title: `Restriction: ${RESTRICTION_LABEL[d.to ?? ''] ?? (d.to ?? '').toLowerCase()}`,
        lines: [d.from ? `was ${RESTRICTION_LABEL[d.from] ?? d.from.toLowerCase()}` : null, d.until ? `until ${formatDateTimeEt(d.until)}` : null, d.reason ?? null].filter((x): x is string => Boolean(x)),
        badge: d.to && d.to !== 'NONE' ? { label: 'Restricted', tone: 'warn' } : { label: 'Lifted', tone: 'up' },
        amount: null,
        icon: 'shield',
      }
    case 'ADMIN_VIEW':
      return {
        ...base,
        lines: [d.admin ? `by @${d.admin}` : null, 'They saw your live positions and open orders (break-glass, audited)'].filter((x): x is string => Boolean(x)),
        badge: { label: 'Audited', tone: 'warn' },
        amount: null,
        icon: 'shield',
      }
    default:
      return base
  }
}
