import { createFileRoute } from '@tanstack/react-router'
import { PushedHeader } from '@/app/PushedHeader'
import { useBreakpoint } from '@/app/useBreakpoint'
import { SymbolSearch } from '@/features/market/SymbolSearch'

export const Route = createFileRoute('/_app/search')({
  validateSearch: (search: Record<string, unknown>): { q?: string } => ({ q: typeof search.q === 'string' ? search.q : undefined }),
  staticData: { chrome: 'watchlist', phone: 'pushed' },
  component: SearchPage,
})

/** Full-screen search on phones; on desktop ⌘K is the main entry and this route exists for deep links (§22.2). */
function SearchPage() {
  const bp = useBreakpoint()
  return (
    <div className="flex min-h-full flex-col">
      {bp === 'phone' ? <PushedHeader title="Search" /> : null}
      <div className="mx-auto w-full max-w-xl p-4">
        <SymbolSearch variant="page" focusOnMount />
      </div>
    </div>
  )
}
