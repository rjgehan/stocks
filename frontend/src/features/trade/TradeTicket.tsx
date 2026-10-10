/**
 * Trade ticket (§22.5): desktop right column, tablet side sheet, phone bottom sheet.
 * Side · Type (Market | Limit) · Limit price (tick stepper + Bid/Mid/Ask/Last) · Shares | Amount ($) with 25%/50%/Max ·
 * TIF · Extended hours · live preview (debounced 250 ms, re-quoted every 15 s) · warnings with the GFV acknowledgement ·
 * note with "Share note publicly" · submit (disabled until the preview is valid) · review dialog · result toast.
 * Server rejections (§7.2 codes) map to inline field errors or a banner.
 */
import { useEffect, useMemo, useReducer, useRef, useState } from 'react'
import { useAccount, useOrderPreview, usePlaceOrder, usePositions } from '@/lib/api/hooks/account'
import { useAsset } from '@/lib/api/hooks/market'
import { useMe } from '@/lib/api/hooks/identity'
import { type DescribedError, describeError, errorField } from '@/lib/api/errors'
import { isApiError } from '@/lib/api/problem'
import type { OrderType, PlaceOrderRequest, Side } from '@/lib/api/types'
import { useQuote } from '@/lib/realtime/useQuote'
import { useQuoteStore } from '@/lib/realtime/quoteStore'
import { type Dec, add, isDecimal, parseUserDecimal, roundToTick, sign, sub, tickFor } from '@/lib/format/decimal'
import { formatInteger, formatPrice, formatQty } from '@/lib/format/number'
import { formatMoney } from '@/lib/format/money'
import { useDebounced } from '@/lib/useDebounced'
import { Button } from '@/components/ui/Button'
import { Checkbox, Switch } from '@/components/ui/Field'
import { Segmented } from '@/components/ui/Segmented'
import { Stepper } from '@/components/ui/Stepper'
import { cx } from '@/components/ui/cx'
import { IconInfo } from '@/components/ui/icons'
import { ORDER_TYPE_LABEL } from '@/features/account/orderLabels'
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

/**
 * The order types the ticket offers. Phase 1 milestone M1 ships MARKET and LIMIT end to end; STOP, STOP_LIMIT and
 * TRAILING_STOP (§11.1) are wired in the ticket state and request builder and switch on here when the engine supports them.
 */
export const ENABLED_ORDER_TYPES: readonly OrderType[] = ['MARKET', 'LIMIT']

function step(value: string, dir: 1 | -1, reference: Dec | null | undefined): string {
  const current = parseUserDecimal(value) ?? reference ?? null
  if (!current || !isDecimal(current)) return value
  const tick = tickFor(current)
  const next = dir === 1 ? add(current, tick) : sub(current, tick)
  return roundToTick(next.startsWith('-') ? tick : next)
}

/** Maps a server/preview error to the input it belongs to, given the quantity mode the ticket shows. */
function inputFor(field: string | null | undefined, code: string | null | undefined, amountMode: boolean): string | null {
  const f = field ?? errorField(code) ?? null
  if (f === 'qty' || f === 'notional') return amountMode ? 'notional' : 'qty'
  return f
}

