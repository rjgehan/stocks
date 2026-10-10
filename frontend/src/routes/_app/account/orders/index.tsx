import { createFileRoute } from '@tanstack/react-router'
import { useOrdersPaged } from '@/lib/api/hooks/account'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { Segmented } from '@/components/ui/Segmented'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { type OrderView, OrderRow, useCancelWithToast, viewFilter } from '@/features/account/OrdersTable'

type StatusParam = 'OPEN' | 'FILLED' | 'CANCELLED' | 'ALL' | 'CLOSED'

export const Route = createFileRoute('/_app/account/orders/')({
  // §22.2 `/orders?status=OPEN`; FILLED and CANCELLED split the server's CLOSED list (CLOSED kept for old links).
  validateSearch: (s: Record<string, unknown>): { status?: StatusParam } => ({
    status: s.status === 'OPEN' || s.status === 'FILLED' || s.status === 'CANCELLED' || s.status === 'ALL' || s.status === 'CLOSED' ? s.status : undefined,
  }),
  component: OrdersPage,
})

function OrdersPage() {
  const { status = 'OPEN' } = Route.useSearch()
  const view: OrderView = status === 'CLOSED' ? 'ALL' : status
  const navigate = Route.useNavigate()
  const filter = viewFilter(view)
  const q = useOrdersPaged(filter.status)
  const cancel = useCancelWithToast()
  const items = (q.data?.pages ?? []).flatMap((p) => p.items ?? []).filter(filter.keep)
  return (
    <div className="space-y-3">
      <Segmented
        label="Order status"
        size="sm"
        value={view}
        onChange={(s) => void navigate({ search: { status: s }, replace: true })}
        options={[
          { value: 'OPEN', label: 'Open' },
          { value: 'FILLED', label: 'Filled' },
          { value: 'CANCELLED', label: 'Cancelled' },
          { value: 'ALL', label: 'All' },
        ]}
      />
      <div className="-mx-4 bg-bg-1 tablet:mx-0 tablet:rounded-lg tablet:border tablet:border-divider">
        {q.isPending ? (
          <div className="px-4">
            <SkeletonRows rows={5} cols={4} />
          </div>
        ) : q.isError ? (
          <EmptyState title="Orders are unavailable right now" body="They will reload automatically." />
        ) : items.length === 0 ? (
          <EmptyState
            title={view === 'OPEN' ? 'No open orders' : view === 'FILLED' ? 'No filled orders yet' : view === 'CANCELLED' ? 'No cancelled orders' : 'No orders yet'}
            body={view === 'OPEN' ? 'Limit orders waiting for their price, and orders queued for the open, show up here.' : undefined}
          />
        ) : (
          <ul className="divide-y divide-divider" aria-label="Orders">
            {items.map((o) => (
              <OrderRow key={o.orderId} o={o} onCancel={cancel.run} cancelling={cancel.pendingId === o.orderId} showDate />
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
    </div>
  )
}
