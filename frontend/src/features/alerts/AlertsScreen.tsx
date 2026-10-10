import { useState } from 'react'
import { describeError, errorBody } from '@/lib/api/errors'
import { useAlerts, useUpdateAlert } from '@/lib/api/hooks/alerts'
import type { Alert, AlertStatus } from '@/lib/api/types'
import { useQuote } from '@/lib/realtime/useQuote'
import { useBreakpoint } from '@/app/useBreakpoint'
import { EmptyState } from '@/components/ui/EmptyState'
import { Sheet } from '@/components/ui/Sheet'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { Tabs } from '@/components/ui/Tabs'
import { IconBell } from '@/components/ui/icons'
import { toast } from '@/features/notifications/toastStore'
import { AlertForm } from './AlertForm'
import { AlertRow } from './AlertRow'
import { describeCondition, liveView, sortAlerts } from './alertLogic'

export type AlertFilter = 'ALL' | AlertStatus

const FILTERS: ReadonlyArray<{ value: AlertFilter; label: string }> = [
  { value: 'ALL', label: 'All' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'PAUSED', label: 'Paused' },
  { value: 'TRIGGERED', label: 'Fired' },
  { value: 'EXPIRED', label: 'Expired' },
]

/** `/alerts` (§22.2, §22.10): every alert by status, with edit, pause/resume and delete. */
export function AlertsScreen({ filter, onFilter }: { filter: AlertFilter; onFilter: (f: AlertFilter) => void }) {
  const alerts = useAlerts()
  const [editing, setEditing] = useState<Alert | null>(null)
  const list = sortAlerts(alerts.data ?? []).filter((a) => filter === 'ALL' || a.status === filter)
  const activeCount = (alerts.data ?? []).filter((a) => a.status === 'ACTIVE' || a.status === 'PAUSED').length

  return (
    <div className="space-y-3">
      <p className="text-caption text-text-3">
        You get a notification (and a push on devices where you turned it on) when an alert fires. {activeCount}/50 active or paused.
      </p>
      <Tabs label="Alert status" value={filter} onChange={onFilter} items={FILTERS.map((f) => ({ value: f.value, label: f.label }))} />
      {alerts.isPending ? (
        <SkeletonRows rows={4} cols={2} />
      ) : alerts.isError ? (
        <EmptyState title="Alerts are unavailable right now" body={errorBody(describeError(alerts.error)) ?? 'Try again in a moment.'} />
      ) : list.length === 0 ? (
        <EmptyState
          icon={<IconBell size={28} />}
          title={filter === 'ALL' ? 'No alerts yet' : 'Nothing here'}
          body="Open a stock and tap the bell to get notified when it reaches a price or moves a percentage on the day."
        />
      ) : (
        <ul className="divide-y divide-divider overflow-hidden rounded-lg border border-divider bg-bg-1" aria-label="Alerts">
          {list.map((a) => (
            <AlertRow key={a.id} alert={a} onEdit={setEditing} />
          ))}
        </ul>
      )}
      {editing ? <EditSheet alert={editing} onClose={() => setEditing(null)} /> : null}
    </div>
  )
}

function EditSheet({ alert, onClose }: { alert: Alert; onClose: () => void }) {
  const bp = useBreakpoint()
  const symbol = alert.symbol ?? ''
  const quote = useQuote(symbol, 'quote')
  const update = useUpdateAlert()
  const err = update.error ? describeError(update.error) : null
  return (
    <Sheet open onClose={onClose} side={bp === 'phone' ? 'bottom' : 'right'} title={`Edit ${symbol} alert`}>
      <div className="p-4">
        <AlertForm
          symbol={symbol}
          live={liveView(quote)}
          alert={alert}
          pending={update.isPending}
          error={err ? (errorBody(err) ?? err.title) : null}
          submitLabel={alert.status === 'TRIGGERED' || alert.status === 'EXPIRED' ? 'Save and turn on' : 'Save alert'}
          onCancel={onClose}
          onSubmit={(body) =>
            update.mutate(
              { id: alert.id ?? '', body },
              {
                onSuccess: (a) => {
                  toast({ tone: 'neutral', title: `Alert saved: ${symbol} ${describeCondition(a.condition, a.threshold)}` })
                  onClose()
                },
              },
            )
          }
        />
      </div>
    </Sheet>
  )
}
