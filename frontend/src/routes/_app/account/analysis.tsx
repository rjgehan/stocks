import { createFileRoute } from '@tanstack/react-router'
import { usePnlAnalysis, usePnlCalendar } from '@/lib/api/hooks/stats'
import type { CalendarView, HistoryRange } from '@/lib/api/types'
import { etMonth, shiftMonth } from '@/features/stats/calendar'
import { EARNINGS_RANGES, MyEarnings } from '@/features/stats/MyEarnings'
import { PnlBySymbol } from '@/features/stats/PnlBySymbol'
import { PnlCalendarCard } from '@/features/stats/PnlCalendarCard'
import { MyStatsSection } from '@/features/stats/StatsSections'

interface AnalysisSearch {
  range?: HistoryRange
  cal?: CalendarView
  month?: string
  year?: number
}

export const Route = createFileRoute('/_app/account/analysis')({
  validateSearch: (s: Record<string, unknown>): AnalysisSearch => ({
    range: EARNINGS_RANGES.find((r) => r === s.range),
    cal: s.cal === 'DAY' || s.cal === 'MONTH' ? s.cal : undefined,
    month: typeof s.month === 'string' && /^\d{4}-\d{2}$/.test(s.month) ? s.month : undefined,
    year: typeof s.year === 'number' && s.year >= 2000 && s.year <= 2100 ? s.year : undefined,
  }),
  component: AnalysisPage,
})

/**
 * P&L Analysis (§16.10, §22.4 Phase 2; Webull "P&L Analysis"): My Earnings (you vs SPY vs QQQ), the P&L calendar
 * (Day / Month), P&L by symbol and your league stats. Phones stack the cards; desktop puts earnings and the calendar
 * side by side.
 */
function AnalysisPage() {
  const search = Route.useSearch()
  const navigate = Route.useNavigate()
  const range = search.range ?? '1M'
  const view = search.cal ?? 'DAY'
  const thisMonth = etMonth()
  const month = search.month ?? thisMonth
  const year = search.year ?? Number(thisMonth.slice(0, 4))
  const set = (patch: Partial<AnalysisSearch>) => void navigate({ search: (prev) => ({ ...prev, ...patch }), replace: true })
  const analysis = usePnlAnalysis(range)
  const calendar = usePnlCalendar(view, month, year)
  return (
    <div className="space-y-4">
      <div className="grid gap-4 desktop:grid-cols-2">
        <MyEarnings range={range} onRange={(r) => set({ range: r === '1M' ? undefined : r })} analysis={analysis.data} isPending={analysis.isPending} isError={analysis.isError} />
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
      <MyStatsSection />
    </div>
  )
}
