/**
 * Trade ticket (§22.5): desktop right column, tablet side sheet, phone bottom sheet.
 * Side · Type (the types `GET /rules` publishes: Market, Limit, Stop, Stop Limit, Trailing Stop) · stop / limit price
 * (tick steppers + Bid/Mid/Ask/Last chips for the price last focused) · trail ($ | %) · Shares | Amount ($) with
 * 25%/50%/Max · TIF (per the order matrix) · Extended hours · live preview (debounced 250 ms) · warnings with the GFV
 * acknowledgement · note with "Share note publicly" · submit (disabled until the preview is valid) · review dialog ·
 * result toast. Server rejections (§7.2 codes) map to inline field errors or a banner. Phase 2: a Simple · Advanced
 * switch (when `GET /rules` lists `orders.groups`) swaps the body for the group-order ticket (`advanced/`, §11.9).
 */
import { Suspense, lazy, useEffect, useMemo, useReducer, useRef, useState } from 'react'
import { useAccount, useOrderPreview, usePlaceOrder, usePositions } from '@/lib/api/hooks/account'
import { useAsset, useClock, useRules } from '@/lib/api/hooks/market'
import { useMe } from '@/lib/api/hooks/identity'
import { type DescribedError, describeError, errorField, orderErrorText, warningText } from '@/lib/api/errors'
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
import { IconChevronDown } from '@/components/ui/icons'
import { Segmented } from '@/components/ui/Segmented'
import { Stepper } from '@/components/ui/Stepper'
import { cx } from '@/components/ui/cx'
import { IconInfo } from '@/components/ui/icons'
import { ORDER_TYPE_LABEL, orderPriceLabel } from '@/features/account/orderLabels'
import { QualityBadge } from '@/features/market/QualityBadge'
import { toast } from '@/features/notifications/toastStore'
import { EstimatesPanel } from './EstimatesPanel'
import { OrderReviewSheet } from './OrderReviewSheet'
import {
  BASE_ORDER_TYPES,
  amountHint,
  availableOrderTypes,
  buildOrderRequest,
  canSell,
  defaultLimitPrice,
  defaultStopPrice,
  defaultTrail,
  dividendAdjustable,
  extAllowed,
  gtcAllowed,
  initialTicket,
  isStopType,
  midPrice,
  newClientOrderId,
  quickQuantity,
  showsLimit,
  showsStop,
  showsTrail,
  stepTrail,
  ticketExplanation,
  ticketReducer,
  trailingStopStart,
  withoutQueueDuplicates,
} from './ticketState'

/** The Advanced (group order) ticket and its review sheet load when Advanced is first chosen. */
const AdvancedTicket = lazy(() => import('./advanced/AdvancedTicket').then((m) => ({ default: m.AdvancedTicket })))

