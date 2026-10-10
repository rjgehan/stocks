import { useState } from 'react'
import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { useLogin } from '@/lib/api/hooks/identity'
import { API_MOCK } from '@/lib/api/client'
import { isApiError } from '@/lib/api/problem'
import { Button } from '@/components/ui/Button'
import { LogoMark } from '@/components/ui/icons'

export const Route = createFileRoute('/login')({
  validateSearch: (search: Record<string, unknown>): { redirect?: string } => ({
    redirect: typeof search.redirect === 'string' && search.redirect.startsWith('/') ? search.redirect : undefined,
  }),
  component: LoginPage,
})

/** Login (§22.2): a centered card on desktop, full screen on phones. JSON POST /auth/login (§17.3). */
function LoginPage() {
  const { redirect } = Route.useSearch()
  const navigate = useNavigate()
  const login = useLogin()
  const [handle, setHandle] = useState('')
  const [password, setPassword] = useState('')
  const error = login.error && isApiError(login.error) ? login.error : null

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    login.mutate(
      { handle: handle.trim(), password },
      { onSuccess: () => void navigate({ to: redirect ?? '/', replace: true }) },
    )
  }

  return (
    <div className="flex min-h-dvh items-stretch justify-center bg-bg-0 pt-safe pb-safe tablet:items-center tablet:p-6">
      <form onSubmit={submit} className="flex w-full flex-col gap-4 bg-bg-1 p-6 tablet:max-w-sm tablet:rounded-lg tablet:border tablet:border-divider tablet:shadow-xl">
        <div className="mb-2 flex items-center gap-2.5">
          <LogoMark size={36} />
          <div>
            <h1 className="text-[22px] leading-7 font-bold tracking-tight">stocks</h1>
            <p className="text-caption text-text-2">Paper trading with friends and bots</p>
          </div>
        </div>
        <label className="block">
          <span className="mb-1 block text-caption font-medium text-text-2">Handle</span>
          <input
            value={handle}
            onChange={(e) => setHandle(e.target.value)}
            autoComplete="username"
            autoCapitalize="none"
            required
            className="h-11 w-full rounded-md border border-border bg-bg-2 px-3 text-body outline-none focus:border-accent"
          />
        </label>
        <label className="block">
          <span className="mb-1 block text-caption font-medium text-text-2">Password</span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
            className="h-11 w-full rounded-md border border-border bg-bg-2 px-3 text-body outline-none focus:border-accent"
          />
        </label>
        {error ? (
          <p role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
            {error.problem.detail ?? error.problem.title}
            {error.hint ? <span className="block text-text-2">{error.hint}</span> : null}
          </p>
        ) : null}
        <Button type="submit" variant="primary" size="lg" block disabled={login.isPending}>
          {login.isPending ? 'Signing in…' : 'Sign in'}
        </Button>
        <p className="text-center text-caption text-text-3">Invite only. Ask the league admin for an invite link.</p>
        {API_MOCK ? <p className="text-center text-micro text-ai">Mock mode: any handle and password (except “wrong”).</p> : null}
      </form>
    </div>
  )
}
