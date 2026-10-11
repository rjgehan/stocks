import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { describeError, errorBody } from '@/lib/api/errors'
import { useAlerts, useCreateAlert, useUpdateAlert } from '@/lib/api/hooks/alerts'
import type { Alert } from '@/lib/api/types'
import { useQuote } from '@/lib/realtime/useQuote'
import { useBreakpoint } from '@/app/useBreakpoint'
import { Sheet } from '@/components/ui/Sheet'
import { toast } from '@/features/notifications/toastStore'
import { AlertForm } from './AlertForm'
import { AlertRow } from './AlertRow'
import { describeCondition, liveView, sortAlerts } from './alertLogic'

/**
 * The quote page's alert sheet (§22.10): a new alert for this symbol (or the one being edited), and the symbol's other
 * alerts with pause/resume, edit and delete. A bottom sheet on phones, a right sheet elsewhere. After "Create alert"
 * the form starts over (fresh defaults from the live price), so a second press never repeats the alert just set.
 */
export default function AlertSheet({ symbol, open, onClose }: { symbol: string; open: boolean; onClose: () => void }) {
  const bp = useBreakpoint()
  const quote = useQuote(symbol, 'quote')
  const alerts = useAlerts()
  const create = useCreateAlert()
  const update = useUpdateAlert()
  const [editing, setEditing] = useState<Alert | null>(null)
  const [formKey, setFormKey] = useState(0)
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
          key={editing?.id ?? `new-${formKey}`}
          existing={mine}
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
                {
                  onSuccess: (a) => {
                    toast({ tone: 'neutral', title: `Alert set: ${symbol} ${describeCondition(a.condition, a.threshold)}` })
                    create.reset()
                    setFormKey((k) => k + 1)
                  },
                },
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
