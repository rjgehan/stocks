import { createFileRoute } from '@tanstack/react-router'
import { PagePlaceholder } from '@/components/PagePlaceholder'

export const Route = createFileRoute('/_app/account/history')({
  validateSearch: (s: Record<string, unknown>): { range?: string } => ({ range: typeof s.range === 'string' ? s.range : undefined }),
  component: () => (
    <PagePlaceholder
      className="px-0 desktop:px-0"
      title="History"
      milestone="M3"
      layout="cards"
      description="Equity curve (1D/1W/1M/3M/YTD/1Y/ALL) with NAV, P&L and Return vs SPY toggles, plus your runs (resets)."
    />
  ),
})
