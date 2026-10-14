import { createFileRoute } from '@tanstack/react-router'
import { PublicPortfolio } from '@/features/league/PublicPortfolio'

export const Route = createFileRoute('/_app/players/$handle/portfolio')({
  component: PlayerPortfolioPage,
})

/** Profile → Portfolio (public mode, §16.7): the player's live cash, positions and open orders. */
function PlayerPortfolioPage() {
  const { handle } = Route.useParams()
  return <PublicPortfolio handle={handle} />
}
