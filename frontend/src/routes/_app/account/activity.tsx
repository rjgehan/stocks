import { createFileRoute } from '@tanstack/react-router'
import { Segmented } from '@/components/ui/Segmented'
import { ActivityList } from '@/features/account/ActivityList'

type Filter = 'ALL' | 'TRADES' | 'TRANSFERS' | 'EVENTS'

const TYPES: Record<Filter, string | undefined> = {
  ALL: undefined,
  TRADES: 'FILL',
  TRANSFERS: 'INITIAL_FUNDING,DEPOSIT,WITHDRAWAL,ADJUSTMENT',
  EVENTS: 'DIVIDEND,CASH_IN_LIEU,SPLIT,NAME_CHANGE,GFV,RESET,RESTRICTION,ADMIN_VIEW',
}

export const Route = createFileRoute('/_app/account/activity')({
  validateSearch: (s: Record<string, unknown>): { types?: Filter } => ({
    types: s.types === 'TRADES' || s.types === 'TRANSFERS' || s.types === 'EVENTS' || s.types === 'ALL' ? s.types : undefined,
  }),
  component: ActivityPage,
})

/** Activity (§22.4): fills with fees, deposits with the memo and a "by admin" badge, and account events, newest first. */
function ActivityPage() {
  const { types = 'ALL' } = Route.useSearch()
  const navigate = Route.useNavigate()
  return (
    <div className="space-y-3">
      <Segmented
        label="Activity type"
        size="sm"
        value={types}
        onChange={(t) => void navigate({ search: { types: t === 'ALL' ? undefined : t }, replace: true })}
        options={[
          { value: 'ALL', label: 'All' },
          { value: 'TRADES', label: 'Trades' },
          { value: 'TRANSFERS', label: 'Deposits' },
          { value: 'EVENTS', label: 'Events' },
        ]}
      />
      <div className="-mx-4 bg-bg-1 tablet:mx-0 tablet:rounded-lg tablet:border tablet:border-divider">
        <ActivityList filter={{ types: TYPES[types] }} />
      </div>
    </div>
  )
}
