/**
 * The Advanced part of the trade ticket (§22.5, §11.9): Bracket · OCO · OTO · OTOCO (the types `GET /rules`
 * `orders.groups` lists). Entry, take-profit, stop-loss and "then" cards with their prices and the distance from the
 * entry, whole shares, Day/GTC for the group, a note and "Don't reduce on dividends"; a live group preview
 * (`POST /order-groups/preview`, debounced) whose errors land on the leg's input; submit opens the group review sheet
 * and posts `POST /order-groups` with a `web-<uuid>` group id that is reused on a network retry.
 */
import { useEffect, useMemo, useReducer, useRef, useState } from 'react'
import { describeError, errorBody, warningText } from '@/lib/api/errors'
import type { OrderGroupRule, OrderGroupType } from '@/lib/api/groupTypes'
import { useGroupPreview, usePlaceGroup } from '@/lib/api/hooks/orderGroups'
import { usePositions } from '@/lib/api/hooks/account'
import { isApiError } from '@/lib/api/problem'
import { useQuote } from '@/lib/realtime/useQuote'
import { useQuoteStore } from '@/lib/realtime/quoteStore'
import { type Dec, add, isDecimal, parseUserDecimal, roundToTick, sub, tickFor } from '@/lib/format/decimal'
import { formatMoney } from '@/lib/format/money'
import { formatPrice, formatQty } from '@/lib/format/number'
import { useDebounced } from '@/lib/useDebounced'
import { Button } from '@/components/ui/Button'
import { Checkbox } from '@/components/ui/Field'
import { IconInfo } from '@/components/ui/icons'
import { Segmented } from '@/components/ui/Segmented'
import { Stepper } from '@/components/ui/Stepper'
import { ORDER_TYPE_LABEL } from '@/features/account/orderLabels'
import { GROUP_TYPE_HELP, GROUP_TYPE_LABEL, ROLE_LABEL } from '@/features/groups/groupLabels'
import { toast } from '@/features/notifications/toastStore'
import { newClientOrderId } from '../ticketState'
import { GroupReviewSheet } from './GroupReviewSheet'
import { groupErrorText } from './groupErrors'
import {
  type GroupField,
  type SecondaryType,
  buildGroupRequest,
  defaultPrices,
  entryReference,
  entryTypesFor,
  entryUsesLimit,
  entryUsesStop,
  groupInputFor,
  groupTicketReducer,
  hasEntry,
  hasPair,
  hasSecondary,
  initialGroupTicket,
  pctFrom,
  rewardToRisk,
  secondaryUsesLimit,
  secondaryUsesStop,
  submitLabel,
} from './groupTicketState'

function step(value: string, dir: 1 | -1, reference: Dec | null | undefined): string {
  const current = parseUserDecimal(value) ?? reference ?? null
  if (!current || !isDecimal(current)) return value
  const tick = tickFor(current)
  const next = dir === 1 ? add(current, tick) : sub(current, tick)
  return roundToTick(next.startsWith('-') ? tick : next)
}

function Card({ title, aside, children, testId }: { title: string; aside?: React.ReactNode; children: React.ReactNode; testId?: string }) {
  return (
    <section className="space-y-2 rounded-md border border-divider p-3" data-testid={testId} aria-label={title}>
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-caption font-semibold text-text-1">{title}</h3>
        {aside}
      </div>
      {children}
    </section>
  )
}

