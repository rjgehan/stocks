import { createFileRoute } from '@tanstack/react-router'
import { PushedHeader } from '@/app/PushedHeader'
import { useBreakpoint } from '@/app/useBreakpoint'
import { type AlertFilter, AlertsScreen } from '@/features/alerts/AlertsScreen'

interface AlertsSearch {
  status?: Exclude<AlertFilter, 'ALL'>
}

const STATUSES = ['ACTIVE', 'PAUSED', 'TRIGGERED', 'EXPIRED'] as const

/** `/alerts?status` (§22.2, Phase 2): the player's price alerts. A pushed screen from Menu on phones. */
export const Route = createFileRoute('/_app/alerts')({
  validateSearch: (s: Record<string, unknown>): AlertsSearch => ({
    status: STATUSES.find((x) => x === String(s.status ?? '').toUpperCase()),
  }),
  staticData: { chrome: 'full', phone: 'pushed', title: 'Alerts' },
  component: AlertsPage,
})

function AlertsPage() {
  const bp = useBreakpoint()
  const { status } = Route.useSearch()
  const navigate = Route.useNavigate()
  return (
    <div className="scrollbar-thin h-full overflow-y-auto">
      {bp === 'phone' ? <PushedHeader title="Price alerts" /> : null}
      <div className="mx-auto w-full max-w-3xl px-4 py-4 desktop:px-6">
        {bp !== 'phone' ? <h1 className="mb-3 text-[20px] leading-7 font-bold tracking-tight">Price alerts</h1> : null}
        <AlertsScreen
          filter={status ?? 'ALL'}
          onFilter={(f) => void navigate({ search: { status: f === 'ALL' ? undefined : f }, replace: true })}
        />
      </div>
    </div>
  )
}
