import { Link } from '@tanstack/react-router'
import { describeError, errorBody } from '@/lib/api/errors'
import { useCancelGroup, useOrderGroup } from '@/lib/api/hooks/orderGroups'
import type { OrderGroupRef } from '@/lib/api/groupTypes'
import { formatPrice, formatQty } from '@/lib/format/number'
import { Button } from '@/components/ui/Button'
import { Pill } from '@/components/ui/Pill'
import { Skeleton } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { ORDER_TYPE_LABEL, STATUS_LABEL, STATUS_TONE, orderPriceLabel } from '@/features/account/orderLabels'
import { toast } from '@/features/notifications/toastStore'
import { GROUP_STATUS_LABEL, GROUP_STATUS_TONE, GROUP_TYPE_HELP, GROUP_TYPE_LABEL, ROLE_LABEL, heldLabel } from './groupLabels'

/**
 * Order detail's "Linked orders" card (§22.5 Orders, §11.9): the group's type and status, every current leg with its
 * role, prices and status (each links to its own detail), and Cancel group while it is active.
 */
export function LinkedOrdersCard({ group, currentOrderId }: { group: OrderGroupRef; currentOrderId: string | null | undefined }) {
  const { data: g, isPending } = useOrderGroup(group.groupId)
  const cancel = useCancelGroup()
  const type = group.type ?? 'BRACKET'
  const onCancel = () =>
    group.groupId &&
    cancel.mutate(group.groupId, {
      onSuccess: (res) => toast({ tone: 'neutral', title: `${GROUP_TYPE_LABEL[type]} cancel sent`, body: res.status === 'COMPLETED' ? 'Some legs had already filled.' : undefined }),
      onError: (e) => {
        const d = describeError(e)
        toast({ tone: 'error', title: d.title, body: errorBody(d) })
      },
    })
  return (
    <section className="rounded-lg border border-divider bg-bg-1 p-4" aria-label="Linked orders" data-testid="linked-orders">
      <div className="mb-1 flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-section font-semibold">
          Linked orders <span className="text-caption font-normal text-text-2">· {GROUP_TYPE_LABEL[type]}</span>
        </h2>
        <div className="flex items-center gap-2">
          {g?.status ? <Pill tone={GROUP_STATUS_TONE[g.status]}>{GROUP_STATUS_LABEL[g.status]}</Pill> : null}
          {g?.status === 'ACTIVE' ? (
            <Button variant="danger" size="sm" disabled={cancel.isPending} onClick={onCancel}>
              Cancel group
            </Button>
          ) : null}
        </div>
      </div>
      <p className="mb-2 text-caption text-text-3">{GROUP_TYPE_HELP[type]}</p>
      {isPending ? (
        <Skeleton className="h-16 w-full" />
      ) : (
        <ul className="divide-y divide-divider">
          {(g?.legs ?? []).map((leg) => {
            const role = leg.group?.role
            const self = leg.orderId === currentOrderId
            const held = heldLabel(leg.group)
            return (
              <li key={leg.orderId} className={cx('flex items-center gap-3 py-2 text-table', self && 'font-semibold')}>
                <span className="w-24 shrink-0 text-text-2">{role ? ROLE_LABEL[role] : ''}</span>
                <Link to="/account/orders/$orderId" params={{ orderId: leg.orderId ?? '' }} className="min-w-0 flex-1 hover:text-accent">
                  <span className={leg.side === 'BUY' ? 'text-up' : 'text-down'}>{leg.side === 'BUY' ? 'Buy' : 'Sell'}</span> {formatQty(leg.qty)} ·{' '}
                  {leg.type ? ORDER_TYPE_LABEL[leg.type] : ''} {orderPriceLabel(leg)}
                  {leg.filledQty && leg.filledQty !== '0' ? <span className="text-text-3"> · filled {formatQty(leg.filledQty)} @ {formatPrice(leg.avgFillPrice)}</span> : null}
                  {self ? <span className="text-text-3"> · this order</span> : null}
                </Link>
                <Pill tone={held ? 'neutral' : leg.status ? STATUS_TONE[leg.status] : 'neutral'}>{held ?? (leg.status ? STATUS_LABEL[leg.status] : '--')}</Pill>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
