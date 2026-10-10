import { useState } from 'react'
import { describeError, errorBody } from '@/lib/api/errors'
import { isApiError } from '@/lib/api/problem'
import { useCalendarOverrides, useCreateCalendarOverride, useDeleteCalendarOverride } from '@/lib/api/hooks/admin'
import type { CalendarOverride } from '@/lib/api/types'
import { etDatePlusDays, etWallTimeToIso, formatDateEt, formatTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { TextField } from '@/components/ui/Field'
import { Pill } from '@/components/ui/Pill'
import { Segmented } from '@/components/ui/Segmented'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { toast } from '@/features/notifications/toastStore'

type Kind = 'CLOSED' | 'EARLY'

/**
 * Market calendar overrides (§21.3 System, §9.1 precedence: admin overrides beat the bundled calendar and the Alpaca
 * sync): close a day (e.g. a national day of mourning) or set an early close, with a reason. Times are entered in ET.
 */
export function CalendarOverrides() {
  const [now] = useState(() => Date.now())
  const from = etDatePlusDays(now, -7)
  const to = etDatePlusDays(now, 365)
  const list = useCalendarOverrides(from, to)
  const create = useCreateCalendarOverride()
  const remove = useDeleteCalendarOverride()
  const [date, setDate] = useState('')
  const [kind, setKind] = useState<Kind>('CLOSED')
  const [rthClose, setRthClose] = useState('13:00')
  const [postClose, setPostClose] = useState('17:00')
  const [reason, setReason] = useState('')
  const [deleting, setDeleting] = useState<CalendarOverride | null>(null)
  const [error, setError] = useState<string | null>(null)

  const rthIso = kind === 'EARLY' && date ? etWallTimeToIso(date, rthClose) : null
  const postIso = kind === 'EARLY' && date && postClose ? etWallTimeToIso(date, postClose) : null
  const ready = Boolean(date && reason.trim() && (kind === 'CLOSED' || rthIso)) && !create.isPending

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!ready) return
    setError(null)
    create.mutate(
      { date, closed: kind === 'CLOSED', rthCloseAt: rthIso, postCloseAt: postIso, reason: reason.trim() },
      {
        onSuccess: (o) => {
          toast({ tone: 'neutral', title: `${formatDateEt(o.date)}: ${o.closed ? 'market closed' : `early close ${formatTimeEt(o.rthCloseAt)}`}` })
          setDate('')
          setReason('')
        },
        onError: (err) => {
          const d = describeError(err)
          setError(isApiError(err) && err.problem.fields.length ? err.problem.fields.map((f) => f.message).join(' ') : (errorBody(d) ?? d.title))
        },
      },
    )
  }

  const rows = list.data ?? []
  return (
    <section className="rounded-lg border border-divider bg-bg-1 p-4" aria-label="Calendar overrides">
      <h2 className="text-section font-semibold">Market calendar overrides</h2>
      <p className="mb-3 text-caption text-text-2">Unplanned closures and early closes. They win over the bundled exchange calendar; orders and sessions follow them right away.</p>
      {list.isPending ? (
        <SkeletonRows rows={2} cols={3} />
      ) : list.isError ? (
        <p className="text-caption text-text-3">Overrides are unavailable right now.</p>
      ) : rows.length === 0 ? (
        <p className="text-caption text-text-3">No overrides in the coming year.</p>
      ) : (
        <ul className="mb-3 divide-y divide-divider" aria-label="Overrides">
          {rows.map((o) => (
            <li key={o.date} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2 text-table">
              <span className="font-semibold">{formatDateEt(o.date)}</span>
              {o.closed ? <Pill tone="down">Closed</Pill> : <Pill tone="warn">Early close {formatTimeEt(o.rthCloseAt)}</Pill>}
              <span className="min-w-0 flex-1 truncate text-caption text-text-2">
                {o.reason}
                {o.createdBy ? ` · @${o.createdBy}` : ''}
              </span>
              <Button size="sm" variant="danger" onClick={() => setDeleting(o)} aria-label={`Delete the override on ${formatDateEt(o.date)}`}>
                Delete
              </Button>
            </li>
          ))}
        </ul>
      )}
      <form onSubmit={submit} className="grid gap-3 border-t border-divider pt-3 tablet:grid-cols-2" aria-label="Add an override">
        <TextField label="Date (ET)" type="date" value={date} min={etDatePlusDays(now)} onChange={(e) => setDate(e.target.value)} />
        <div>
          <span className="mb-1 block text-caption font-medium text-text-2">Change</span>
          <Segmented
            label="Override kind"
            block
            value={kind}
            onChange={setKind}
            options={[
              { value: 'CLOSED', label: 'Closed all day' },
              { value: 'EARLY', label: 'Early close' },
            ]}
          />
        </div>
        {kind === 'EARLY' ? (
          <>
            <TextField label="Regular session closes (ET)" type="time" value={rthClose} onChange={(e) => setRthClose(e.target.value)} />
            <TextField label="After-hours close (ET)" type="time" value={postClose} onChange={(e) => setPostClose(e.target.value)} hint="Usually 4 hours after the regular close." />
          </>
        ) : null}
        <TextField className="tablet:col-span-2" label="Reason (audited)" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={200} placeholder="NYSE closed for a national day of mourning" />
        {error ? (
          <p role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down tablet:col-span-2">
            {error}
          </p>
        ) : null}
        <div className="tablet:col-span-2">
          <Button type="submit" variant="primary" disabled={!ready}>
            {create.isPending ? 'Saving…' : 'Add override'}
          </Button>
        </div>
      </form>
      <ConfirmDialog
        open={deleting !== null}
        onClose={() => setDeleting(null)}
        title={`Delete the override on ${formatDateEt(deleting?.date)}?`}
        tone="danger"
        confirmLabel="Delete"
        pending={remove.isPending}
        onConfirm={() =>
          deleting?.date &&
          remove.mutate(deleting.date, {
            onSuccess: () => {
              toast({ tone: 'neutral', title: 'Override deleted' })
              setDeleting(null)
            },
            onError: (err) => toast({ tone: 'error', title: 'Not deleted', body: errorBody(describeError(err)) }),
          })
        }
      >
        The day goes back to the regular exchange calendar.
      </ConfirmDialog>
    </section>
  )
}
