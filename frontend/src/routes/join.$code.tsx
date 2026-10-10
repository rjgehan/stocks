import { createFileRoute, Link } from '@tanstack/react-router'
import { LogoMark } from '@/components/ui/icons'
import { Skeleton } from '@/components/ui/Skeleton'
import { Pill } from '@/components/ui/Pill'

export const Route = createFileRoute('/join/$code')({
  validateSearch: (search: Record<string, unknown>): { redirect?: string } => ({
    redirect: typeof search.redirect === 'string' && search.redirect.startsWith('/') ? search.redirect : undefined,
  }),
  component: JoinPage,
})

/** Invite signup (§17.3 POST /auth/signup): handle, display name, password; funds a $500 account. Phase 1 (M1). */
function JoinPage() {
  const { code } = Route.useParams()
  return (
    <div className="flex min-h-dvh items-stretch justify-center bg-bg-0 pt-safe pb-safe tablet:items-center tablet:p-6">
      <div className="flex w-full flex-col gap-4 bg-bg-1 p-6 tablet:max-w-sm tablet:rounded-lg tablet:border tablet:border-divider tablet:shadow-xl">
        <div className="flex items-center gap-2.5">
          <LogoMark size={36} />
          <div>
            <h1 className="text-[22px] leading-7 font-bold tracking-tight">Join the league</h1>
            <p className="text-caption text-text-2">
              Invite <span className="font-mono text-text-1">{code}</span> · start with $500.00
            </p>
          </div>
        </div>
        <Pill tone="neutral" className="self-start">Signup arrives in M1</Pill>
        {['Handle', 'Display name', 'Password (10+ characters)'].map((l) => (
          <div key={l} className="space-y-1.5">
            <span className="block text-caption font-medium text-text-2">{l}</span>
            <Skeleton className="h-11 w-full" />
          </div>
        ))}
        <Link to="/login" className="text-center text-caption text-accent">
          Already have an account? Sign in
        </Link>
      </div>
    </div>
  )
}
