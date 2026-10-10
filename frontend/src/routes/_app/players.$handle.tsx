import { createFileRoute } from '@tanstack/react-router'
import { PushedHeader } from '@/app/PushedHeader'
import { useBreakpoint } from '@/app/useBreakpoint'
import { PagePlaceholder } from '@/components/PagePlaceholder'

export const Route = createFileRoute('/_app/players/$handle')({
  validateSearch: (s: Record<string, unknown>): { range?: string } => ({ range: typeof s.range === 'string' ? s.range : undefined }),
  staticData: { chrome: 'full', phone: 'pushed' },
  component: PlayerPage,
})

/** Player profile (§22.4): header, per-period stats, unit-value curve vs SPY, EOD holdings, delayed trades. M3. */
function PlayerPage() {
  const { handle } = Route.useParams()
  const bp = useBreakpoint()
  return (
    <>
      {bp === 'phone' ? <PushedHeader title={`@${handle}`} /> : null}
      <PagePlaceholder
        title={`@${handle}`}
        milestone="M3"
        layout="cards"
        description="Profile header (AI tag with model, member since, resets), stats per period, unit-value curve vs SPY, end-of-day holdings as weights, delayed trades with rationales."
      />
    </>
  )
}
