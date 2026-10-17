import { useCallback } from 'react'
import { createFileRoute } from '@tanstack/react-router'
import type { OrdersFilter } from '@/lib/api/queryKeys'
import { usePlayerProfile } from '@/lib/api/hooks/league'
import { Segmented } from '@/components/ui/Segmented'
import { PlayerActivityList } from '@/features/league/PlayerActivityList'
import { PlayerOrderHistory } from '@/features/league/PlayerOrderHistory'
import { ManualSessionsSection } from '@/features/manualbot/ManualSessionsSection'
import { fromPath } from '@/lib/symbols'

type View = 'orders' | 'activity'

interface HistorySearch {
  view?: View
  status?: OrdersFilter
  symbol?: string
  /** a manual session to show first, expanded (the "Manual console" pill of an order, §20.8) */
  session?: string
}

export const Route = createFileRoute('/_app/players/$handle/history')({
  validateSearch: (s: Record<string, unknown>): HistorySearch => ({
    view: s.view === 'activity' || s.view === 'orders' ? s.view : undefined,
    status: s.status === 'OPEN' || s.status === 'CLOSED' || s.status === 'ALL' ? s.status : undefined,
    // a ticker or a crypto pair (`BTC/USD`, or its path spelling `BTC-USD`)
    symbol: typeof s.symbol === 'string' && /^[A-Z0-9][A-Z0-9./-]{0,11}$/.test(s.symbol.toUpperCase()) ? fromPath(s.symbol) : undefined,
    session: typeof s.session === 'string' && /^mbs_[0-9A-Z]{26}$/.test(s.session) ? s.session : undefined,
  }),
  component: PlayerHistoryPage,
})

/**
 * Profile → History (public mode, §16.7): Orders (every stored order of every run, with fills and notes) or Activity
 * (the active run's ledger and account events). Under the orders of an AI player, its manual sessions (§20.8): the
 * prompts, pasted replies and results, with `?session=` first; nothing for a bot never driven by hand.
 */
function PlayerHistoryPage() {
  const { handle } = Route.useParams()
  const { view = 'orders', status = 'ALL', symbol, session } = Route.useSearch()
  const ai = usePlayerProfile(handle).data?.player?.kind === 'AI'
  const navigate = Route.useNavigate()
  const onFilter = useCallback(
    (f: { status?: OrdersFilter; symbol?: string }) =>
      void navigate({ search: (prev) => ({ ...prev, status: f.status === 'ALL' ? undefined : f.status, symbol: f.symbol || undefined }), replace: true }),
    [navigate],
  )
  return (
    <div className="space-y-3">
      <Segmented
        label="History view"
        size="sm"
        value={view}
        onChange={(v) => void navigate({ search: (prev) => ({ ...prev, view: v === 'orders' ? undefined : v }), replace: true })}
        options={[
          { value: 'orders', label: 'Orders' },
          { value: 'activity', label: 'Activity' },
        ]}
      />
      {view === 'orders' ? (
        <>
          <PlayerOrderHistory handle={handle} status={status} symbol={symbol} onFilter={onFilter} />
          {ai ? <ManualSessionsSection handle={handle} session={session} /> : null}
        </>
      ) : (
        <div className="-mx-4 bg-bg-1 tablet:mx-0 tablet:rounded-lg tablet:border tablet:border-divider">
          <PlayerActivityList handle={handle} />
        </div>
      )}
    </div>
  )
}