/** The price inputs the Bid · Mid · Ask · Last chips can fill. */
type PriceField = 'limitPrice' | 'stopPrice'

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
  const rules = useRules()
  const clock = useClock()
  const quote = useQuote(symbol, 'quote')
  const matrix = rules.data?.orders?.matrix ?? null
  const orderTypes = useMemo(() => availableOrderTypes(matrix), [matrix])
  const [state, dispatch] = useReducer(ticketReducer, undefined, () => {
    const s = initialTicket({ side: initialSide, preferences: me.data?.preferences })
    return BASE_ORDER_TYPES.includes(s.type) ? s : ticketReducer(s, { type: 'orderType', orderType: 'LIMIT' })
  })
  const [priceField, setPriceField] = useState<PriceField>('limitPrice')
  const [noteOpen, setNoteOpen] = useState(false)
  const [reviewing, setReviewing] = useState(false)
  const [submitErrors, setSubmitErrors] = useState<Record<string, string>>({})
  const [banner, setBanner] = useState<DescribedError | null>(null)
  const place = usePlaceOrder()
  // Phase 2 group orders (§11.9): offered when the rulebook lists them
  const groupRules = rules.data?.orders?.groups ?? []
  const [ticketMode, setTicketMode] = useState<'SIMPLE' | 'ADVANCED'>('SIMPLE')
  const advanced = ticketMode === 'ADVANCED' && groupRules.length > 0

  useEffect(() => dispatch({ type: 'side', side: initialSide }), [initialSide])

  const position = positions.data?.find((p) => p.symbol === symbol)
  const ctx = useMemo(() => ({ fractionable: asset.data?.fractionable ?? false, availableQty: position?.availableQty ?? null }), [asset.data?.fractionable, position?.availableQty])
  const sellable = canSell(ctx)
  const tradable = asset.data ? asset.data.tradable : true
  const amountMode = state.qtyMode === 'AMOUNT'

  // A type the rulebook no longer lists (or a stale default) falls back to Limit.
  useEffect(() => {
    if (!orderTypes.includes(state.type)) dispatch({ type: 'orderType', orderType: orderTypes.includes('LIMIT') ? 'LIMIT' : orderTypes[0]! })
  }, [orderTypes, state.type])
  const matrixRow = matrix?.find((r) => r.type === state.type) ?? null
  const gtcOk = gtcAllowed(state, matrixRow)
  useEffect(() => {
    if (state.tif === 'GTC' && !gtcOk) dispatch({ type: 'tif', tif: 'DAY' })
  }, [state.tif, gtcOk])
  // the chips fill the stop price on a Stop order, the limit on Limit; on Stop Limit, whichever was focused last
  const chipTarget: PriceField | null = showsStop(state.type) && showsLimit(state.type) ? priceField : showsStop(state.type) ? 'stopPrice' : showsLimit(state.type) ? 'limitPrice' : null

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
    const stop = defaultStopPrice(state.side, q.last)
    // a stop limit starts with its limit at the stop (it fills at the stop or better once triggered)
    const limit = state.type === 'STOP_LIMIT' ? stop : defaultLimitPrice(state.side, q)
    if (showsLimit(state.type) && limit) dispatch({ type: 'field', field: 'limitPrice', value: limit })
    if (showsStop(state.type) && stop) dispatch({ type: 'field', field: 'stopPrice', value: stop })
    if (showsTrail(state.type) && !state.trail) dispatch({ type: 'field', field: 'trail', value: defaultTrail(state.trailMode, q.last) })
    setPriceField(showsStop(state.type) ? 'stopPrice' : 'limitPrice')
  }, [symbol, state.type, state.side, state.trail, state.trailMode, hasQuote])

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
      if (f && e.message) out[f] ??= orderErrorText(e)
    }
    return { ...out, ...submitErrors }
  }, [previewErrors, submitErrors, amountMode])
  const placed = new Set(['qty', 'notional', 'limitPrice', 'stopPrice', 'trailAmount', 'trailPercent'])
  const session = clock.data?.session
  const stopSessionNote =
    session && session !== 'REGULAR' && isStopType(state.type)
      ? state.type === 'TRAILING_STOP'
        ? 'Trailing stops can only be placed during regular hours, 9:30–16:00 ET.'
        : 'Stops only trigger during regular hours (9:30–16:00 ET); this one arms at the next open.'
      : null
  // the session note already says it: the server's SESSION_NOT_ALLOWED (and its hint) would repeat it twice more
  const generalErrors = previewErrors.filter(
    (e) => !placed.has(inputFor(e.field, e.code, amountMode) ?? '') && !(stopSessionNote && e.code === 'SESSION_NOT_ALLOWED'),
  )
  // "Fix the highlighted fields" only when an error really sits on an input (not for ENGINE_HALTED, type errors, …)
  const highlighted = previewErrors.length > generalErrors.length
  const gfvRisk = warnings.some((w) => w.code === 'GFV_RISK')
  const stale = request !== debounced || preview.isFetching
  const canSubmit = Boolean(tradable && request && previewData?.valid && !stale && (!gfvRisk || state.gfvAck) && !place.isPending)

  const confirmOrders = me.data?.preferences?.confirmOrders ?? true
  const bp = account.data?.buyingPower ?? null

  // Clear stale submit errors once the user edits the ticket.
  const editKey = `${state.qty}|${state.amount}|${state.limitPrice}|${state.stopPrice}|${state.trail}|${state.trailMode}|${state.type}|${state.side}|${state.qtyMode}|${state.tif}`
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
        const price = orderPriceLabel(o)
        toast({
          tone: o.side === 'BUY' ? 'up' : 'down',
          title: `${o.side === 'BUY' ? 'Buy' : 'Sell'} ${size} ${o.symbol} ${o.type ? ORDER_TYPE_LABEL[o.type] : ''}${price ? ` ${price}` : ''} · ${res.replayed ? 'Already placed' : 'Pending'}`,
          body:
            o.waitReason === 'QUEUED_FOR_OPEN'
              ? isStopType(o.type)
                ? 'Arms at the 9:30 ET open.'
                : 'Queued for the 9:30 ET open.'
              : o.waitReason === 'QUEUED_FOR_EXT_OPEN'
                ? 'Queued for 4:00 ET pre-market.'
                : isStopType(o.type)
                  ? 'Waiting for the stop price.'
                  : undefined,
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
          if (input) fields[input] ??= orderErrorText({ code: f.code ?? e.code, message: f.message })
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
    if (!value || !chipTarget) return
    dispatch({ type: 'field', field: chipTarget, value: roundToTick(value) })
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

  const showPriceChips = chipTarget !== null
  const sideLabel = state.side === 'BUY' ? 'Buy' : 'Sell'
  const est = previewData?.estimate
  const trailStart = showsTrail(state.type) ? trailingStopStart(state.side, quote?.last, state.trail, state.trailMode) : null
  const explanation = ticketExplanation(state, symbol)
  // a corporate-action hold reports the asset untradable while its quote reads halted (§14)
  const untradableNote = tradable
    ? null
    : quote?.halted
      ? `${symbol} is on hold while a corporate action (such as a split) is reviewed. Orders open again once it is resolved.`
      : `${symbol} cannot be traded right now (inactive, or not tradable on this platform).`
  const modeSwitch = groupRules.length ? (
    <Segmented
      label="Ticket mode"
      block
      size="sm"
      value={advanced ? 'ADVANCED' : 'SIMPLE'}
      onChange={setTicketMode}
      options={[
        { value: 'SIMPLE', label: 'Simple' },
        { value: 'ADVANCED', label: 'Advanced', title: 'Bracket, OCO, OTO and OTOCO orders' },
      ]}
    />
  ) : null
  const header = (
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
  )
  if (advanced) {
    return (
      <div className={cx('flex flex-col', className)}>
        {header}
        <div className="space-y-3 px-4 pb-1">
          {modeSwitch}
          {untradableNote ? (
            <p role="status" className="flex gap-1.5 rounded-sm bg-warn/10 p-2 text-caption text-warn">
              <IconInfo size={16} className="mt-0.5 shrink-0" />
              {untradableNote}
            </p>
          ) : null}
          <Suspense fallback={<div className="h-64 animate-pulse rounded-md bg-bg-2" aria-busy="true" />}>
            <AdvancedTicket symbol={symbol} groups={groupRules} confirmOrders={confirmOrders} onDone={onDone} />
          </Suspense>
        </div>
      </div>
    )
  }
  return (
    <div className={cx('flex flex-col', className)}>
      {/* Header: symbol, live last, bid × size, ask × size, quality badge */}
      {header}

      <div className="space-y-3 px-4 pb-4">
        {untradableNote ? (
          <p role="status" className="flex gap-1.5 rounded-sm bg-warn/10 p-2 text-caption text-warn">
            <IconInfo size={16} className="mt-0.5 shrink-0" />
            {untradableNote}
          </p>
        ) : null}
        {modeSwitch}
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
          <label htmlFor={`${symbol}-order-type`} className="text-caption font-medium text-text-2">
            Order type
          </label>
          <div className="relative">
            <select
              id={`${symbol}-order-type`}
              value={state.type}
              onChange={(e) => dispatch({ type: 'orderType', orderType: e.target.value as OrderType })}
              className="h-11 appearance-none rounded-md border border-border bg-bg-2 pr-8 pl-3 text-caption font-semibold text-text-1 outline-none focus:border-accent tablet:h-8"
            >
              {orderTypes.map((t) => (
                <option key={t} value={t}>
                  {ORDER_TYPE_LABEL[t]}
                </option>
              ))}
            </select>
            <IconChevronDown size={16} className="pointer-events-none absolute top-1/2 right-2 -translate-y-1/2 text-text-3" />
          </div>
        </div>

        {showsStop(state.type) ? (
          <Stepper
            label="Stop price"
            value={state.stopPrice}
            onChange={(v) => dispatch({ type: 'field', field: 'stopPrice', value: v })}
            onStep={(d) => dispatch({ type: 'field', field: 'stopPrice', value: step(state.stopPrice, d, quote?.last) })}
            onFocus={() => setPriceField('stopPrice')}
            active={chipTarget === 'stopPrice' && showsLimit(state.type)}
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
            onFocus={() => setPriceField('limitPrice')}
            active={chipTarget === 'limitPrice' && showsStop(state.type)}
            error={fieldErrors.limitPrice}
            placeholder="0.00"
          />
        ) : null}
        {showsTrail(state.type) ? (
          <div>
            <Stepper
              label="Trail"
              labelAside={
                <Segmented
                  label="Trail type"
                  size="sm"
                  value={state.trailMode}
                  onChange={(mode) => {
                    dispatch({ type: 'trailMode', mode })
                    dispatch({ type: 'field', field: 'trail', value: defaultTrail(mode, quote?.last) })
                  }}
                  options={[
                    { value: 'AMOUNT', label: '$', title: 'Trail by a dollar amount' },
                    { value: 'PERCENT', label: '%', title: 'Trail by a percentage' },
                  ]}
                />
              }
              prefix={state.trailMode === 'AMOUNT' ? '$' : undefined}
              suffix={state.trailMode === 'PERCENT' ? <span className="pr-2 text-text-3">%</span> : undefined}
              value={state.trail}
              onChange={(v) => dispatch({ type: 'field', field: 'trail', value: v })}
              onStep={(d) => dispatch({ type: 'field', field: 'trail', value: stepTrail(state.trail, d, state.trailMode, quote?.last) })}
              error={fieldErrors.trailAmount ?? fieldErrors.trailPercent}
              placeholder={state.trailMode === 'AMOUNT' ? '1.00' : '2.5'}
            />
            <p className="mt-1 text-caption text-text-3">
              Stop starts at <span className="font-semibold text-text-1">{trailStart ? formatPrice(trailStart) : '--'}</span>
              <span className="text-text-3"> · then follows the {state.side === 'SELL' ? 'highest' : 'lowest'} price</span>
            </p>
          </div>
        ) : null}
        {explanation ? <p className="rounded-sm bg-accent-bg px-2 py-1.5 text-caption text-text-1">{explanation}</p> : null}
        {stopSessionNote ? (
          <p className="flex gap-1.5 rounded-sm bg-warn/10 p-2 text-caption text-warn">
            <IconInfo size={16} className="mt-0.5 shrink-0" />
            {stopSessionNote}
          </p>
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
                aria-label={`Use the ${label.toLowerCase()} price ${formatPrice(value)}${chipTarget === 'stopPrice' ? ' as the stop' : ''}`}
                className="flex h-11 flex-col items-center justify-center rounded-sm bg-bg-2 text-micro font-semibold text-text-2 hover:bg-bg-3 hover:text-text-1 disabled:opacity-40 tablet:h-10"
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
              <button key={label} type="button" onClick={() => quick(f)} className="h-11 rounded-sm bg-bg-2 text-caption font-semibold text-text-2 hover:bg-bg-3 hover:text-text-1 tablet:h-9">
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
              {
                value: 'GTC',
                label: 'GTC',
                disabled: !gtcOk,
                title: gtcOk ? 'Good till cancelled (60 days)' : state.type === 'TRAILING_STOP' ? 'Trailing stops are Day only' : 'Market and dollar orders are Day only',
              },
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
        {dividendAdjustable(state) ? (
          <Checkbox checked={state.doNotReduce} onChange={(value) => dispatch({ type: 'doNotReduce', value })} className="text-caption text-text-2">
            Don't reduce on dividends (keep my prices on ex-dividend dates)
          </Checkbox>
        ) : null}

        <div className="rounded-md bg-bg-2/60 px-3 py-2" aria-live="polite">
          {request ? (
            <EstimatesPanel preview={previewData} loading={preview.isPending} notional={amountMode} stop={isStopType(state.type)} highlighted={highlighted} />
          ) : (
            <p className="py-1 text-caption text-text-3">Enter {amountMode ? 'an amount' : 'a quantity'} to see the estimate.</p>
          )}
        </div>

        {generalErrors.map((e) => (
          <div key={`${e.code}-${e.message}`} role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
            <p>{orderErrorText(e)}</p>
            {e.hint ? <p className="text-text-2">{e.hint}</p> : null}
            {e.code === 'FRACTIONAL_MARKET_ONLY' && state.type !== 'MARKET' ? (
              // the common case: selling a fractional position ("All" after an Amount buy) with the default Limit type
              <Button size="sm" variant="secondary" className="mt-1.5" onClick={() => dispatch({ type: 'orderType', orderType: 'MARKET' })}>
                Use a Market order
              </Button>
            ) : null}
          </div>
        ))}
        {banner ? (
          <div role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
            <p className="font-semibold">{banner.title}</p>
            {banner.detail ?? banner.hint ? <p className="text-text-2">{banner.detail ?? banner.hint}</p> : null}
          </div>
        ) : null}
        {withoutQueueDuplicates(warnings, previewData?.sessionNote)
          .filter((w) => w.code !== 'GFV_RISK')
          .map((w) => (
            <p key={w.code} className="flex gap-1.5 rounded-sm bg-warn/10 p-2 text-caption text-warn">
              <IconInfo size={16} className="mt-0.5 shrink-0" />
              {warningText(w)}
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
              placeholder="Why this trade? (public)"
              className="w-full resize-none rounded-md border border-border bg-bg-2 p-2 text-body text-text-1 outline-none focus:border-accent"
            />
            <p className="mt-1 text-micro text-text-3">Notes are public: every player sees them with the order.</p>
          </div>
        ) : (
          <button type="button" onClick={() => setNoteOpen(true)} className="inline-flex min-h-11 items-center text-caption font-semibold text-accent hover:text-accent-hover tablet:min-h-0">
            + Add note
          </button>
        )}

        <p className="text-center text-micro text-text-3">Paper trading · simulated fills against real quotes</p>
      </div>

      {/* The primary action stays in view however long the ticket gets (stop types, estimates, warnings): pinned to the
          bottom of the scrolling column (desktop) or sheet (phone, tablet). */}
      <div className="sticky bottom-0 z-10 border-t border-divider bg-bg-1 px-4 py-3">
        {est && request ? (
          <p className="mb-1.5 flex justify-between text-caption text-text-3">
            <span>{amountMode ? 'Estimated shares' : state.side === 'BUY' ? 'Estimated cost' : 'Estimated proceeds'}</span>
            <span className="font-semibold text-text-1">{amountMode ? `≈ ${formatQty(est.qty)} sh` : `≈ ${formatMoney(est.principal)}`}</span>
          </p>
        ) : null}
        <Button variant={state.side === 'BUY' ? 'buy' : 'sell'} size="lg" block disabled={!canSubmit} onClick={onSubmitClick}>
          {place.isPending ? 'Sending…' : `${sideLabel} ${symbol}`}
        </Button>
      </div>

      {request && previewData ? (
        <OrderReviewSheet open={reviewing} onClose={() => setReviewing(false)} onConfirm={() => void submit()} pending={place.isPending} request={request} preview={previewData} />
      ) : null}
    </div>
  )
}
