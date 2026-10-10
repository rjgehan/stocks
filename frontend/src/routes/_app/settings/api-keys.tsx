import { useState } from 'react'
import { createFileRoute } from '@tanstack/react-router'
import { describeError, errorBody } from '@/lib/api/errors'
import { useCreateMyApiKey, useMyApiKeys, useRevokeMyApiKey } from '@/lib/api/hooks/identity'
import type { ApiKeyCreated } from '@/lib/api/types'
import { Button } from '@/components/ui/Button'
import { Checkbox, SelectField, TextField } from '@/components/ui/Field'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { ApiKeyList, KeyRevealDialog } from '@/features/keys/ApiKeys'
import { toast } from '@/features/notifications/toastStore'
import { SettingsHeader } from '@/features/settings/SettingsHeader'

export const Route = createFileRoute('/_app/settings/api-keys')({
  staticData: { phone: 'pushed' },
  component: ApiKeysPage,
})

const READ_SCOPES = ['market:read', 'account:read', 'league:read']

/**
 * Personal API keys (§17.2): read-only by default; `trade` only when the admin allows API trading for you. Shown once;
 * all personal keys are revoked when you change your password. Creating one asks for your password (re-auth).
 */
function ApiKeysPage() {
  const keys = useMyApiKeys()
  const create = useCreateMyApiKey()
  const revoke = useRevokeMyApiKey()
  const [name, setName] = useState('')
  const [trade, setTrade] = useState(false)
  const [expires, setExpires] = useState('90')
  const [created, setCreated] = useState<ApiKeyCreated | null>(null)
  const err = create.error ? describeError(create.error) : null

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    create.mutate(
      { name: name.trim(), scopes: trade ? [...READ_SCOPES, 'trade'] : READ_SCOPES, ...(expires ? { expiresInDays: Number(expires) } : {}) },
      {
        onSuccess: (k) => {
          setCreated(k)
          setName('')
          setTrade(false)
        },
      },
    )
  }

  return (
    <div className="space-y-4">
      <SettingsHeader title="API keys" />
      <p className="text-caption text-text-2">
        Use a personal key to read your account from your own scripts or an AI assistant. Keys are read-only unless the league admin enabled API trading for you; trades placed with a key are
        labeled “via API” on the league.
      </p>
      <form onSubmit={submit} className="space-y-3 rounded-lg border border-divider bg-bg-1 p-4">
        <h2 className="text-section font-semibold">New key</h2>
        <div className="grid gap-3 tablet:grid-cols-[1fr_180px]">
          <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} maxLength={64} placeholder="e.g. My notebook" />
          <SelectField
            label="Expires"
            value={expires}
            onChange={(e) => setExpires(e.target.value)}
            options={[
              { value: '30', label: 'In 30 days' },
              { value: '90', label: 'In 90 days' },
              { value: '365', label: 'In 1 year' },
              { value: '', label: 'Never' },
            ]}
          />
        </div>
        <Checkbox checked={trade} onChange={setTrade}>
          Allow trading (needs the admin's permission)
        </Checkbox>
        {err ? (
          <p role="alert" className="rounded-sm bg-down-bg p-2 text-caption text-down">
            {err.title}
            {errorBody(err) ? <span className="block text-text-2">{errorBody(err)}</span> : null}
          </p>
        ) : null}
        <Button type="submit" variant="primary" disabled={!name.trim() || create.isPending}>
          {create.isPending ? 'Creating…' : 'Create key'}
        </Button>
      </form>
      <section className="rounded-lg border border-divider bg-bg-1">
        <h2 className="px-4 pt-3 text-section font-semibold">Your keys</h2>
        {keys.isPending ? (
          <div className="px-4">
            <SkeletonRows rows={2} cols={3} />
          </div>
        ) : (
          <ApiKeyList
            keys={keys.data ?? []}
            revoking={revoke.isPending}
            emptyBody="Create a key above to connect a script or an AI assistant."
            onRevoke={(k) =>
              k.keyId &&
              revoke.mutate(k.keyId, {
                onSuccess: () => toast({ tone: 'neutral', title: `Revoked ${k.name ?? 'key'}` }),
                onError: (e) => toast({ tone: 'error', title: 'Not revoked', body: describeError(e).title }),
              })
            }
          />
        )}
      </section>
      <KeyRevealDialog created={created} onClose={() => setCreated(null)} />
    </div>
  )
}
