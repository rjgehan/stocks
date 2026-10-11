import { createFileRoute } from '@tanstack/react-router'
import { OAuthErrorScreen } from '@/features/oauth/OAuthErrorScreen'

/**
 * `/oauth/error?error=…` (§17.9.3): authorization errors that are never redirected to the app. The server also sends
 * `error_description`; it is ignored here (anyone can craft this URL, so only fixed copy per `error` code is shown).
 */
export const Route = createFileRoute('/oauth/error')({
  validateSearch: (search: Record<string, unknown>): { error: string } => ({
    error: typeof search.error === 'string' ? search.error.slice(0, 64) : '',
  }),
  component: ErrorPage,
})

function ErrorPage() {
  const { error } = Route.useSearch()
  return <OAuthErrorScreen error={error} />
}
