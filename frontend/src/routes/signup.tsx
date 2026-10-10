import { createFileRoute } from '@tanstack/react-router'
import { SignupForm } from '@/features/auth/SignupForm'

/** `/signup?invite=…`: an alias of `/join/$code`, so either invite-link shape works. Without a code, it asks for one. */
export const Route = createFileRoute('/signup')({
  validateSearch: (search: Record<string, unknown>): { invite?: string } => ({
    invite: typeof search.invite === 'string' && search.invite.length > 0 ? search.invite : undefined,
  }),
  component: SignupPage,
})

function SignupPage() {
  const { invite } = Route.useSearch()
  return <SignupForm initialCode={invite ?? ''} />
}
