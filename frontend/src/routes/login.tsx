import { useState } from 'react'
import { Link, createFileRoute, useNavigate } from '@tanstack/react-router'
import { useLogin } from '@/lib/api/hooks/identity'
import { API_MOCK } from '@/lib/api/client'
import { describeError } from '@/lib/api/errors'
import { Button } from '@/components/ui/Button'
import { TextField } from '@/components/ui/Field'
import { AuthCard } from '@/features/auth/AuthCard'

export const Route = createFileRoute('/login')({
  validateSearch: (search: Record<string, unknown>): { redirect?: string } => ({
    redirect: typeof search.redirect === 'string' && search.redirect.startsWith('/') && !search.redirect.startsWith('//') ? search.redirect : undefined,
  }),
  component: LoginPage,
})

/** Login (§22.2, §17.3): JSON POST /auth/login; handles are case-insensitive. */
function LoginPage() {
  const { redirect } = Route.useSearch()
  const navigate = useNavigate()
  const login = useLogin()
  const [handle, setHandle] = useState('')
  const [password, setPassword] = useState('')
  const error = login.error ? describeError(login.error) : null

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    login.mutate({ handle: handle.trim(), password }, { onSuccess: () => void navigate({ to: redirect ?? '/', replace: true }) })
  }

  return (
    <AuthCard
      title="stocks"
      subtitle="Paper trading with friends and bots on the live market"
      footer={
        <>
          Invite only. Got an invite link? Open it to create your account
          {API_MOCK ? (
            <>
              {' '}
              (mock:{' '}
              <Link to="/join/$code" params={{ code: 'welcome-friends' }} className="font-semibold text-accent">
                try one
              </Link>
              )
            </>
          ) : null}
          .
        </>
      }
    >
      <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
        <TextField
          label="Handle"
          value={handle}
          onChange={(e) => setHandle(e.target.value)}
          autoComplete="username"
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          required
        />
        <TextField label="Password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
        {error ? (
          <div role="alert" className="rounded-md bg-down-bg p-3 text-caption text-down">
            <p className="font-semibold">{error.title}</p>
            {error.code === 'LOGIN_LOCKED' || error.hint ? <p className="text-text-2">{error.hint ?? error.detail}</p> : null}
          </div>
        ) : null}
        <Button type="submit" variant="primary" size="lg" block disabled={login.isPending || !handle.trim() || !password}>
          {login.isPending ? 'Signing in…' : 'Sign in'}
        </Button>
        {API_MOCK ? <p className="text-center text-micro text-ai">Mock mode: any handle and password (except “wrong”).</p> : null}
      </form>
    </AuthCard>
  )
}