export function AdvancedTicket({ symbol, groups, confirmOrders, onDone }: { symbol: string; groups: readonly OrderGroupRule[]; confirmOrders: boolean; onDone?: () => void }) {
  const types = useMemo(() => groups.map((g) => g.type).filter((t): t is OrderGroupType => t != null), [groups])
  const [state, dispatch] = useReducer(groupTicketReducer, undefined, () => initialGroupTicket({ groupType: types.includes('BRACKET') ? 'BRACKET' : types[0] }))
  const [reviewing, setReviewing] = useState(false)
  const [submitErrors, setSubmitErrors] = useState<Record<string, string>>({})
  const [banner, setBanner] = useState<string | null>(null)
  const quote = useQuote(symbol, 'quote')
  const positions = usePositions()
  const position = positions.data?.find((p) => p.symbol === symbol)
  const place = usePlaceGroup()
  const t = state.groupType
  const entryTypes = entryTypesFor(t, groups)

  // prices default around the market once per group type and side (never re-filled after the user edits them)
  const defaulted = useRef('')
  const hasQuote = quote !== undefined
  useEffect(() => {
    const q = hasQuote ? useQuoteStore.getState().quotes[symbol] : undefined
    const key = `${t}:${state.ocoSide}`
    if (!q || defaulted.current === key) return
    defaulted.current = key
    const d = defaultPrices(t, state.ocoSide, q)
    for (const [field, value] of Object.entries(d)) if (value) dispatch({ type: 'field', field: field as GroupField, value })
  }, [symbol, t, state.ocoSide, hasQuote])

  const request = useMemo(() => buildGroupRequest(state, symbol), [state, symbol])
  const debounced = useDebounced(request, 250)
  const previewRequest = useMemo(() => (debounced ? { ...debounced, clientOrderId: null } : null), [debounced])
  const preview = useGroupPreview(previewRequest)
  const previewData = request ? preview.data : undefined
  const errors = useMemo(() => previewData?.errors ?? [], [previewData])
  const warnings = previewData?.warnings ?? []
  const fieldErrors = useMemo(() => {
    const out: Record<string, string> = {}
    for (const e of errors) {
      const input = groupInputFor(e.field)
      if (input) out[input] ??= groupErrorText(e)
    }
    return { ...out, ...submitErrors }
  }, [errors, submitErrors])
  const generalErrors = errors.filter((e) => !groupInputFor(e.field))
  const gfvRisk = warnings.some((w) => w.code === 'GFV_RISK')
  const stale = request !== debounced || preview.isFetching
  const canSubmit = Boolean(request && previewData?.valid && !stale && (!gfvRisk || state.gfvAck) && !place.isPending)
  const ref = entryReference(state, quote)
  const rr = hasEntry(t) && hasPair(t) ? rewardToRisk(ref, state.tpLimit, state.slStop) : null

  // stale submit errors go away once the user edits the ticket
  const editKey = JSON.stringify([state.groupType, state.ocoSide, state.entryType, state.entryLimit, state.entryStop, state.tpLimit, state.slStop, state.slLimit, state.slUseLimit, state.secondaryType, state.secondaryLimit, state.secondaryStop, state.qty, state.tif])
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
      toast({
        tone: 'neutral',
        title: `${GROUP_TYPE_LABEL[t]} ${res.replayed ? 'already placed' : 'placed'}: ${formatQty(request.qty)} ${symbol}`,
        body: t === 'OCO' ? 'Both orders are working; the first to execute cancels the other.' : 'The follow-up orders wait for the entry to fill.',
      })
      dispatch({ type: 'submitted', clientOrderId: newClientOrderId() })
      onDone?.()
    } catch (e) {
      setReviewing(false)
      const d = describeError(e)
      if (isApiError(e)) {
        if (e.code === 'CLIENT_ORDER_ID_CONFLICT' || e.code === 'CLIENT_ORDER_ID_INVALID') dispatch({ type: 'submitted', clientOrderId: newClientOrderId() })
        const fields: Record<string, string> = {}
        for (const f of e.problem.fields) {
          const input = groupInputFor(f.field)
          if (input) fields[input] ??= groupErrorText({ code: f.code ?? e.code, field: f.field, message: e.problem.detail ?? f.message })
        }
        setSubmitErrors(fields)
        if (Object.keys(fields).length === 0) setBanner(d.detail ?? d.title)
        toast({ tone: 'error', title: d.title, body: errorBody(d) })
      } else {
        // network failure: keep the same group id, so pressing again cannot place it twice (§11.7)
        setBanner(d.detail ?? d.title)
        toast({ tone: 'error', title: 'Order not confirmed', body: 'Check your connection and press the button again; it will not double the order.' })
      }
    }
  }

  const price = (field: GroupField, label: string, opts: { hint?: string | null; testId?: string } = {}) => (
    <div data-testid={opts.testId}>
      <Stepper
        label={label}
        value={state[field]}
        onChange={(v) => dispatch({ type: 'field', field, value: v })}
        onStep={(d) => dispatch({ type: 'field', field, value: step(state[field], d, quote?.last) })}
        error={fieldErrors[field]}
        placeholder="0.00"
      />
      {opts.hint ? <p className="mt-0.5 text-right text-micro text-text-3">{opts.hint}</p> : null}
    </div>
  )
  const fromEntry = (field: GroupField): string | null => {
    const pct = pctFrom(ref, state[field])
    return pct ? `${pct} from ${hasEntry(t) ? 'entry' : 'last'}` : null
  }
  const childSide = t === 'OCO' ? (state.ocoSide === 'BUY' ? 'Buy' : 'Sell') : 'Sell'

  return (
    <>
      <div className="space-y-3">
        <Segmented
          label="Advanced order type"
          block
          value={t}
          onChange={(groupType) => dispatch({ type: 'groupType', groupType })}
          options={types.map((g) => ({ value: g, label: GROUP_TYPE_LABEL[g], title: GROUP_TYPE_HELP[g] }))}
        />
        <p className="text-caption text-text-2">{GROUP_TYPE_HELP[t]}</p>

        {t === 'OCO' ? (
          <Segmented
            label="OCO side"
            block
            value={state.ocoSide}
            onChange={(side) => dispatch({ type: 'ocoSide', side })}
            options={[
              { value: 'SELL', label: 'Sell (protect)', tone: 'down', disabled: !position, title: position ? undefined : 'You hold no shares to protect' },
              { value: 'BUY', label: 'Buy', tone: 'up' },
            ]}
          />
        ) : null}

        {hasEntry(t) ? (
          <Card
            title={`${ROLE_LABEL.ENTRY} · Buy`}
            testId="group-entry"
            aside={
              <Segmented
                label="Entry type"
                size="sm"
                value={state.entryType}
                onChange={(entryType) => dispatch({ type: 'entryType', entryType })}
                options={entryTypes.map((e) => ({ value: e, label: ORDER_TYPE_LABEL[e] }))}
              />
            }
          >
            {entryUsesStop(state) ? price('entryStop', 'Entry stop') : null}
            {entryUsesLimit(state) ? price('entryLimit', 'Entry limit') : null}
            {fieldErrors.entryType ? <p className="text-caption text-down">{fieldErrors.entryType}</p> : null}
            {state.entryType === 'MARKET' ? <p className="text-caption text-text-3">Buys at the market (Day only); the legs below are measured from the ask {formatPrice(quote?.ask)}.</p> : null}
          </Card>
        ) : null}

        {hasPair(t) ? (
          <>
            <Card title={`${ROLE_LABEL.TAKE_PROFIT} · ${childSide} limit`} testId="group-take-profit">
              {price('tpLimit', 'Take profit', { hint: fromEntry('tpLimit') })}
            </Card>
            <Card
              title={`${ROLE_LABEL.STOP_LOSS} · ${childSide} ${state.slUseLimit ? 'stop limit' : 'stop'}`}
              testId="group-stop-loss"
              aside={
                <Checkbox checked={state.slUseLimit} onChange={(value) => dispatch({ type: 'slUseLimit', value })} className="text-caption text-text-2">
                  Stop limit
                </Checkbox>
              }
            >
              {price('slStop', 'Stop loss', { hint: fromEntry('slStop') })}
              {state.slUseLimit ? price('slLimit', 'Stop loss limit') : null}
            </Card>
          </>
        ) : null}

        {hasSecondary(t) ? (
          <Card
            title={`${ROLE_LABEL.SECONDARY} · Sell`}
            testId="group-secondary"
            aside={
              <Segmented
                label="Then order type"
                size="sm"
                value={state.secondaryType}
                onChange={(secondaryType: SecondaryType) => dispatch({ type: 'secondaryType', secondaryType })}
                options={(['LIMIT', 'STOP', 'STOP_LIMIT'] as const).map((e) => ({ value: e, label: ORDER_TYPE_LABEL[e] }))}
              />
            }
          >
            {secondaryUsesStop(state) ? price('secondaryStop', 'Then stop', { hint: fromEntry('secondaryStop') }) : null}
            {secondaryUsesLimit(state) ? price('secondaryLimit', 'Then limit', { hint: fromEntry('secondaryLimit') }) : null}
          </Card>
        ) : null}

        <div>
          <Stepper
            label="Shares (every leg)"
            value={state.qty}
            inputMode="numeric"
            onChange={(v) => dispatch({ type: 'field', field: 'qty', value: v })}
            onStep={(d) => {
              const current = parseUserDecimal(state.qty) ?? '0'
              const next = d === 1 ? add(current, '1') : sub(current, '1')
              dispatch({ type: 'field', field: 'qty', value: next.startsWith('-') || next === '0' ? '' : next })
            }}
            error={fieldErrors.qty}
            placeholder="0"
          />
          <p className="mt-1 flex justify-between text-caption text-text-3">
            <span>Whole shares only</span>
            {t === 'OCO' && state.ocoSide === 'SELL' ? (
              <span>
                Available <span className="font-semibold text-text-1">{formatQty(position?.availableQty)}</span> sh
              </span>
            ) : null}
          </p>
        </div>

        <div className="flex items-center justify-between gap-3">
          <span className="text-caption font-medium text-text-2">Time in force</span>
          <Segmented
            label="Group time in force"
            size="sm"
            value={state.tif}
            onChange={(tif) => dispatch({ type: 'tif', tif })}
            options={[
              { value: 'DAY', label: 'Day' },
              { value: 'GTC', label: 'GTC', title: 'Good till cancelled (60 days); a market entry is still Day' },
            ]}
          />
        </div>
        <Checkbox checked={state.doNotReduce} onChange={(value) => dispatch({ type: 'doNotReduce', value })} className="text-caption text-text-2">
          Don't reduce on dividends (keep my prices on ex-dividend dates)
        </Checkbox>

        <div className="rounded-md bg-bg-2/60 px-3 py-2 text-table" aria-live="polite" data-testid="group-preview">
          {!request ? (
            <p className="py-1 text-caption text-text-3">Enter the shares and every price to see the estimate.</p>
          ) : previewData ? (
            <dl>
              {(previewData.legs ?? []).map((l) => (
                <div key={l.role} className="flex justify-between py-0.5">
                  <dt className="text-text-2">
                    {l.role ? ROLE_LABEL[l.role] : ''} · {l.side === 'BUY' ? 'Buy' : 'Sell'} {l.type ? ORDER_TYPE_LABEL[l.type] : ''}
                  </dt>
                  <dd className="text-text-1">≈ {formatPrice(l.estimate?.price)}</dd>
                </div>
              ))}
              {rr ? (
                <div className="flex justify-between py-0.5">
                  <dt className="text-text-2">Risk : reward</dt>
                  <dd className="font-semibold text-text-1">{rr}</dd>
                </div>
              ) : null}
              <div className="flex justify-between py-0.5">
                <dt className="text-text-2">Buying power</dt>
                <dd className="font-semibold text-text-1">
                  {formatMoney(previewData.buyingPowerBefore)} <span className="text-text-3">→</span> {formatMoney(previewData.buyingPowerAfter)}
                </dd>
              </div>
              {previewData.sessionNote ? <p className="pt-1 text-caption text-session-ext">{previewData.sessionNote}</p> : null}
            </dl>
          ) : (
            <p className="py-1 text-caption text-text-3">Checking…</p>
          )}
        </div>

        {generalErrors.map((e) => (
          <div key={`${e.code}-${e.message}`} role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
            <p>{groupErrorText(e)}</p>
            {e.hint ? <p className="text-text-2">{e.hint}</p> : null}
          </div>
        ))}
        {banner ? (
          <div role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
            {banner}
          </div>
        ) : null}
        {warnings
          .filter((w) => w.code !== 'GFV_RISK')
          .map((w) => (
            <p key={`${w.code}-${w.message}`} className="flex gap-1.5 rounded-sm bg-warn/10 p-2 text-caption text-warn">
              <IconInfo size={16} className="mt-0.5 shrink-0" />
              {warningText(w)}
            </p>
          ))}
        {gfvRisk ? (
          <div className="rounded-sm border border-warn/40 bg-warn/10 p-2">
            <p className="mb-1.5 text-caption text-warn">{warnings.find((w) => w.code === 'GFV_RISK')?.message}</p>
            <Checkbox checked={state.gfvAck} onChange={(value) => dispatch({ type: 'gfvAck', value })}>
              I understand this may cause a good-faith violation
            </Checkbox>
          </div>
        ) : null}

        <div>
          <label htmlFor="group-note" className="mb-1 block text-caption font-medium text-text-2">
            Note <span className="text-text-3">({state.note.length}/1000)</span>
          </label>
          <textarea
            id="group-note"
            value={state.note}
            maxLength={1000}
            rows={2}
            onChange={(e) => dispatch({ type: 'field', field: 'note', value: e.target.value })}
            placeholder="Why this trade? (private unless you share it)"
            className="w-full resize-none rounded-md border border-border bg-bg-2 p-2 text-body text-text-1 outline-none focus:border-accent"
          />
          {state.note.trim() ? (
            <Checkbox checked={state.shareNote} onChange={(value) => dispatch({ type: 'shareNote', value })} className="mt-1 text-text-2">
              Share note publicly (shown with your delayed trades)
            </Checkbox>
          ) : null}
        </div>
      </div>

      <div className="sticky bottom-0 z-10 -mx-4 mt-3 border-t border-divider bg-bg-1 px-4 py-3">
        <Button variant="primary" size="lg" block disabled={!canSubmit} onClick={() => (confirmOrders ? setReviewing(true) : void submit())}>
          {place.isPending ? 'Sending…' : submitLabel(t, symbol)}
        </Button>
      </div>

      {request && previewData ? (
        <GroupReviewSheet open={reviewing} onClose={() => setReviewing(false)} onConfirm={() => void submit()} pending={place.isPending} request={request} preview={previewData} rewardToRisk={rr} />
      ) : null}
    </>
  )
}
