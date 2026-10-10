import { useState } from 'react'
import { useMe, reauthenticate } from '@/lib/api/hooks/identity'
import { describeError } from '@/lib/api/errors'
import { useReauthStore } from '@/lib/api/reauth'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { TextField } from '@/components/ui/Field'

/**
 * "Confirm your password" (§17.1 `@RecentAuth`, §21.1 R routes): opened by `withRecentAuth` when the server answers
 * 403 RECENT_AUTH_REQUIRED; on success the original request is retried.
 */
export function ReauthDialog() {
  const open = useReauthStore((s) => s.open)
  const settle = useReauthStore((s) => s.settle)
  const { data: me } = useMe()
  const [password, setPassword] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const close = (ok: boolean) => {
    setPassword('')
    setError(null)
    settle(ok)
  }
  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setPending(true)
    setError(null)
    try {
      await reauthenticate(password)
      close(true)
    } catch (err) {
      const d = describeError(err)
      setError(d.detail ?? d.title)
    } finally {
      setPending(false)
    }
  }

  return (
    <Dialog open={open} onClose={() => close(false)} title="Confirm it's you">
      <form onSubmit={(e) => void submit(e)} className="space-y-3 p-4">
        <p className="text-body text-text-2">Sensitive admin and key actions need your password again (every 15 minutes).</p>
        <input type="text" autoComplete="username" value={me?.handle ?? ''} readOnly hidden />
        <TextField
          label="Password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          error={error}
          required
        />
        <div className="flex gap-2 pt-1">
          <Button variant="secondary" size="lg" className="flex-1" onClick={() => close(false)}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" size="lg" className="flex-[1.4]" disabled={pending || password.length === 0}>
            {pending ? 'Checking…' : 'Confirm'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
