import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { describeError, errorBody } from '@/lib/api/errors'
import { useAlerts, useCreateAlert, useUpdateAlert } from '@/lib/api/hooks/alerts'
import type { Alert } from '@/lib/api/types'
import { useQuote } from '@/lib/realtime/useQuote'
import { useBreakpoint } from '@/app/useBreakpoint'
import { IconButton } from '@/components/ui/Button'
import { Sheet } from '@/components/ui/Sheet'
import { IconBell } from '@/components/ui/icons'
import { toast } from '@/features/notifications/toastStore'
import { AlertForm } from './AlertForm'
import { AlertRow } from './AlertRow'
import { describeCondition, liveView, sortAlerts } from './alertLogic'

/**
 * The quote page's alert sheet (§22.10): a new alert for this symbol (or the one being edited), and the symbol's other
 * alerts with pause/resume, edit and delete. A bottom sheet on phones, a right sheet elsewhere.
 */
export function AlertSheet({ symbol, open, onClose }: { symbol: string; open: boolean; onClose: () => void }) {
  const bp = useBreakpoint()
  const quote = useQuote(symbol, 'quote')
  const alerts = useAlerts()
  const create = useCreateAlert()
  const update = useUpdateAlert()
  const [editing, setEditing] = useState<Alert | null>(null)
  const mine = sortAlerts((alerts.data ?? []).filter((a) => a.symbol === symbol))
  const mutation = editing ? update : create
  const err = mutation.error ? describeError(mutation.error) : null

  const close = () => {
    setEditing(null)
    create.reset()
    update.reset()
    onClose()
  }

  return (
    <Sheet open={open} onClose={close} side={bp === 'phone' ? 'bottom' : 'right'} title={editing ? `Edit ${symbol} alert` : `New ${symbol} alert`}>
      <div className="p-4">
        <AlertForm
          key={editing?.id ?? 'new'}
          symbol={symbol}
          live={liveView(quote)}
          alert={editing}
          pending={mutation.isPending}
          error={err ? (errorBody(err) ?? err.title) : null}
          submitLabel={editing ? 'Save alert' : 'Create alert'}
          onCancel={editing ? () => setEditing(null) : undefined}
          onSubmit={(body) => {
            if (editing?.id) {
              update.mutate(
                { id: editing.id, body },
                {
                  onSuccess: (a) => {
                    toast({ tone: 'neutral', title: `Alert saved: ${symbol} ${describeCondition(a.condition, a.threshold)}` })
                    setEditing(null)
                  },
                },
              )
            } else {
              create.mutate(
                { symbol, ...body },
                { onSuccess: (a) => toast({ tone: 'neutral', title: `Alert set: ${symbol} ${describeCondition(a.condition, a.threshold)}` }) },
              )
            }
          }}
        />
      </div>
      <section className="border-t border-divider" aria-label={`${symbol} alerts`}>
        <div className="flex items-center justify-between px-4 pt-3 pb-1">
          <h3 className="text-caption font-semibold tracking-wide text-text-2 uppercase">Your {symbol} alerts</h3>
          <Link to="/alerts" className="text-caption font-semibold text-accent" onClick={close}>
            All alerts
          </Link>
        </div>
        {mine.length === 0 ? (
          <p className="px-4 pb-4 text-caption text-text-3">None yet.</p>
        ) : (
          <ul className="divide-y divide-divider">
            {mine.map((a) => (
              <AlertRow key={a.id} alert={a} showSymbol={false} onEdit={setEditing} />
            ))}
          </ul>
        )}
      </section>
    </Sheet>
  )
}

/** The quote page's bell (§22.10): opens the alert sheet; highlighted while the symbol has an active alert. */
export function AlertButton({ symbol, size = 'md' }: { symbol: string; size?: 'md' | 'touch' }) {
  const [open, setOpen] = useState(false)
  const alerts = useAlerts()
  const active = (alerts.data ?? []).some((a) => a.symbol === symbol && a.status === 'ACTIVE')
  return (
    <>
      <IconButton
        label={active ? `${symbol} price alerts (active)` : `Set a price alert on ${symbol}`}
        size={size}
        active={active}
        onClick={() => setOpen(true)}
        data-testid="alert-button"
      >
        <IconBell />
      </IconButton>
      <AlertSheet symbol={symbol} open={open} onClose={() => setOpen(false)} />
    </>
  )
}
