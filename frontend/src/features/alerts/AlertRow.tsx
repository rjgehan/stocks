import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { describeError, errorBody } from '@/lib/api/errors'
import { useDeleteAlert, usePauseAlert } from '@/lib/api/hooks/alerts'
import type { Alert } from '@/lib/api/types'
import { formatDateTimeEt } from '@/lib/format/time'
import { IconButton } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { IconEdit, IconTrash } from '@/components/ui/icons'
import { Pill } from '@/components/ui/Pill'
import { cx } from '@/components/ui/cx'
import { toast } from '@/features/notifications/toastStore'
import { describeCondition, statusLine } from './alertLogic'

function onError(title: string) {
  return (e: unknown) => {
    const d = describeError(e)
    toast({ tone: 'error', title, body: errorBody(d) ?? d.title })
  }
}

/** One alert: what it watches, its state, and pause/resume, edit and delete (after a confirmation) (§22.10). */
export function AlertRow({ alert, showSymbol = true, onEdit }: { alert: Alert; showSymbol?: boolean; onEdit?: (a: Alert) => void }) {
  const pause = usePauseAlert()
  const remove = useDeleteAlert()
  const [confirming, setConfirming] = useState(false)
  const what = `${alert.symbol ?? ''} ${describeCondition(alert.condition, alert.threshold)}`.trim()
  const id = alert.id ?? ''
  const live = alert.status === 'ACTIVE' || alert.status === 'PAUSED'
  const tone = alert.status === 'ACTIVE' ? 'accent' : alert.status === 'TRIGGERED' ? 'up' : 'neutral'
  return (
    <li className="flex items-center gap-3 px-4 py-3" data-testid="alert-row" data-alert-id={id}>
      <div className={cx('min-w-0 flex-1', !live && 'opacity-80')}>
        <div className="flex flex-wrap items-center gap-2">
          {showSymbol && alert.symbol ? (
            <Link to="/stock/$symbol" params={{ symbol: alert.symbol }} className="font-semibold text-text-1 hover:text-accent">
              {alert.symbol}
            </Link>
          ) : null}
          <span className="font-medium tabular-nums">{describeCondition(alert.condition, alert.threshold)}</span>
          <Pill tone={tone}>{alert.status === 'TRIGGERED' ? 'Fired' : (alert.status ?? '').toLowerCase()}</Pill>
          {alert.repeat ? <Pill tone="neutral">repeat</Pill> : null}
        </div>
        <div className="truncate text-caption text-text-3">
          {statusLine(alert)}
          {alert.lastTriggeredAt ? ` · ${formatDateTimeEt(alert.lastTriggeredAt)}` : ''}
          {alert.expiresAt && live ? ` · expires ${formatDateTimeEt(alert.expiresAt)}` : ''}
          {alert.note ? ` · ${alert.note}` : ''}
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-1">
        {live ? (
          <button
            type="button"
            className="h-9 rounded-md px-2 text-caption font-semibold text-accent hover:bg-accent-bg disabled:opacity-50"
            disabled={pause.isPending}
            onClick={() => pause.mutate({ id, pause: alert.status === 'ACTIVE' }, { onError: onError('Alert not updated') })}
          >
            {alert.status === 'ACTIVE' ? 'Pause' : 'Resume'}
          </button>
        ) : null}
        {onEdit ? (
          <IconButton label={live ? 'Edit alert' : 'Edit and turn on again'} onClick={() => onEdit(alert)}>
            <IconEdit size={18} />
          </IconButton>
        ) : null}
        <IconButton label="Delete alert" onClick={() => setConfirming(true)}>
          <IconTrash size={18} />
        </IconButton>
      </div>
      <ConfirmDialog
        open={confirming}
        onClose={() => setConfirming(false)}
        title="Delete this alert?"
        confirmLabel="Delete"
        tone="danger"
        pending={remove.isPending}
        onConfirm={() => {
          setConfirming(false)
          remove.mutate(id, { onError: onError('Alert not deleted'), onSuccess: () => toast({ tone: 'neutral', title: 'Alert deleted' }) })
        }}
      >
        {what}. It stops watching the price; this cannot be undone.
      </ConfirmDialog>
    </li>
  )
}
