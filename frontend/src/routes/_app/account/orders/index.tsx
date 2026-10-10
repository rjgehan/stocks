import { createFileRoute } from '@tanstack/react-router'
import { Segmented } from '@/components/ui/Segmented'
import { OrdersTable } from '@/features/account/OrdersTable'

type Status = 'OPEN' | 'CLOSED' | 'ALL'

export const Route = createFileRoute('/_app/account/orders/')({
  validateSearch: (s: Record<string, unknown>): { status?: Status } => ({
    status: s.status === 'OPEN' || s.status === 'CLOSED' || s.status === 'ALL' ? s.status : undefined,
  }),
  component: OrdersPage,
})

function OrdersPage() {
  const { status = 'OPEN' } = Route.useSearch()
  const navigate = Route.useNavigate()
  return (
    <div className="space-y-3">
      <Segmented
        label="Order status"
        size="sm"
        value={status}
        onChange={(s) => void navigate({ search: { status: s }, replace: true })}
        options={[
          { value: 'OPEN', label: 'Open' },
          { value: 'CLOSED', label: 'Closed' },
          { value: 'ALL', label: 'All' },
        ]}
      />
      <div className="-mx-4 bg-bg-1 tablet:mx-0 tablet:rounded-lg tablet:border tablet:border-divider">
        <OrdersTable status={status} />
      </div>
    </div>
  )
}
