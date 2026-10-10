import { createFileRoute } from '@tanstack/react-router'
import { PagePlaceholder } from '@/components/PagePlaceholder'

export const Route = createFileRoute('/_app/admin/players/$handle')({
  component: AdminPlayer,
})

function AdminPlayer() {
  const { handle } = Route.useParams()
  return (
    <PagePlaceholder
      className="px-0 desktop:px-0"
      title={`@${handle}`}
      milestone="M2"
      layout="cards"
      description="Runs, balances, end-of-day holdings, API keys (copy-once snippets), allow API trading, transfers, recent audit. Live positions and orders only after “Break glass: view live” (reason + re-auth; the player sees it in their activity)."
    />
  )
}
