import { createFileRoute } from '@tanstack/react-router'
import { requireAuth } from '@/app/RequireAuth'
import { ConsentScreen } from '@/features/oauth/ConsentScreen'

/** `/oauth/consent?request=oar_…` (§17.9.3): `/oauth2/authorize` sends the browser here; anonymous → /login and back. */
export const Route = createFileRoute('/oauth/consent')({
  validateSearch: (search: Record<string, unknown>): { request: string } => ({
    request: typeof search.request === 'string' && /^oar_[A-Za-z0-9_-]{1,64}$/.test(search.request) ? search.request : '',
  }),
  beforeLoad: ({ context, location }) => requireAuth(context.queryClient, location.href),
  component: ConsentPage,
})

function ConsentPage() {
  const { request } = Route.useSearch()
  return <ConsentScreen requestId={request} />
}
