import { createFileRoute } from '@tanstack/react-router'
import { usePlayerPnlAnalysis, usePlayerPnlCalendar } from '@/lib/api/hooks/league'
import { isApiError } from '@/lib/api/problem'
import type { CalendarView, HistoryRange } from '@/lib/api/types'
import { EmptyState } from '@/components/ui/EmptyState'
import { etMonth, shiftMonth } from '@/features/stats/calendar'
import { EARNINGS_RANGES, MyEarnings } from '@/features/stats/MyEarnings'
import { PnlBySymbol } from '@/features/stats/PnlBySymbol'
import { PnlCalendarCard } from '@/features/stats/PnlCalendarCard'

interface AnalysisSearch {
  range?: HistoryRange
  cal?: CalendarView
  month?: string
  year?: number
}

export const Route = createFileRoute('/_app/players/$handle/analysis')({
  validateSearch: (s: Record<string, unknown>): AnalysisSearch => ({
    range: EARNINGS_RANGES.find((r) => r === s.range),
    cal: s.cal === 'DAY' || s.cal === 'MONTH' ? s.cal : undefined,
    month: typeof s.month === 'string' && /^\d{4}-\d{2}$/.test(s.month) ? s.month : undefined,
    year: typeof s.year === 'number' && s.year >= 2000 && s.year <= 2100 ? s.year : undefined,
  }),
  component: PlayerAnalysisPage,
})

/**
 * Profile → Analysis (public mode, §16.7): the same P&L Analysis as the account's (earnings vs SPY and QQQ, the P&L
 * calendar, P&L by symbol) for the player's active run. Their league stats are on the Overview.
 */
function PlayerAnalysisPage() {
  const { handle } = Route.useParams()
  const search = Route.useSearch()
  const navigate = Route.useNavigate()
  const range = search.range ?? '1M'
  const view = search.cal ?? 'DAY'
  const thisMonth = etMonth()
  const month = search.month ?? thisMonth
  const year = search.year ?? Number(thisMonth.slice(0, 4))
  const set = (patch: Partial<AnalysisSearch>) => void navigate({ search: (prev) => ({ ...prev, ...patch }), replace: true })
  const analysis = usePlayerPnlAnalysis(handle, range)
  const calendar = usePlayerPnlCalendar(handle, view, month, year)
  if (isApiError(analysis.error) && analysis.error.code === 'ACCOUNT_NOT_FOUND') return <EmptyState title="No active run." body={`@${handle} has no account in the league right now.`} />
  return (
    <div className="space-y-4">
      <div className="grid gap-4 desktop:grid-cols-2">
        <MyEarnings
          title="Earnings"
          who={`@${handle}`}
          range={range}
          onRange={(r) => set({ range: r === '1M' ? undefined : r })}
          analysis={analysis.data}
          isPending={analysis.isPending}
          isError={analysis.isError}
        />
        <PnlCalendarCard
          view={view}
          onView={(v) => set({ cal: v === 'DAY' ? undefined : v })}
          month={month}
          year={year}
          onShift={(n) => (view === 'DAY' ? set({ month: shiftMonth(month, n) }) : set({ year: year + n }))}
          calendar={calendar.data}
          isPending={calendar.isPending}
          isError={calendar.isError}
        />
      </div>
      <PnlBySymbol analysis={analysis.data} isPending={analysis.isPending} />
    </div>
  )
}
