/**
 * Trade ticket (§22.5): desktop right column, tablet side sheet, phone bottom sheet.
 * Side · Type · Price (tick stepper + Bid/Mid/Ask/Last) · Shares | Amount ($) with 25%/50%/Max · TIF ·
 * Extended hours · live preview (debounced 250 ms) · warnings with the GFV acknowledgement · note with
 * "Share note publicly" · submit (disabled until the preview is valid) · review sheet · result toast.
 */
import { useEffect, useMemo, useReducer, useRef, useState } from 'react'
import { useAccount, useOrderPreview, usePlaceOrder, usePositions } from '@/lib/api/hooks/account'
import { useAsset } from '@/lib/api/hooks/market'
import { useMe } from '@/lib/api/hooks/identity'
import { isApiError } from '@/lib/api/problem'
import type { OrderType, PlaceOrderRequest, Side } from '@/lib/api/types'
import { useQuote } from '@/lib/realtime/useQuote'
import { useQuoteStore } from '@/lib/realtime/quoteStore'
import { type Dec, add, isDecimal, parseUserDecimal, roundToTick, sub, tickFor } from '@/lib/format/decimal'
import { formatInteger, formatPrice, formatQty } from '@/lib/format/number'
import { formatMoney } from '@/lib/format/money'
import { useDebounced } from '@/lib/useDebounced'
import { Button } from '@/components/ui/Button'
import { Segmented } from '@/components/ui/Segmented'
import { Stepper } from '@/components/ui/Stepper'
import { cx } from '@/components/ui/cx'
import { IconInfo } from '@/components/ui/icons'
import { QualityBadge } from '@/features/market/QualityBadge'
import { toast } from '@/features/notifications/toastStore'
import { EstimatesPanel } from './EstimatesPanel'
import { OrderReviewSheet } from './OrderReviewSheet'
import {
  amountHint,
  buildOrderRequest,
  canSell,
  extAllowed,
  gtcAllowed,
  initialTicket,
  midPrice,
  newClientOrderId,
  quickQuantity,
  showsLimit,
  showsStop,
  showsTrail,
  ticketReducer,
} from './ticketState'

const ORDER_TYPES: ReadonlyArray<{ value: OrderType; label: string }> = [
  { value: 'MARKET', label: 'Market' },
  { value: 'LIMIT', label: 'Limit' },
  { value: 'STOP', label: 'Stop' },
  { value: 'STOP_LIMIT', label: 'Stop Limit' },
  { value: 'TRAILING_STOP', label: 'Trailing Stop' },
]

export const ORDER_TYPE_LABEL: Record<OrderType, string> = Object.fromEntries(ORDER_TYPES.map((t) => [t.value, t.label])) as Record<OrderType, string>

function step(value: string, dir: 1 | -1, reference: Dec | null | undefined): string {
  const current = parseUserDecimal(value) ?? reference ?? null
  if (!current || !isDecimal(current)) return value
  const tick = tickFor(current)
  const next = dir === 1 ? add(current, tick) : sub(current, tick)
  return roundToTick(next.startsWith('-') ? tick : next)
}