export function TradeTicket({ symbol, initialSide = 'BUY', onDone, className }: { symbol: string; initialSide?: Side; onDone?: () => void; className?: string }) {
  const me = useMe()
  const account = useAccount()
  const positions = usePositions()
  const asset = useAsset(symbol)
  const quote = useQuote(symbol, 'quote')
  const [state, dispatch] = useReducer(ticketReducer, undefined, () => {
    const s = initialTicket({ side: initialSide, preferences: me.data?.preferences })
    return ENABLED_ORDER_TYPES.includes(s.type) ? s : ticketReducer(s, { type: 'orderType', orderType: 'LIMIT' })
  })
  const [noteOpen, setNoteOpen] = useState(false)
  const [reviewing, setReviewing] = useState(false)
  const [submitErrors, setSubmitErrors] = useState<Record<string, string>>({})
  const [banner, setBanner] = useState<DescribedError | null>(null)
  const place = usePlaceOrder()

  useEffect(() => dispatch({ type: 'side', side: initialSide }), [initialSide])

  const position = positions.data?.find((p) => p.symbol === symbol)
  const ctx = useMemo(() => ({ fractionable: asset.data?.fractionable ?? false, availableQty: position?.availableQty ?? null }), [asset.data?.fractionable, position?.availableQty])
  const sellable = canSell(ctx)
  const tradable = asset.data ? asset.data.tradable : true
  const amountMode = state.qtyMode === 'AMOUNT'

  // A sell ticket on a symbol you no longer hold falls back to Buy.
  useEffect(() => {
    if (state.side === 'SELL' && positions.isSuccess && !sellable) dispatch({ type: 'side', side: 'BUY' })
  }, [state.side, positions.isSuccess, sellable])

  // Price fields default to the touch once per order type/side (never re-filled after the user clears them).
  const defaulted = useRef('')
  const hasQuote = quote !== undefined
  useEffect(() => {
    const q = hasQuote ? useQuoteStore.getState().quotes[symbol] : undefined
    const key = `${state.type}:${state.side}`
    if (!q || defaulted.current === key) return
    defaulted.current = key
    const touch = state.side === 'BUY' ? q.ask : q.bid
    if (showsLimit(state.type) && (touch ?? q.last)) dispatch({ type: 'field', field: 'limitPrice', value: roundToTick((touch ?? q.last)!) })
    if (showsStop(state.type) && q.last) dispatch({ type: 'field', field: 'stopPrice', value: roundToTick(q.last) })
  }, [symbol, state.type, state.side, hasQuote])

  const request = useMemo(() => buildOrderRequest(state, symbol, ctx), [state, symbol, ctx])
  const debounced = useDebounced(request, 250)
  // Preview without the idempotency key: it is optional for previews and would defeat caching.
  const previewRequest = useMemo<PlaceOrderRequest | null>(() => (debounced ? { ...debounced, clientOrderId: null } : null), [debounced])
  const preview = useOrderPreview(previewRequest)
  const previewData = request ? preview.data : undefined
  const previewErrors = useMemo(() => previewData?.errors ?? [], [previewData])
  const warnings = useMemo(() => previewData?.warnings ?? [], [previewData])

  const fieldErrors = useMemo(() => {
    const out: Record<string, string> = {}
    for (const e of previewErrors) {
      const f = inputFor(e.field, e.code, amountMode)
      if (f && e.message) out[f] ??= e.message
    }
    return { ...out, ...submitErrors }
  }, [previewErrors, submitErrors, amountMode])
  const placed = new Set(['qty', 'notional', 'limitPrice', 'stopPrice'])
  const generalErrors = previewErrors.filter((e) => !placed.has(inputFor(e.field, e.code, amountMode) ?? ''))
  const gfvRisk = warnings.some((w) => w.code === 'GFV_RISK')
  const stale = request !== debounced || preview.isFetching
  const canSubmit = Boolean(tradable && request && previewData?.valid && !stale && (!gfvRisk || state.gfvAck) && !place.isPending)

  const confirmOrders = me.data?.preferences?.confirmOrders ?? true
  const bp = account.data?.buyingPower ?? null

  // Clear stale submit errors once the user edits the ticket.
  const editKey = `${state.qty}|${state.amount}|${state.limitPrice}|${state.type}|${state.side}|${state.qtyMode}`
  const lastEdit = useRef(editKey)
  useEffect(() => {
    if (lastEdit.current === editKey) return
    lastEdit.current = editKey
    setSubmitErrors({})
    setBanner(null)
  }, [editKey])

  const submit = async () => {
    if (!request) return
    setSubmitErrors({})
    setBanner(null)
    try {
      const res = await place.mutateAsync(request)
      setReviewing(false)
      const o = res.order
      if (o) {
        const size = o.notional ? formatMoney(o.notional) : formatQty(o.qty)
        const price = o.limitPrice ? ` ${formatPrice(o.limitPrice)}` : ''
        toast({
          tone: o.side === 'BUY' ? 'up' : 'down',
          title: `${o.side === 'BUY' ? 'Buy' : 'Sell'} ${size} ${o.symbol} ${o.type ? ORDER_TYPE_LABEL[o.type] : ''}${price} · ${res.replayed ? 'Already placed' : 'Pending'}`,
          body: o.waitReason === 'QUEUED_FOR_OPEN' ? 'Queued for the 9:30 ET open.' : o.waitReason === 'QUEUED_FOR_EXT_OPEN' ? 'Queued for 4:00 ET pre-market.' : undefined,
        })
      }
      dispatch({ type: 'submitted', clientOrderId: newClientOrderId() })
      setNoteOpen(false)
      onDone?.()
    } catch (e) {
      setReviewing(false)
      const d = describeError(e)
      if (isApiError(e)) {
        // A different order already used this id (§11.7): start a new one so the retry is a new order.
        if (e.code === 'CLIENT_ORDER_ID_CONFLICT' || e.code === 'CLIENT_ORDER_ID_INVALID') dispatch({ type: 'submitted', clientOrderId: newClientOrderId() })
        const fields: Record<string, string> = {}
        for (const f of e.problem.fields) {
          const input = inputFor(f.field, f.code, amountMode)
          if (input) fields[input] ??= f.message
        }
        const fallback = inputFor(null, e.code, amountMode)
        if (fallback && !fields[fallback] && placed.has(fallback)) fields[fallback] = d.detail ?? d.title
        setSubmitErrors(fields)
        if (Object.keys(fields).length === 0) setBanner(d)
        toast({ tone: 'error', title: d.title, body: d.hint ?? d.detail ?? undefined })
      } else {
        // Network failure: keep the same clientOrderId, so retrying cannot create a second order (§22.5).
        setBanner(d)
        toast({ tone: 'error', title: 'Order not confirmed', body: 'Check your connection and press the button again; it will not double the order.' })
      }
    }
  }

  const onSubmitClick = () => (confirmOrders ? setReviewing(true) : void submit())

  const setPriceFromChip = (value: Dec | null | undefined) => {
    if (!value) return
    dispatch({ type: 'field', field: state.type === 'STOP' ? 'stopPrice' : 'limitPrice', value: roundToTick(value) })
  }
  const quick = (fraction: '0.25' | '0.5' | '1') => {
    const v = quickQuantity(fraction, {
      side: state.side,
      qtyMode: state.qtyMode,
      buyingPower: bp,
      ask: quote?.ask ?? quote?.last ?? null,
      bid: quote?.bid ?? quote?.last ?? null,
      availableQty: position?.availableQty ?? null,
      fractionable: ctx.fractionable,
    })
    if (v !== null && sign(v) > 0) dispatch({ type: 'field', field: amountMode ? 'amount' : 'qty', value: v })
  }

  const showPriceChips = showsLimit(state.type) || state.type === 'STOP'
  const sideLabel = state.side === 'BUY' ? 'Buy' : 'Sell'
  const est = previewData?.estimate
  const types = ENABLED_ORDER_TYPES.map((t) => ({ value: t, label: ORDER_TYPE_LABEL[t] }))
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
            Bid <span className="font-medium text-text-1">{formatPrice(quote?.bid)}</span> × {formatInteger(quote?.bidSize)}
            <span className="mx-1.5 text-text-3">·</span>
            Ask <span className="font-medium text-text-1">{formatPrice(quote?.ask)}</span> × {formatInteger(quote?.askSize)}
          </div>
        </div>
        <span className="text-[20px] leading-7 font-semibold" aria-label={`Last ${formatPrice(quote?.last)}`}>
          {formatPrice(quote?.last)}
        </span>
      </div>

      <div className="space-y-3 px-4 pb-4">
        {!tradable ? <p className="rounded-sm bg-warn/10 p-2 text-caption text-warn">{symbol} cannot be traded right now.</p> : null}
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

        <div className="flex items-center justify-between gap-3">
          <span className="text-caption font-medium text-text-2">Order type</span>
          <Segmented label="Order type" size="sm" value={state.type} onChange={(orderType) => dispatch({ type: 'orderType', orderType })} options={types} />
        </div>

        {showsStop(state.type) ? (
          <Stepper
            label="Stop price"
            value={state.stopPrice}
            onChange={(v) => dispatch({ type: 'field', field: 'stopPrice', value: v })}
            onStep={(d) => dispatch({ type: 'field', field: 'stopPrice', value: step(state.stopPrice, d, quote?.last) })}
            error={fieldErrors.stopPrice}
            placeholder="0.00"
          />
        ) : null}
        {showsLimit(state.type) ? (
          <Stepper
            label="Limit price"
            value={state.limitPrice}
            onChange={(v) => dispatch({ type: 'field', field: 'limitPrice', value: v })}
            onStep={(d) => dispatch({ type: 'field', field: 'limitPrice', value: step(state.limitPrice, d, quote?.last) })}
            error={fieldErrors.limitPrice}
            placeholder="0.00"
          />
        ) : null}
        {showsTrail(state.type) ? (
          <Stepper
            label={state.trailMode === 'AMOUNT' ? 'Trail ($)' : 'Trail (%)'}
            value={state.trail}
            onChange={(v) => dispatch({ type: 'field', field: 'trail', value: v })}
            error={fieldErrors.trailAmount ?? fieldErrors.trailPercent}
            placeholder={state.trailMode === 'AMOUNT' ? '1.00' : '2.5'}
          />
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
                aria-label={`Use the ${label.toLowerCase()} price ${formatPrice(value)}`}
                className="flex h-10 flex-col items-center justify-center rounded-sm bg-bg-2 text-micro font-semibold text-text-2 hover:bg-bg-3 hover:text-text-1 disabled:opacity-40"
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
                { value: 'AMOUNT', label: 'Amount ($)', disabled: !ctx.fractionable && asset.isSuccess, title: ctx.fractionable ? undefined : 'Dollar orders need a fractionable symbol' },
              ]}
            />
          </div>
          {amountMode ? (
            <Stepper
              label="Amount"
              prefix="$"
              value={state.amount}
              onChange={(v) => dispatch({ type: 'field', field: 'amount', value: v })}
              error={amountHint(state, ctx) ?? fieldErrors.notional}
              placeholder="0.00"
            />
          ) : (
            <Stepper
              label="Shares"
              value={state.qty}
              onChange={(v) => dispatch({ type: 'field', field: 'qty', value: v })}
              onStep={(d) => {
                const current = parseUserDecimal(state.qty) ?? '0'
                const next = d === 1 ? add(current, '1') : sub(current, '1')
                dispatch({ type: 'field', field: 'qty', value: next.startsWith('-') || sign(next) === 0 ? '' : next })
              }}
              error={fieldErrors.qty}
              placeholder="0"
            />
          )}
          <div className="mt-1.5 grid grid-cols-3 gap-1.5">
            {(
              [
                ['25%', '0.25'],
                ['50%', '0.5'],
                [state.side === 'SELL' ? 'All' : 'Max', '1'],
              ] as const
            ).map(([label, f]) => (
              <button key={label} type="button" onClick={() => quick(f)} className="h-9 rounded-sm bg-bg-2 text-caption font-semibold text-text-2 hover:bg-bg-3 hover:text-text-1">
                {label}
              </button>
            ))}
          </div>
          <p className="mt-1.5 flex justify-between text-caption text-text-3">
            {state.side === 'BUY' ? (
              <span>
                Buying power <span className="font-semibold text-text-1">{formatMoney(bp)}</span>
              </span>
            ) : (
              <span>
                Available <span className="font-semibold text-text-1">{formatQty(position?.availableQty)}</span> sh
              </span>
            )}
            {est ? <span>{amountMode ? `≈ ${formatQty(est.qty)} sh` : `≈ ${formatMoney(est.principal)}`}</span> : null}
          </p>
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
              { value: 'GTC', label: 'GTC', disabled: !gtcAllowed(state), title: gtcAllowed(state) ? 'Good till cancelled (60 days)' : 'Market and dollar orders are Day only' },
            ]}
          />
        </div>

        <Switch
          checked={state.extendedHours}
          disabled={!extAllowed(state)}
          onChange={(value) => dispatch({ type: 'extendedHours', value })}
          label={<span className="text-caption font-medium text-text-2">Extended hours</span>}
          description={<span className="text-micro">Limit orders only · 4:00–9:30 & 16:00–20:00 ET</span>}
        />
        {state.extendedHours ? (
          <p className="flex gap-1.5 rounded-sm bg-warn/10 p-2 text-caption text-warn">
            <IconInfo size={16} className="mt-0.5 shrink-0" />
            No live IEX quotes 4:00–8:00 and 17:00–20:00 ET; orders then use the last known prices.
          </p>
        ) : null}

        <div className="rounded-md bg-bg-2/60 px-3 py-2" aria-live="polite">
          {request ? (
            <EstimatesPanel preview={previewData} loading={preview.isPending} notional={amountMode} />
          ) : (
            <p className="py-1 text-caption text-text-3">Enter {amountMode ? 'an amount' : 'a quantity'} to see the estimate.</p>
          )}
        </div>

        {generalErrors.map((e) => (
          <p key={`${e.code}-${e.message}`} role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
            {e.message}
            {e.hint ? <span className="block text-text-2">{e.hint}</span> : null}
          </p>
        ))}
        {banner ? (
          <div role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
            <p className="font-semibold">{banner.title}</p>
            {banner.detail ?? banner.hint ? <p className="text-text-2">{banner.detail ?? banner.hint}</p> : null}
          </div>
        ) : null}
        {warnings
          .filter((w) => w.code !== 'GFV_RISK')
          .map((w) => (
            <p key={w.code} className="flex gap-1.5 rounded-sm bg-warn/10 p-2 text-caption text-warn">
              <IconInfo size={16} className="mt-0.5 shrink-0" />
              {w.message}
            </p>
          ))}
        {gfvRisk ? (
          <div className="rounded-sm border border-warn/40 bg-warn/10 p-2">
            <p className="mb-1.5 text-caption text-warn">{warnings.find((w) => w.code === 'GFV_RISK')?.message}</p>
            <Checkbox checked={state.gfvAck} onChange={(value) => dispatch({ type: 'gfvAck', value })}>
              I understand this will cause a good-faith violation
            </Checkbox>
          </div>
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
              placeholder="Why this trade? (private unless you share it)"
              className="w-full resize-none rounded-md border border-border bg-bg-2 p-2 text-body text-text-1 outline-none focus:border-accent"
            />
            <Checkbox checked={state.shareNote} onChange={(value) => dispatch({ type: 'shareNote', value })} className="mt-1 text-text-2">
              Share note publicly (shown with your delayed trade)
            </Checkbox>
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
        <OrderReviewSheet open={reviewing} onClose={() => setReviewing(false)} onConfirm={() => void submit()} pending={place.isPending} request={request} preview={previewData} />
      ) : null}
    </div>
  )
}
