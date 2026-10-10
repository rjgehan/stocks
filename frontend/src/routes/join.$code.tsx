import { createFileRoute } from '@tanstack/react-router'
import { SignupForm } from '@/features/auth/SignupForm'

/** `/join/$code` (§22.2): the invite link the admin shares. */
export const Route = createFileRoute('/join/$code')({
  component: JoinPage,
})

function JoinPage() {
  const { code } = Route.useParams()
  return <SignupForm initialCode={code} />
}
