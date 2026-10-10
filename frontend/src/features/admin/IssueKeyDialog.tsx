import { useState } from 'react'
import { describeError } from '@/lib/api/errors'
import { useIssueKey } from '@/lib/api/hooks/admin'
import type { ApiKeyCreated } from '@/lib/api/types'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { Checkbox, SelectField, TextField } from '@/components/ui/Field'
import { KeyRevealDialog } from '@/features/keys/ApiKeys'

const SCOPES = ['market:read', 'account:read', 'trade', 'league:read'] as const

/** Issue an API key for a player (§21.1 `POST /admin/players/{handle}/api-keys`, R); the token is revealed once. */
export function IssueKeyDialog({ handle, onClose }: { handle: string | null; onClose: () => void }) {
  const issue = useIssueKey()
  const [name, setName] = useState('')
  const [scopes, setScopes] = useState<string[]>([...SCOPES])
  const [expires, setExpires] = useState('')
  const [created, setCreated] = useState<{ key: ApiKeyCreated; handle: string } | null>(null)
  const close = () => {
    setName('')
    setScopes([...SCOPES])
    setExpires('')
    issue.reset()
    onClose()
  }
  const toggle = (s: string, on: boolean) => setScopes((cur) => (on ? [...new Set([...cur, s])] : cur.filter((x) => x !== s)))
  return (
    <>
      <Dialog open={handle !== null && created === null} onClose={close} title={`New API key for @${handle ?? ''}`}>
        <form
          className="space-y-3 p-4"
          onSubmit={(e) => {
            e.preventDefault()
            if (!handle) return
            issue.mutate(
              { handle, name: name.trim(), scopes, ...(expires ? { expiresInDays: Number(expires) } : {}) },
              {
                onSuccess: (key) => {
                  setCreated({ key, handle })
                  setName('')
                },
              },
            )
          }}
        >
          <TextField label="Key name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Agent runner" maxLength={64} />
          <fieldset className="space-y-1.5">
            <legend className="mb-1 text-caption font-medium text-text-2">Scopes</legend>
            {SCOPES.map((s) => (
              <Checkbox key={s} checked={scopes.includes(s)} onChange={(on) => toggle(s, on)}>
                <span className="font-mono">{s}</span>
                {s === 'trade' ? <span className="text-text-3"> (place and cancel orders)</span> : null}
              </Checkbox>
            ))}
          </fieldset>
          <SelectField
            label="Expires"
            value={expires}
            onChange={(e) => setExpires(e.target.value)}
            options={[
              { value: '', label: 'Never' },
              { value: '30', label: 'In 30 days' },
              { value: '90', label: 'In 90 days' },
              { value: '365', label: 'In 1 year' },
            ]}
          />
          {issue.error ? (
            <p role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
              {describeError(issue.error).title}
            </p>
          ) : null}
          <Button type="submit" variant="primary" size="lg" block disabled={!name.trim() || scopes.length === 0 || issue.isPending}>
            {issue.isPending ? 'Issuing…' : 'Issue key'}
          </Button>
        </form>
      </Dialog>
      <KeyRevealDialog
        created={created?.key ?? null}
        owner={created?.handle}
        onClose={() => {
          setCreated(null)
          close()
        }}
      />
    </>
  )
}
