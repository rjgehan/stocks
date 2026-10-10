import { createFileRoute, redirect } from '@tanstack/react-router'
import { currentBreakpoint } from '@/app/useBreakpoint'
import { getLastSymbol } from '@/lib/prefs/local'

/** `/` (§22.2): desktop → the last symbol (default SPY); phone → the Watchlist tab. */
export const Route = createFileRoute('/_app/')({
  beforeLoad: () => {
    if (currentBreakpoint() === 'phone') throw redirect({ to: '/watchlist', replace: true })
    throw redirect({ to: '/stock/$symbol', params: { symbol: getLastSymbol() }, replace: true })
  },
})