export function TradeTicket({ symbol, initialSide = 'BUY', onDone, className }: { symbol: string; initialSide?: Side; onDone?: () => void; className?: string }) {
  const me = useMe()
  const account = useAccount()
  const positions = usePositions()
  const asset = useAsset(symbol)
  const quote = useQuote(symbol, 'quote')
  const [state, dispatch] = useReducer(ticketReducer, undefined, () => initialTicket({ side: initialSide, preferences: me.data?.preferences }))
  const [noteOpen, setNoteOpen] = useState(false)
  const [reviewing, setReviewing] = useState(false)
  const [submitErrors, setSubmitErrors] = useState<Record<string, string>>({})
  const place = usePlaceOrder()

  useEffect(() => dispatch({ type: 'side', side: initialSide }), [initialSide])

  const position = positions.data?.find((p) => p.symbol === symbol)
  const ctx = useMemo(
    () => ({ fractionable: asset.data?.fractionable ?? false, availableQty: position?.availableQty ?? null }),
    [asset.data?.fractionable, position?.availableQty],
  )
  const sellable = canSell(ctx)

  // Price fields default to the touch once per order type/side (never re-filled after the user clears them).
  const defaulted = useRef('')
  const hasQuote = quote !== undefined
  useEffect(() => {
    const q = hasQuote ? useQuoteStore.getState().quotes[symbol] : undefined
    const key = `${state.type}:${state.side}`
    if (!q || defaulted.current === key) return
    defaulted.current = key
    const touch = state.side === 'BUY' ? q.ask : q.bid
    if (showsLimit(state.type) && touch) dispatch({ type: 'field', field: 'limitPrice', value: roundToTick(touch) })
    if (showsStop(state.type) && q.last) dispatch({ type: 'field', field: 'stopPrice', value: roundToTick(q.last) })
  }, [symbol, state.type, state.side, hasQuote])

  const request = useMemo(() => buildOrderRequest(state, symbol, ctx), [state, symbol, ctx])
  const debounced = useDebounced(request, 250)
  // Preview without the idempotency key: it is optional for previews and would defeat caching.
  const previewRequest = useMemo<PlaceOrderRequest | null>(() => (debounced ? { ...debounced, clientOrderId: null } : null), [debounced])
  const preview = useOrderPreview(previewRequest)
  const previewData = request ? preview.data : undefined

  const previewFieldErrors = useMemo(() => {
    const out: Record<string, string> = {}
    for (const e of previewData?.errors ?? []) if (e.field) out[e.field] ??= e.message
    return out
  }, [previewData])
  const fieldError = (field: string) => submitErrors[field] ?? previewFieldErrors[field] ?? null
  const generalErrors = (previewData?.errors ?? []).filter((e) => !e.field)
  const gfvRisk = previewData?.warnings.some((w) => w.code === 'GFV_RISK') ?? false
  const stale = request !== debounced || preview.isFetching
  const canSubmit = Boolean(request && previewData?.valid && !stale && (!gfvRisk || state.gfvAck) && !place.isPending)

  const confirmOrders = me.data?.preferences?.confirmOrders ?? true
  const bp = account.data?.buyingPower ?? null

  const submit = async () => {
    if (!request) return
    setSubmitErrors({})
    try {
      const res = await place.mutateAsync(request)
      setReviewing(false)
      const o = res.order
      const qtyLabel = o.notional ? `${formatMoney(o.notional)}` : formatQty(o.qty)
      const priceLabel = o.limitPrice ? ` ${formatPrice(o.limitPrice)}` : ''
      toast({
        tone: o.side === 'BUY' ? 'up' : 'down',
        title: `${o.side === 'BUY' ? 'Buy' : 'Sell'} ${qtyLabel} ${o.symbol} ${ORDER_TYPE_LABEL[o.type]}${priceLabel} · ${res.replayed ? 'Already placed' : 'Pending'}`,
      })
      dispatch({ type: 'submitted', clientOrderId: newClientOrderId() })
      setNoteOpen(false)
      onDone?.()
    } catch (e) {
      // Keep the same clientOrderId so a retry is idempotent (§22.5); map field errors inline.
      setReviewing(false)
      if (isApiError(e)) {
        setSubmitErrors(e.fieldErrors)
        toast({ tone: 'error', title: e.problem.title, body: e.hint ?? e.problem.detail ?? undefined })
      } else {
        toast({ tone: 'error', title: 'Order not sent', body: 'Please try again.' })
      }
    }
  }

  const onSubmitClick = () => (confirmOrders ? setReviewing(true) : void submit())

  const setPriceFromChip = (value: Dec | null | undefined) => {
    if (!value) return
    const field = state.type === 'STOP' ? 'stopPrice' : 'limitPrice'
    dispatch({ type: 'field', field, value: roundToTick(value) })
  }
  const quick = (fraction: '0.25' | '0.5' | '1') => {
    const v = quickQuantity(fraction, {
      side: state.side,
      qtyMode: state.qtyMode,
      buyingPower: bp,
      ask: quote?.ask ?? null,
      bid: quote?.bid ?? null,
      availableQty: position?.availableQty ?? null,
      fractionable: ctx.fractionable,
    })
    if (v !== null) dispatch({ type: 'field', field: state.qtyMode === 'AMOUNT' ? 'amount' : 'qty', value: v })
  }

  const showPriceChips = showsLimit(state.type) || state.type === 'STOP'
  const sideLabel = state.side === 'BUY' ? 'Buy' : 'Sell'
  return (
    <div className={cx('flex flex-col', className)}>
      {/* Header: symbol, live last, bid × size, ask × size, quality badge */}
      <div className="flex items-center justify-between gap-2 px-4 pt-3 pb-2">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-section font-semibold">{symbol}</span>
            <QualityBadge quality={quote?.quality} />
          </div>
          <div className="text-caption text-text-3">
            <span className="text-up">{formatPrice(quote?.bid)}</span> × {formatInteger(quote?.bidSize)}
            <span className="mx-1.5 text-text-3">|</span>
            <span className="text-down">{formatPrice(quote?.ask)}</span> × {formatInteger(quote?.askSize)}
          </div>
        </div>
        <span className="text-[20px] leading-7 font-semibold">{formatPrice(quote?.last)}</span>
      </div>

      <div className="space-y-3 px-4 pb-4">
        <Segmented
          label="Side"
          block
          value={state.side}
          onChange={(side) => dispatch({ type: 'side', side })}
          options={[
            { value: 'BUY', label: 'Buy', tone: 'up' },
            { value: 'SELL', label: 'Sell', tone: 'down', disabled: !sellable, title: sellable ? undefined : 'No position to sell (short selling is not allowed)' },
          ]}
        />

        <div>
          <label htmlFor="ticket-type" className="mb-1 block text-caption font-medium text-text-2">
            Order type
          </label>
          <select
            id="ticket-type"
            value={state.type}
            onChange={(e) => dispatch({ type: 'orderType', orderType: e.target.value as OrderType })}
            className="h-10 w-full rounded-md border border-border bg-bg-2 px-3 text-body font-semibold text-text-1 outline-none focus:border-accent"
          >
            {ORDER_TYPES.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </select>
        </div>

        {showsStop(state.type) ? (
          <Stepper
            label="Stop price"
            value={state.stopPrice}
            onChange={(v) => dispatch({ type: 'field', field: 'stopPrice', value: v })}
            onStep={(d) => dispatch({ type: 'field', field: 'stopPrice', value: step(state.stopPrice, d, quote?.last) })}
            error={fieldError('stopPrice')}
            placeholder="0.00"
          />
        ) : null}
        {showsLimit(state.type) ? (
          <Stepper
            label="Limit price"
            value={state.limitPrice}
            onChange={(v) => dispatch({ type: 'field', field: 'limitPrice', value: v })}
            onStep={(d) => dispatch({ type: 'field', field: 'limitPrice', value: step(state.limitPrice, d, quote?.last) })}
            error={fieldError('limitPrice')}
            placeholder="0.00"
          />
        ) : null}
        {showsTrail(state.type) ? (
          <div className="flex items-end gap-2">
            <div className="flex-1">
              <Stepper
                label={state.trailMode === 'AMOUNT' ? 'Trail ($)' : 'Trail (%)'}
                value={state.trail}
                onChange={(v) => dispatch({ type: 'field', field: 'trail', value: v })}
                error={fieldError('trailAmount') ?? fieldError('trailPercent')}
                placeholder={state.trailMode === 'AMOUNT' ? '1.00' : '2.5'}
              />
            </div>
            <Segmented
              label="Trail unit"
              size="sm"
              value={state.trailMode}
              onChange={(mode) => dispatch({ type: 'trailMode', mode })}
              options={[
                { value: 'AMOUNT', label: '$' },
                { value: 'PERCENT', label: '%' },
              ]}
            />
          </div>
        ) : null}
        {showPriceChips ? (
          <div className="grid grid-cols-4 gap-1.5">
            {(
              [
                ['Bid', quote?.bid],
                ['Mid', quote?.bid && quote?.ask ? midPrice(quote.bid, quote.ask) : null],
                ['Ask', quote?.ask],
                ['Last', quote?.last],
              ] as const
            ).map(([label, value]) => (
              <button
                key={label}
                type="button"
                disabled={!value}
                onClick={() => setPriceFromChip(value)}
                className="flex h-9 flex-col items-center justify-center rounded-sm bg-bg-2 text-micro font-semibold text-text-2 hover:bg-bg-3 hover:text-text-1 disabled:opacity-40"
              >
                <span>{label}</span>
                <span className="text-text-1">{formatPrice(value)}</span>
              </button>
            ))}
          </div>
        ) : null}

        <div>
          <div className="mb-1 flex items-center justify-between">
            <span className="text-caption font-medium text-text-2">{sideLabel} in</span>
            <Segmented
              label="Quantity mode"
              size="sm"
              value={state.qtyMode}
              onChange={(mode) => dispatch({ type: 'qtyMode', mode })}
              options={[
                { value: 'SHARES', label: 'Shares' },
                { value: 'AMOUNT', label: 'Amount ($)' },
              ]}
            />
          </div>
          {state.qtyMode === 'SHARES' ? (
            <Stepper
              label="Shares"
              value={state.qty}
              onChange={(v) => dispatch({ type: 'field', field: 'qty', value: v })}
              onStep={(d) => {
                const current = parseUserDecimal(state.qty) ?? '0'
                const next = d === 1 ? add(current, '1') : sub(current, '1')
                dispatch({ type: 'field', field: 'qty', value: next.startsWith('-') || next === '0' ? '' : next })
              }}
              error={fieldError('qty')}
              placeholder="0"
            />
          ) : (
            <Stepper
              label="Amount"
              prefix="$"
              value={state.amount}
              onChange={(v) => dispatch({ type: 'field', field: 'amount', value: v })}
              error={amountHint(state, ctx) ?? fieldError('notional')}
              placeholder="0.00"
            />
          )}
          <div className="mt-1.5 grid grid-cols-3 gap-1.5">
            {(
              [
                ['25%', '0.25'],
                ['50%', '0.5'],
                ['Max', '1'],
              ] as const
            ).map(([label, f]) => (
              <button
                key={label}
                type="button"
                onClick={() => quick(f)}
                className="h-8 rounded-sm bg-bg-2 text-caption font-semibold text-text-2 hover:bg-bg-3 hover:text-text-1"
              >
                {label}
              </button>
            ))}
          </div>
        </div>

        <div className="flex items-center justify-between gap-3">
          <span className="text-caption font-medium text-text-2">Time in force</span>
          <Segmented
            label="Time in force"
            size="sm"
            value={state.tif}
            onChange={(tif) => dispatch({ type: 'tif', tif })}
            options={[
              { value: 'DAY', label: 'Day' },
              { value: 'GTC', label: 'GTC', disabled: !gtcAllowed(state), title: gtcAllowed(state) ? 'Good till cancelled (60 days)' : 'Not available for this order' },
            ]}
          />
        </div>

        <label className={cx('flex items-center justify-between gap-3', !extAllowed(state) && 'opacity-50')}>
          <span className="text-caption font-medium text-text-2">
            Extended hours
            <span className="block text-micro font-normal text-text-3">Limit orders only · 4:00–9:30 & 16:00–20:00 ET</span>
          </span>
          <input
            type="checkbox"
            role="switch"
            aria-checked={state.extendedHours}
            checked={state.extendedHours}
            disabled={!extAllowed(state)}
            onChange={(e) => dispatch({ type: 'extendedHours', value: e.target.checked })}
            className="size-5 accent-[var(--accent)]"
          />
        </label>
        {state.extendedHours ? (
          <p className="flex gap-1.5 rounded-sm bg-warn/10 p-2 text-caption text-warn">
            <IconInfo size={16} className="mt-0.5 shrink-0" />
            No live IEX quotes 4:00–8:00 and 17:00–20:00 ET; fills use the last known prices.
          </p>
        ) : null}

        <div className="rounded-md bg-bg-2/60 px-3 py-2">
          {request ? (
            <EstimatesPanel preview={previewData} loading={preview.isPending} notional={state.qtyMode === 'AMOUNT'} />
          ) : (
            <p className="py-1 text-caption text-text-3">Enter {state.qtyMode === 'AMOUNT' ? 'an amount' : 'a quantity'} to see estimates.</p>
          )}
        </div>

        {generalErrors.map((e) => (
          <p key={e.code} className="rounded-sm bg-down-bg p-2 text-caption text-down">
            {e.message}
            {e.hint ? <span className="block text-text-2">{e.hint}</span> : null}
          </p>
        ))}
        {previewData?.warnings
          .filter((w) => w.code !== 'GFV_RISK')
          .map((w) => (
            <p key={w.code} className="flex gap-1.5 rounded-sm bg-warn/10 p-2 text-caption text-warn">
              <IconInfo size={16} className="mt-0.5 shrink-0" />
              {w.message}
            </p>
          ))}
        {gfvRisk ? (
          <label className="flex items-start gap-2 rounded-sm border border-warn/40 bg-warn/10 p-2 text-caption text-text-1">
            <input type="checkbox" checked={state.gfvAck} onChange={(e) => dispatch({ type: 'gfvAck', value: e.target.checked })} className="mt-0.5 size-4 accent-[var(--warn)]" />
            I understand this will cause a good-faith violation
          </label>
        ) : null}

        {noteOpen ? (
          <div>
            <label htmlFor="ticket-note" className="mb-1 block text-caption font-medium text-text-2">
              Note <span className="text-text-3">({state.note.length}/1000)</span>
            </label>
            <textarea
              id="ticket-note"
              value={state.note}
              maxLength={1000}
              rows={3}
              onChange={(e) => dispatch({ type: 'field', field: 'note', value: e.target.value })}
              placeholder="Why this trade?"
              className="w-full resize-none rounded-md border border-border bg-bg-2 p-2 text-body text-text-1 outline-none focus:border-accent"
            />
            <label className="mt-1 flex items-center gap-2 text-caption text-text-2">
              <input type="checkbox" checked={state.shareNote} onChange={(e) => dispatch({ type: 'shareNote', value: e.target.checked })} className="size-4 accent-[var(--accent)]" />
              Share note publicly
            </label>
          </div>
        ) : (
          <button type="button" onClick={() => setNoteOpen(true)} className="text-caption font-semibold text-accent hover:text-accent-hover">
            + Add note
          </button>
        )}

        <Button variant={state.side === 'BUY' ? 'buy' : 'sell'} size="lg" block disabled={!canSubmit} onClick={onSubmitClick}>
          {place.isPending ? 'Sending…' : `${sideLabel} ${symbol}`}
        </Button>
        <p className="text-center text-micro text-text-3">Paper trading · simulated fills against real quotes</p>
      </div>

      {request && previewData ? (
        <OrderReviewSheet
          open={reviewing}
          onClose={() => setReviewing(false)}
          onConfirm={submit}
          pending={place.isPending}
          request={request}
          preview={previewData}
        />
      ) : null}
    </div>
  )
}
