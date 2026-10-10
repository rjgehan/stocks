import { createFileRoute } from '@tanstack/react-router'
import { PushedHeader } from '@/app/PushedHeader'
import { useBreakpoint } from '@/app/useBreakpoint'
import { SymbolSearch } from '@/features/market/SymbolSearch'

export const Route = createFileRoute('/_app/search')({
  validateSearch: (search: Record<string, unknown>): { q?: string } => ({ q: typeof search.q === 'string' ? search.q : undefined }),
  staticData: { chrome: 'watchlist', phone: 'pushed' },
  component: SearchPage,
})

/**
 * Full-screen search on phones; on desktop ⌘K is the main entry and this route exists for deep links (§22.2):
 * `/search?q=apple` opens with the query filled in, and typing keeps `?q=` current (replacing the history entry).
 */
function SearchPage() {
  const bp = useBreakpoint()
  const { q } = Route.useSearch()
  const navigate = Route.useNavigate()
  return (
    <div className="flex min-h-full flex-col">
      {bp === 'phone' ? <PushedHeader title="Search" /> : null}
      <div className="mx-auto w-full max-w-xl p-4">
        <SymbolSearch
          variant="page"
          focusOnMount
          initialQuery={q ?? ''}
          onQueryChange={(next) => void navigate({ search: { q: next.length > 0 ? next : undefined }, replace: true })}
        />
      </div>
    </div>
  )
}
