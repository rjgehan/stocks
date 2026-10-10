import { createFileRoute } from '@tanstack/react-router'
import { OAuthErrorScreen } from '@/features/oauth/OAuthErrorScreen'

/** `/oauth/error?error=…&error_description=…` (§17.9.3): authorization errors that are never redirected to the app. */
export const Route = createFileRoute('/oauth/error')({
  validateSearch: (search: Record<string, unknown>): { error: string; error_description: string } => ({
    error: typeof search.error === 'string' ? search.error.slice(0, 64) : '',
    error_description: typeof search.error_description === 'string' ? search.error_description : '',
  }),
  component: ErrorPage,
})

function ErrorPage() {
  const { error, error_description } = Route.useSearch()
  return <OAuthErrorScreen error={error} description={error_description} />
}
