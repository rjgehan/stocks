import { useState } from 'react'
import type { ApiKeyCreated, ApiKeyInfo } from '@/lib/api/types'
import { formatDateTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { CopyField } from '@/components/ui/CopyField'
import { Dialog } from '@/components/ui/Dialog'
import { EmptyState } from '@/components/ui/EmptyState'
import { Pill } from '@/components/ui/Pill'
import { Tabs } from '@/components/ui/Tabs'
import { IconKey } from '@/components/ui/icons'

const SNIPPETS = [
  { value: 'claudeCode', label: 'Claude Code' },
  { value: 'claudeApi', label: 'Claude API' },
  { value: 'openaiResponses', label: 'OpenAI' },
  { value: 'curl', label: 'curl' },
] as const
type SnippetKey = (typeof SNIPPETS)[number]['value']

/**
 * The copy-once key dialog (§21.3 IssueKeyDialog): the token is shown exactly once, with ready-to-paste connection
 * snippets for Claude Code, the Claude API MCP connector, the OpenAI Responses API and curl (§20.4).
 */
export function KeyRevealDialog({ created, onClose, owner }: { created: ApiKeyCreated | null; onClose: () => void; owner?: string }) {
  const [tab, setTab] = useState<SnippetKey>('claudeCode')
  const [ack, setAck] = useState(false)
  const close = () => {
    setAck(false)
    onClose()
  }
  const snippet = created?.snippets?.[tab]
  return (
    <Dialog open={created !== null} onClose={close} title="Copy this key now">
      {created ? (
        <div className="space-y-4 p-4">
          <p className="rounded-md bg-warn/10 p-3 text-caption text-warn">
            This is the only time the full key is shown{owner ? ` for @${owner}` : ''}. Store it in a password manager or your bot's secret store; if you lose it, revoke it and issue a new one.
          </p>
          <div>
            <p className="mb-1 text-caption font-medium text-text-2">
              {created.key?.name} · <span className="font-mono">{created.key?.keyId}</span>
            </p>
            <CopyField label="API key" value={created.token ?? ''} />
            {created.key?.scopes?.length ? (
              <div className="mt-2 flex flex-wrap gap-1">
                {created.key.scopes.map((s) => (
                  <Pill key={s} tone={s === 'trade' ? 'warn' : 'neutral'}>
                    {s}
                  </Pill>
                ))}
              </div>
            ) : null}
          </div>
          {created.snippets ? (
            <div>
              <Tabs label="Connection snippets" value={tab} onChange={setTab} items={SNIPPETS} />
              <div className="pt-2">{snippet ? <CopyField label={`${SNIPPETS.find((s) => s.value === tab)?.label} snippet`} value={snippet} multiline /> : null}</div>
            </div>
          ) : null}
          <label className="flex items-center gap-2 text-caption text-text-1">
            <input type="checkbox" checked={ack} onChange={(e) => setAck(e.target.checked)} className="size-4 accent-[var(--accent)]" />
            I copied the key
          </label>
          <Button variant="primary" size="lg" block disabled={!ack} onClick={close}>
            Done
          </Button>
        </div>
      ) : null}
    </Dialog>
  )
}

/** A list of API keys with Revoke (§17.2): id, scopes, created, last used, expiry. */
export function ApiKeyList({ keys, onRevoke, revoking, emptyBody }: { keys: ApiKeyInfo[]; onRevoke: (key: ApiKeyInfo) => void; revoking?: boolean; emptyBody?: string }) {
  const [confirm, setConfirm] = useState<ApiKeyInfo | null>(null)
  const [now] = useState(() => Date.now())
  if (!keys.length) return <EmptyState icon={<IconKey size={28} />} title="No API keys" body={emptyBody} />
  const sorted = [...keys].sort((a, b) => Number(Boolean(a.revokedAt)) - Number(Boolean(b.revokedAt)) || (b.createdAt ?? '').localeCompare(a.createdAt ?? ''))
  return (
    <>
      <ul className="divide-y divide-divider">
        {sorted.map((k) => {
          const expired = k.expiresAt ? Date.parse(k.expiresAt) < now : false
          const dead = Boolean(k.revokedAt) || expired
          return (
            <li key={k.keyId} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className="font-semibold">{k.name}</span>
                  <span className="font-mono text-caption text-text-3">stk_{k.keyId}_…</span>
                  {k.revokedAt ? <Pill tone="down">Revoked</Pill> : expired ? <Pill tone="flat">Expired</Pill> : <Pill tone="up">Active</Pill>}
                </div>
                <div className="mt-0.5 flex flex-wrap gap-1">
                  {(k.scopes ?? []).map((s) => (
                    <Pill key={s} tone={s === 'trade' ? 'warn' : 'neutral'}>
                      {s}
                    </Pill>
                  ))}
                </div>
                <p className="mt-0.5 text-micro text-text-3">
                  Created {formatDateTimeEt(k.createdAt)} · last used {k.lastUsedAt ? `${formatDateTimeEt(k.lastUsedAt)}${k.lastUsedIp ? ` from ${k.lastUsedIp}` : ''}` : 'never'}
                  {k.expiresAt ? ` · expires ${formatDateTimeEt(k.expiresAt)}` : ''}
                  {k.revokedAt ? ` · revoked ${formatDateTimeEt(k.revokedAt)}` : ''}
                </p>
              </div>
              {!dead ? (
                <Button size="sm" variant="danger" onClick={() => setConfirm(k)} disabled={revoking}>
                  Revoke
                </Button>
              ) : null}
            </li>
          )
        })}
      </ul>
      <ConfirmDialog
        open={confirm !== null}
        onClose={() => setConfirm(null)}
        title={`Revoke “${confirm?.name ?? ''}”?`}
        confirmLabel="Revoke key"
        tone="danger"
        pending={revoking}
        onConfirm={() => {
          if (confirm) onRevoke(confirm)
          setConfirm(null)
        }}
      >
        Anything using this key stops working immediately. This cannot be undone.
      </ConfirmDialog>
    </>
  )
}
