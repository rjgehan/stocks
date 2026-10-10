import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { describeError, errorBody } from '@/lib/api/errors'
import { type CorporateActionFilter, useCorporateActions, useResolveCorporateAction } from '@/lib/api/hooks/admin'
import { isApiError } from '@/lib/api/problem'
import type { CorporateAction } from '@/lib/api/types'
import { stripTrailingZeros } from '@/lib/format/decimal'
import { formatDateEt, formatDateTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { EmptyState } from '@/components/ui/EmptyState'
import { TextAreaField, TextField } from '@/components/ui/Field'
import { Pill } from '@/components/ui/Pill'
import { Segmented } from '@/components/ui/Segmented'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { IconInfo, IconShield } from '@/components/ui/icons'
import { toast } from '@/features/notifications/toastStore'
import { CA_ORIGIN_LABEL, CA_STATUS_LABEL, CA_STATUS_TONE, CA_TYPE_LABEL, caSummary, isSplit, splitEffect, splitKind, validRate } from './corporateActionLabels'

const FILTERS: ReadonlyArray<{ value: CorporateActionFilter; label: string }> = [
  { value: 'NEEDS_ADMIN', label: 'Needs you' },
  { value: 'PENDING', label: 'Upcoming' },
  { value: 'APPLIED', label: 'Applied' },
  { value: 'SKIPPED', label: 'Skipped' },
  { value: 'ALL', label: 'All' },
]

/** The dates that matter for the type: ex-date always, payable for dividends. */
function dates(c: CorporateAction): string {
  const parts = [`ex ${formatDateEt(c.exDate)}`]
  if (c.type === 'CASH_DIVIDEND' && c.payableDate) parts.push(`pays ${formatDateEt(c.payableDate)}`)
  return parts.join(' · ')
}

function ActionRow({ c, onResolve }: { c: CorporateAction; onResolve: (c: CorporateAction) => void }) {
  const needs = c.status === 'NEEDS_ADMIN'
  return (
    <li className="flex flex-col gap-2 px-4 py-3 tablet:flex-row tablet:items-start tablet:gap-4">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-1.5">
          <Link to="/stock/$symbol" params={{ symbol: c.newSymbol && c.status === 'APPLIED' ? c.newSymbol : (c.symbol ?? '') }} className="text-body font-bold hover:text-accent">
            {c.symbol}
          </Link>
          <span className="text-body font-semibold text-text-1">{caSummary(c)}</span>
          {c.status ? <Pill tone={CA_STATUS_TONE[c.status]}>{CA_STATUS_LABEL[c.status]}</Pill> : null}
          {c.origin === 'DETECTED' ? (
            <Pill tone="neutral" title={CA_ORIGIN_LABEL.DETECTED}>
              Detected
            </Pill>
          ) : null}
        </div>
        <p className="mt-0.5 text-caption text-text-3">
          {c.type ? CA_TYPE_LABEL[c.type] : 'Corporate action'} · {dates(c)}
          {c.appliedAt ? ` · applied ${formatDateTimeEt(c.appliedAt)}` : ''}
          {c.resolvedBy ? ` · by @${c.resolvedBy}` : ''}
        </p>
        {c.detail ? <p className="mt-1 rounded-sm bg-bg-2 px-2 py-1 text-caption text-text-2">{c.detail}</p> : null}
      </div>
      {needs ? (
        <Button size="sm" variant="primary" className="self-start" onClick={() => onResolve(c)} aria-label={`Resolve ${c.symbol} ${caSummary(c)}`}>
          Resolve…
        </Button>
      ) : null}
    </li>
  )
}

/**
 * Resolve one NEEDS_ADMIN action (§14 split guard, §21.1 `POST /admin/corporate-actions/{id}/resolve`, `@RecentAuth`):
 * APPLY a split with confirmed rates (applied as of now: positions adjusted, open orders on the symbol cancelled, cash
 * in lieu paid), or SKIP it to release the corporate-action hold. Phase 1 applies forward and reverse splits only.
 */
export function ResolveDialog({ action, onClose }: { action: CorporateAction | null; onClose: () => void }) {
  const resolve = useResolveCorporateAction()
  const split = isSplit(action?.type)
  const [resolution, setResolution] = useState<'APPLY' | 'SKIP'>(split ? 'APPLY' : 'SKIP')
  const [oldRate, setOldRate] = useState(action?.oldRate ? stripTrailingZeros(action.oldRate) : '')
  const [newRate, setNewRate] = useState(action?.newRate ? stripTrailingZeros(action.newRate) : '')
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  if (!action) return null
  const symbol = action.symbol ?? ''
  const apply = resolution === 'APPLY'
  const ratesOk = !apply || (validRate(oldRate) && validRate(newRate) && splitKind(oldRate, newRate) !== null)
  const noteOk = note.trim().length >= 1 && note.trim().length <= 200
  const ready = ratesOk && noteOk && !resolve.isPending
  const effect = apply ? splitEffect(symbol, oldRate, newRate) : null

  const submit = () => {
    if (!ready || !action.id) return
    setError(null)
    setFieldErrors({})
    resolve.mutate(
      { id: action.id, body: apply ? { resolution: 'APPLY', oldRate: oldRate.trim(), newRate: newRate.trim(), note: note.trim() } : { resolution: 'SKIP', note: note.trim() } },
      {
        onSuccess: (c) => {
          const confirmedOnly = apply && c.status === 'PENDING'
          toast({
            tone: apply ? 'up' : 'neutral',
            title: confirmedOnly
              ? `${symbol} ${caSummary(c)} confirmed`
              : apply
                ? `${symbol} ${caSummary(c)} applied`
                : `${symbol} ${CA_TYPE_LABEL[c.type ?? 'FORWARD_SPLIT'].toLowerCase()} skipped`,
            body: confirmedOnly
              ? `It applies at 03:45 ET on its ex-date${c.exDate ? `, ${formatDateEt(c.exDate)}` : ''}.`
              : apply
                ? 'Positions were adjusted and open orders on the symbol cancelled.'
                : 'The corporate-action hold is released.',
          })
          onClose()
        },
        onError: (e) => {
          const d = describeError(e)
          if (isApiError(e) && e.problem.fields.length) setFieldErrors(Object.fromEntries(e.problem.fields.map((f) => [f.field, f.message])))
          setError(errorBody(d) ? `${d.title}: ${errorBody(d)}` : d.title)
        },
      },
    )
  }

  return (
    <Dialog
      open
      onClose={onClose}
      title={`Resolve ${symbol} ${action.type ? CA_TYPE_LABEL[action.type].toLowerCase() : ''}`}
      footer={
        <div className="flex gap-2">
          <Button variant="secondary" size="lg" className="flex-1" onClick={onClose}>
            Cancel
          </Button>
          <Button variant={apply ? 'primary' : 'secondary'} size="lg" className="flex-[2]" disabled={!ready} onClick={submit}>
            {resolve.isPending ? 'Saving…' : apply ? `Apply ${splitKind(oldRate, newRate) ? `${stripTrailingZeros(newRate.trim())}-for-${stripTrailingZeros(oldRate.trim())}` : 'split'}` : 'Skip it'}
          </Button>
        </div>
      }
    >
      <div className="space-y-3 p-4 text-body">
        <div className="rounded-md bg-bg-2 p-3 text-caption text-text-2">
          <p className="font-semibold text-text-1">
            {symbol} · {caSummary(action)}
          </p>
          <p>
            {dates(action)} · {action.origin ? (CA_ORIGIN_LABEL[action.origin] ?? action.origin) : ''}
          </p>
          {action.detail ? <p className="mt-1">{action.detail}</p> : null}
        </div>
        <Segmented
          label="Resolution"
          block
          value={resolution}
          onChange={setResolution}
          options={[
            { value: 'APPLY', label: 'Apply', disabled: !split, title: split ? 'Apply the split now with the rates below' : 'Phase 1 applies forward and reverse splits only' },
            { value: 'SKIP', label: 'Skip' },
          ]}
        />
        {apply ? (
          <>
            <div className="grid grid-cols-2 gap-3">
              <TextField
                label="Old shares"
                inputMode="decimal"
                value={oldRate}
                onChange={(e) => setOldRate(e.target.value)}
                error={fieldErrors.oldRate ?? (oldRate && !validRate(oldRate) ? 'A positive number' : null)}
                hint="e.g. 1 for 2-for-1"
              />
              <TextField
                label="New shares"
                inputMode="decimal"
                value={newRate}
                onChange={(e) => setNewRate(e.target.value)}
                error={fieldErrors.newRate ?? (newRate && !validRate(newRate) ? 'A positive number' : null)}
                hint="e.g. 2 for 2-for-1"
              />
            </div>
            {validRate(oldRate) && validRate(newRate) && !splitKind(oldRate, newRate) ? <p className="text-caption text-down">Old and new share counts are equal: that is not a split.</p> : null}
            {effect ? <p className="rounded-sm bg-accent-bg px-2 py-1.5 text-caption text-text-1">{effect}</p> : null}
            <p className="flex gap-1.5 rounded-sm bg-warn/10 p-2 text-caption text-warn">
              <IconInfo size={16} className="mt-0.5 shrink-0" />
              Applies as of now to the {symbol} shares each account held at the ex-date ({action.exDate ? formatDateEt(action.exDate) : 'its ex-date'}); shares bought since are not changed. Open {symbol} orders entered before the ex-date are cancelled and players are notified. This cannot be undone. Before the ex-date it only confirms the rates: the split then applies at 03:45 ET on the ex-date.
            </p>
          </>
        ) : (
          <p className="text-caption text-text-2">
            {split
              ? `Skipping releases the corporate-action hold: ${symbol} trades again at unadjusted quantities. Do this only if the split did not happen.`
              : 'Skipping dismisses this action. Mergers, spin-offs and delistings are handled by hand in Phase 2.'}
          </p>
        )}
        <TextAreaField
          label={`Note (required, audited) ${note.trim().length}/200`}
          rows={2}
          maxLength={200}
          value={note}
          onChange={(e) => setNote(e.target.value)}
          error={fieldErrors.note}
          hint={noteOk ? undefined : `A note is required to ${apply ? 'apply' : 'skip'} it; it goes into the audit log.`}
          placeholder={apply ? 'e.g. Confirmed 2-for-1 on the issuer press release' : 'e.g. False positive from the adjusted bars'}
        />
        <p className="flex items-center gap-1.5 text-micro text-text-3">
          <IconShield size={14} /> You may be asked for your password (recent sign-in required).
        </p>
        {error ? (
          <p role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
            {error}
          </p>
        ) : null}
      </div>
    </Dialog>
  )
}

/** Admin corporate actions (§14, §21.1, §21.3): newest first, filtered by status; NEEDS_ADMIN rows resolve here. */
export function CorporateActions({ filter = 'NEEDS_ADMIN', onFilterChange }: { filter?: CorporateActionFilter; onFilterChange: (f: CorporateActionFilter) => void }) {
  const [resolving, setResolving] = useState<CorporateAction | null>(null)
  const q = useCorporateActions(filter)
  const rows = q.data?.pages.flatMap((p) => p.items ?? []) ?? []
  return (
    <section className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-section font-semibold">Corporate actions</h2>
        <div className="flex-1" />
        <div className="scrollbar-none -mx-4 w-[calc(100%+2rem)] overflow-x-auto px-4 tablet:mx-0 tablet:w-auto tablet:px-0">
          <Segmented label="Corporate action status" size="sm" value={filter} onChange={onFilterChange} options={FILTERS} />
        </div>
      </div>
      <p className="text-caption text-text-3">
        Splits, reverse splits, dividends and symbol changes apply automatically at 03:45 ET on the ex-date. A split the price feed detected without a report, or any merger, spin-off or
        delisting, waits here; while a split waits, the symbol is on hold (no trading, positions valued at the last close).
      </p>
      <div className="rounded-lg border border-divider bg-bg-1">
        {q.isPending ? (
          <div className="px-4">
            <SkeletonRows rows={4} cols={3} />
          </div>
        ) : q.isError ? (
          <EmptyState title="Corporate actions are unavailable" body={describeError(q.error).title} />
        ) : rows.length === 0 ? (
          <EmptyState title={filter === 'NEEDS_ADMIN' ? 'Nothing needs you' : 'No corporate actions'} body={filter === 'NEEDS_ADMIN' ? 'Detected splits and mergers show up here for a decision.' : undefined} />
        ) : (
          <ul className="divide-y divide-divider" aria-label="Corporate actions">
            {rows.map((c) => (
              <ActionRow key={c.id} c={c} onResolve={setResolving} />
            ))}
          </ul>
        )}
        {q.hasNextPage ? (
          <div className="flex justify-center border-t border-divider p-3">
            <Button size="sm" onClick={() => void q.fetchNextPage()} disabled={q.isFetchingNextPage}>
              {q.isFetchingNextPage ? 'Loading…' : 'Load more'}
            </Button>
          </div>
        ) : null}
      </div>
      {resolving ? <ResolveDialog key={resolving.id} action={resolving} onClose={() => setResolving(null)} /> : null}
    </section>
  )
}
