import { useState } from 'react'
import { createFileRoute } from '@tanstack/react-router'
import { describeError, errorBody } from '@/lib/api/errors'
import { useResetMyAccount } from '@/lib/api/hooks/account'
import { useChangePassword, useMe, useUpdateDisplayName } from '@/lib/api/hooks/identity'
import { API_MOCK } from '@/lib/api/client'
import { Button } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { TextField } from '@/components/ui/Field'
import { SettingsHeader } from '@/features/settings/SettingsHeader'
import { toast } from '@/features/notifications/toastStore'

export const Route = createFileRoute('/_app/settings/account')({
  staticData: { phone: 'pushed' },
  component: AccountSettings,
})

/** Profile name, password change (revokes personal keys, §17.3) and self-reset when the league allows it (type RESET). */
function AccountSettings() {
  const { data: me } = useMe()
  const rename = useUpdateDisplayName()
  const changePassword = useChangePassword()
  const reset = useResetMyAccount()
  const [displayName, setDisplayName] = useState<string | null>(null)
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [resetOpen, setResetOpen] = useState(false)
  const [resetKey, setResetKey] = useState(() => crypto.randomUUID())
  const name = displayName ?? me?.displayName ?? ''
  const pwError = next && next.length < 10 ? 'At least 10 characters.' : confirm && confirm !== next ? 'The passwords do not match.' : null
  const pwServer = changePassword.error ? describeError(changePassword.error) : null

  return (
    <div className="space-y-4">
      <SettingsHeader title="Account" />
      <form
        className="space-y-3 rounded-lg border border-divider bg-bg-1 p-4"
        onSubmit={(e) => {
          e.preventDefault()
          rename.mutate(name.trim(), {
            onSuccess: () => {
              setDisplayName(null)
              toast({ tone: 'neutral', title: 'Display name saved' })
            },
            onError: (err) => toast({ tone: 'error', title: describeError(err).title }),
          })
        }}
      >
        <h2 className="text-section font-semibold">Profile</h2>
        <TextField label="Display name" value={name} onChange={(e) => setDisplayName(e.target.value)} maxLength={40} hint={me?.handle ? `Your handle is @${me.handle}.` : undefined} />
        <Button type="submit" disabled={!name.trim() || name === me?.displayName || rename.isPending}>
          Save name
        </Button>
      </form>

      <form
        className="space-y-3 rounded-lg border border-divider bg-bg-1 p-4"
        onSubmit={(e) => {
          e.preventDefault()
          changePassword.mutate(
            { currentPassword: current, newPassword: next },
            {
              onSuccess: () => {
                setCurrent('')
                setNext('')
                setConfirm('')
                toast({ tone: 'neutral', title: 'Password changed', body: 'Your other sessions were signed out and your personal API keys were revoked.' })
              },
            },
          )
        }}
      >
        <h2 className="text-section font-semibold">Password</h2>
        <input type="text" autoComplete="username" value={me?.handle ?? ''} readOnly hidden />
        <TextField label="Current password" type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} />
        <TextField label="New password" type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} hint="At least 10 characters." />
        <TextField label="Confirm new password" type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} error={pwError} />
        {pwServer ? (
          <p role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
            {pwServer.title}
            {errorBody(pwServer) ? <span className="block text-text-2">{errorBody(pwServer)}</span> : null}
          </p>
        ) : null}
        <p className="text-caption text-text-3">Changing your password signs out your other sessions and revokes your personal API keys.</p>
        <Button type="submit" disabled={!current || !next || Boolean(pwError) || next !== confirm || changePassword.isPending}>
          {changePassword.isPending ? 'Saving…' : 'Change password'}
        </Button>
      </form>

      <section className="space-y-2 rounded-lg border border-down/30 bg-bg-1 p-4">
        <h2 className="text-section font-semibold">Reset my account</h2>
        <p className="text-caption text-text-2">
          Start over with the league's starting cash. Your open orders are cancelled and a new run begins. The leaderboard keeps counting across runs, so a reset never erases a loss.
          Only available when the admin allows self-reset.
        </p>
        <Button variant="danger" onClick={() => setResetOpen(true)}>
          Reset account…
        </Button>
      </section>
      {API_MOCK ? <MockReset /> : null}

      <ConfirmDialog
        open={resetOpen}
        onClose={() => setResetOpen(false)}
        title="Reset your account?"
        tone="danger"
        confirmLabel="Reset account"
        typeToConfirm="RESET"
        pending={reset.isPending}
        error={reset.error ? describeError(reset.error).title : null}
        onConfirm={() =>
          reset.mutate(resetKey, {
            onSuccess: () => {
              setResetOpen(false)
              setResetKey(crypto.randomUUID())
              toast({ tone: 'neutral', title: 'Account reset', body: 'A new run has started.' })
            },
          })
        }
      >
        This cancels your open orders, closes this run and opens a new one with the starting cash.
      </ConfirmDialog>
    </div>
  )
}

/** Mock mode only: wipe the in-memory fake server's data (orders, deposits, lists). */
function MockReset() {
  return (
    <section className="space-y-2 rounded-lg border border-ai/40 bg-ai-bg p-4">
      <h2 className="text-section font-semibold text-ai">Mock mode</h2>
      <p className="text-caption text-text-2">Reset the fake server's data for this tab (orders, positions, deposits, lists).</p>
      <Button
        size="sm"
        onClick={() => {
          // constant-folded away in normal builds, so the mock never ships to production
          if (import.meta.env.VITE_API_MOCK === '1') {
            void import('@/lib/api/mock').then(({ mockServer }) => {
              mockServer().resetAll()
              window.location.assign('/')
            })
          }
        }}
      >
        Reset mock data
      </Button>
    </section>
  )
}
