import { useState } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { useSignup } from '@/lib/api/hooks/identity'
import { describeError } from '@/lib/api/errors'
import { isApiError } from '@/lib/api/problem'
import { Button } from '@/components/ui/Button'
import { TextField } from '@/components/ui/Field'
import { AuthCard } from './AuthCard'
import { HANDLE_HINT, HANDLE_RE, displayNameError } from './rules'

const utf8Bytes = (s: string) => new TextEncoder().encode(s).length

/** Client-side checks mirror the server's (§8 handle and display-name patterns, §17.3 password 10 chars / 72 bytes); the server decides. */
export function validateSignup(v: { handle: string; displayName: string; password: string; confirm: string; inviteCode: string }) {
  const errors: Partial<Record<'handle' | 'displayName' | 'password' | 'confirm' | 'inviteCode', string>> = {}
  if (!v.inviteCode.trim()) errors.inviteCode = 'Paste the code from your invite link.'
  if (!HANDLE_RE.test(v.handle)) errors.handle = HANDLE_HINT
  const nameError = displayNameError(v.displayName)
  if (nameError) errors.displayName = nameError
  if (v.password.length < 10) errors.password = 'At least 10 characters.'
  else if (utf8Bytes(v.password) > 72) errors.password = 'At most 72 bytes.'
  if (v.confirm !== v.password) errors.confirm = 'The passwords do not match.'
  return errors
}

/**
 * Invite signup (§17.3 `POST /auth/signup`): handle, display name and password; the server funds the starting
 * account (default $500,000) and seeds "My List". Reached from `/join/$code` (spec) and `/signup?invite=` (alias).
 */
export function SignupForm({ initialCode }: { initialCode: string }) {
  const navigate = useNavigate()
  const signup = useSignup()
  const [inviteCode, setInviteCode] = useState(initialCode)
  const [handle, setHandle] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [touched, setTouched] = useState(false)
  const local = validateSignup({ handle, displayName, password, confirm, inviteCode })
  const server = signup.error && isApiError(signup.error) ? signup.error : null
  const serverFields = server?.fieldErrors ?? {}
  const fieldError = (f: 'handle' | 'displayName' | 'password' | 'confirm' | 'inviteCode') =>
    serverFields[f] ?? (server?.code === 'HANDLE_TAKEN' && f === 'handle' ? (server.problem.detail ?? 'That handle is taken.') : null) ?? (touched ? (local[f] ?? null) : null)
  const banner = signup.error && (!server || !['HANDLE_TAKEN', 'VALIDATION_FAILED'].includes(server.code) || Object.keys(serverFields).length === 0) ? describeError(signup.error) : null

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    setTouched(true)
    if (Object.keys(local).length) return
    signup.mutate(
      { inviteCode: inviteCode.trim(), handle, displayName: displayName.trim(), password },
      { onSuccess: () => void navigate({ to: '/', replace: true }) },
    )
  }

  return (
    <AuthCard
      title="Join the league"
      subtitle="Trade fake money on the real market against friends and AI bots."
      footer={
        <>
          Already have an account?{' '}
          <Link to="/login" className="font-semibold text-accent hover:text-accent-hover">
            Sign in
          </Link>
        </>
      }
    >
      <form onSubmit={submit} className="flex flex-col gap-3.5" noValidate>
        {initialCode ? (
          <p className="rounded-md bg-accent-bg px-3 py-2 text-caption text-text-1">
            Invite <span className="font-mono font-semibold">{initialCode}</span> · you start with the league's starting cash (usually $500,000.00).
          </p>
        ) : (
          <TextField label="Invite code" value={inviteCode} onChange={(e) => setInviteCode(e.target.value)} autoCapitalize="none" spellCheck={false} error={fieldError('inviteCode')} />
        )}
        <TextField
          label="Handle"
          hint="Your login name and @name in the league: lowercase letters, digits, _."
          value={handle}
          onChange={(e) => setHandle(e.target.value.toLowerCase().replace(/\s/g, ''))}
          autoComplete="username"
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          maxLength={32}
          prefix="@"
          error={fieldError('handle')}
        />
        <TextField label="Display name" value={displayName} onChange={(e) => setDisplayName(e.target.value)} autoComplete="nickname" maxLength={40} error={fieldError('displayName')} />
        <TextField
          label="Password"
          type="password"
          hint="At least 10 characters."
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="new-password"
          error={fieldError('password')}
        />
        <TextField label="Confirm password" type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password" error={fieldError('confirm')} />
        {banner ? (
          <div role="alert" className="rounded-md bg-down-bg p-3 text-caption text-down">
            <p className="font-semibold">{banner.title}</p>
            {banner.hint ?? banner.detail ? <p className="text-text-2">{banner.hint ?? banner.detail}</p> : null}
          </div>
        ) : null}
        <Button type="submit" variant="primary" size="lg" block disabled={signup.isPending}>
          {signup.isPending ? 'Creating your account…' : 'Create account'}
        </Button>
        <p className="text-center text-micro text-text-3">Paper trading only: no real money, not investment advice.</p>
      </form>
    </AuthCard>
  )
}
