import { useState } from 'react'
import type { Alert, AlertCondition, CreateAlertRequest } from '@/lib/api/types'
import { compare, isDecimal } from '@/lib/format/decimal'
import { formatPct, formatPrice } from '@/lib/format/number'
import { formatDateTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { SelectField, Switch, TextField } from '@/components/ui/Field'
import { Segmented } from '@/components/ui/Segmented'
import {
  type ExpiryPreset,
  EXPIRY_PRESETS,
  type LiveView,
  NOTE_MAX,
  defaultInput,
  expiryFor,
  fromWireThreshold,
  isAbove,
  isPrice,
  thresholdError,
  toWireThreshold,
  wouldFireNow,
} from './alertLogic'

type Kind = 'PRICE' | 'CHANGE'
type Dir = 'GTE' | 'LTE'

const conditionOf = (kind: Kind, dir: Dir): AlertCondition => (kind === 'PRICE' ? `PRICE_${dir}` : `CHANGE_PCT_${dir}`) as AlertCondition

export interface AlertFormProps {
  symbol: string
  live: LiveView
  /** Editing this alert; otherwise a new one. */
  alert?: Alert | null
  pending?: boolean
  error?: string | null
  submitLabel: string
  onSubmit: (body: Omit<CreateAlertRequest, 'symbol'>) => void
  onCancel?: () => void
  /** The symbol's other alerts: a live one with the same condition and threshold blocks a duplicate. */
  existing?: readonly Alert[]
}

/** A live (active or paused) alert other than `self` with the same condition and threshold, if any. */
export function sameAlert(existing: readonly Alert[] | undefined, self: Alert | null | undefined, condition: AlertCondition, wire: string | null): Alert | null {
  if (wire === null || !isDecimal(wire)) return null
  return (
    (existing ?? []).find(
      (a) =>
        a.id !== self?.id &&
        (a.status === 'ACTIVE' || a.status === 'PAUSED') &&
        a.condition === condition &&
        typeof a.threshold === 'string' &&
        isDecimal(a.threshold) &&
        compare(a.threshold, wire) === 0,
    ) ?? null
  )
}

/**
 * The alert sheet's form (§22.10): condition (price or day change, at/above or at/below), threshold (prefilled from
 * the live price, or ±5% for a change; percent here, a fraction on the wire), repeat, expiry and a private note. It
 * says up front when the condition already holds, i.e. the alert would fire on the next live price. The default
 * threshold is taken from the live price once, when the form opens (it does not follow every tick), and a live alert
 * with the same condition and threshold blocks a duplicate.
 */
export function AlertForm({ symbol, live, alert, pending, error, submitLabel, onSubmit, onCancel, existing }: AlertFormProps) {
  const initialCondition: AlertCondition = alert?.condition ?? 'PRICE_GTE'
  const [kind, setKind] = useState<Kind>(isPrice(initialCondition) ? 'PRICE' : 'CHANGE')
  const [dir, setDir] = useState<Dir>(isAbove(initialCondition) ? 'GTE' : 'LTE')
  const condition = conditionOf(kind, dir)
  const [inputs, setInputs] = useState<Partial<Record<AlertCondition, string>>>(() =>
    alert?.condition ? { [alert.condition]: fromWireThreshold(alert.condition, alert.threshold) } : {},
  )
  // the live line the defaults come from, frozen at the first price (review finding: it followed every tick)
  const [base, setBase] = useState<LiveView | null>(() => (live.price ? live : null))
  if (!base && live.price) setBase(live) // the first price arrived after the form opened (render-time adjustment)
  const input = inputs[condition] ?? defaultInput(condition, base ?? live)
  const [repeat, setRepeat] = useState(alert?.repeat ?? false)
  const [expiry, setExpiry] = useState<ExpiryPreset | 'KEEP'>(alert?.expiresAt ? 'KEEP' : 'NEVER')
  const [note, setNote] = useState(alert?.note ?? '')
  const [touched, setTouched] = useState(false)

  const wire = toWireThreshold(condition, input)
  const problem = thresholdError(condition, input)
  const firesNow = !problem && wouldFireNow(condition, wire, live)
  const duplicate = problem ? null : sameAlert(existing, alert, condition, wire)
  const expiryOptions = [
    ...(alert?.expiresAt ? [{ value: 'KEEP', label: `Keep (${formatDateTimeEt(alert.expiresAt)})` }] : []),
    ...EXPIRY_PRESETS.map((p) => ({ value: p.value, label: p.label })),
  ]

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    setTouched(true)
    if (problem || wire === null || duplicate) return
    onSubmit({
      condition,
      threshold: wire,
      repeat,
      expiresAt: expiry === 'KEEP' ? (alert?.expiresAt ?? null) : expiryFor(expiry, new Date()),
      note: note.trim() === '' ? null : note.trim(),
    })
  }

  const now = live.price
    ? `${symbol} is at $${formatPrice(live.price)}${live.changePct ? ` (${formatPct(live.changePct)} today)` : ''}`
    : `No live price for ${symbol} right now`

  return (
    <form onSubmit={submit} className="space-y-4" aria-label={alert ? 'Edit alert' : 'New alert'} noValidate>
      <p className="text-caption text-text-2" data-testid="alert-live">
        {now}
      </p>
      <div className="grid gap-3">
        <div>
          <div className="mb-1 text-caption font-medium text-text-2">Alert on</div>
          <Segmented
            label="Alert on"
            block
            value={kind}
            onChange={setKind}
            options={[
              { value: 'PRICE', label: 'Price' },
              { value: 'CHANGE', label: 'Day change' },
            ]}
          />
        </div>
        <div>
          <div className="mb-1 text-caption font-medium text-text-2">When it is</div>
          <Segmented
            label="When it is"
            block
            value={dir}
            onChange={setDir}
            options={[
              { value: 'GTE', label: 'At or above' },
              { value: 'LTE', label: 'At or below' },
            ]}
          />
        </div>
      </div>
      <TextField
        label={kind === 'PRICE' ? 'Price' : 'Day change (%)'}
        prefix={kind === 'PRICE' ? '$' : undefined}
        inputMode="decimal"
        autoComplete="off"
        value={input}
        onChange={(e) => setInputs((m) => ({ ...m, [condition]: e.target.value }))}
        onBlur={() => setTouched(true)}
        error={touched ? problem : null}
        hint={kind === 'CHANGE' ? 'From the previous close, e.g. 5 for +5% or -3 for −3%.' : undefined}
      />
      {duplicate ? (
        <p role="status" className="rounded-md border border-divider bg-bg-2 px-3 py-2 text-caption text-text-2">
          You already have this alert{duplicate.status === 'PAUSED' ? ' (paused)' : ''}.
        </p>
      ) : null}
      {firesNow ? (
        <p role="status" className="rounded-md border border-warn/40 bg-warn/10 px-3 py-2 text-caption text-text-1">
          The condition already holds: this alert fires on the next live price.
        </p>
      ) : null}
      <Switch
        checked={repeat}
        onChange={setRepeat}
        label="Repeat"
        description="Fire again after the price moves back 0.5% (at most every 15 minutes). Off: fire once."
      />
      <SelectField
        label="Expires"
        value={expiry}
        onChange={(e) => setExpiry(e.target.value as ExpiryPreset | 'KEEP')}
        options={expiryOptions}
      />
      <TextField
        label="Note (private)"
        maxLength={NOTE_MAX}
        value={note}
        onChange={(e) => setNote(e.target.value)}
        placeholder="Why this level matters"
        hint={`${note.length}/${NOTE_MAX}`}
      />
      {error ? (
        <p role="alert" className="text-caption text-down">
          {error}
        </p>
      ) : null}
      <div className="flex gap-2">
        {onCancel ? (
          <Button variant="secondary" onClick={onCancel} className="flex-1">
            Cancel
          </Button>
        ) : null}
        <Button variant="primary" type="submit" disabled={pending || Boolean(duplicate)} className="flex-1">
          {pending ? 'Saving…' : submitLabel}
        </Button>
      </div>
      <p className="text-micro text-text-3">Alerts only notify you; they never place or change an order. Live prices only (never stale or estimated quotes).</p>
    </form>
  )
}
