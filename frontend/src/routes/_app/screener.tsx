import { createFileRoute } from '@tanstack/react-router'
import { useBreakpoint } from '@/app/useBreakpoint'
import { ScreenerScreen } from '@/features/screener/ScreenerScreen'
import { type ScreenerSearch, parseScreenerSearch } from '@/features/screener/screenerParams'

/** §22.10 screener: `/screener?preset&minPrice&maxPrice&cap&chg&rvol&sectors&type&sort&order`. */
export const Route = createFileRoute('/_app/screener')({
  validateSearch: (search: Record<string, unknown>): ScreenerSearch => parseScreenerSearch(search),
  staticData: { chrome: 'watchlist', phone: 'pushed', title: 'Screener' },
  component: ScreenerRoute,
})

function ScreenerRoute() {
  const search = Route.useSearch()
  const navigate = Route.useNavigate()
  const bp = useBreakpoint()
  return <ScreenerScreen search={search} breakpoint={bp} onSearch={(patch) => void navigate({ search: (prev) => ({ ...prev, ...patch }), replace: true })} />
}
