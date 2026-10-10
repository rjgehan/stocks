import { useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { describeError, errorBody } from '@/lib/api/errors'
import { useFreezePlayer, useUnfreezePlayer, useUpdatePlayer } from '@/lib/api/hooks/admin'
import type { AdminPlayer } from '@/lib/api/types'
import { IconButton } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { Checkbox, TextField } from '@/components/ui/Field'
import { MenuItem, Popover } from '@/components/ui/Popover'
import { IconMore } from '@/components/ui/icons'
import { toast } from '@/features/notifications/toastStore'

type Pending = null | 'freeze' | 'unfreeze' | 'disable' | 'enable' | 'promote' | 'demote'

const onError = (title: string) => (e: unknown) => {
  const d = describeError(e)
  toast({ tone: 'error', title, body: errorBody(d) ?? d.title })
}

/**
 * Per-player admin actions (§21.3 Players): open, deposit, issue a key, freeze / unfreeze (kill switch, optional key
 * revocation), disable / enable, promote a human to admin. Every one of them is audited server-side.
 */
export function PlayerActions({ p, myHandle, onIssueKey }: { p: AdminPlayer; myHandle: string | null | undefined; onIssueKey: (handle: string) => void }) {
  const navigate = useNavigate()
  const update = useUpdatePlayer()
  const freeze = useFreezePlayer()
  const unfreeze = useUnfreezePlayer()
  const [pending, setPending] = useState<Pending>(null)
  const [reason, setReason] = useState('')
  const [revokeKeys, setRevokeKeys] = useState(false)
  const handle = p.handle ?? ''
  const self = handle === myHandle
  const done = (msg: string) => () => {
    setPending(null)
    setReason('')
    setRevokeKeys(false)
    toast({ tone: 'neutral', title: msg })
  }
  const run = () => {
    switch (pending) {
      case 'freeze':
        return freeze.mutate({ handle, reason: reason.trim(), revokeKeys }, { onSuccess: done(`@${handle} frozen`), onError: onError('Not frozen') })
      case 'unfreeze':
        return unfreeze.mutate(handle, { onSuccess: done(`@${handle} unfrozen`), onError: onError('Not unfrozen') })
      case 'disable':
        return update.mutate({ handle, patch: { status: 'DISABLED' } }, { onSuccess: done(`@${handle} disabled`), onError: onError('Not disabled') })
      case 'enable':
        return update.mutate({ handle, patch: { status: 'ACTIVE' } }, { onSuccess: done(`@${handle} enabled`), onError: onError('Not enabled') })
      case 'promote':
        return update.mutate({ handle, patch: { role: 'ADMIN' } }, { onSuccess: done(`@${handle} is now an admin`), onError: onError('Not promoted') })
      case 'demote':
        return update.mutate({ handle, patch: { role: 'PLAYER' } }, { onSuccess: done(`@${handle} is no longer an admin`), onError: onError('Not changed') })
    }
  }
  const copy: Record<Exclude<Pending, null>, { title: string; body: string; cta: string; danger?: boolean }> = {
    freeze: { title: `Freeze @${handle}?`, body: 'Their open orders are cancelled and they cannot trade until unfrozen. They can still sign in and see their account.', cta: 'Freeze', danger: true },
    unfreeze: { title: `Unfreeze @${handle}?`, body: 'They can trade again (any good-faith restriction is restored).', cta: 'Unfreeze' },
    disable: { title: `Disable @${handle}?`, body: 'They are signed out, cannot sign in or use API keys, and disappear from the league.', cta: 'Disable', danger: true },
    enable: { title: `Enable @${handle}?`, body: 'They can sign in and trade again.', cta: 'Enable' },
    promote: { title: `Make @${handle} an admin?`, body: 'Admins can move money, create players and see the audit log. Their sessions are reset.', cta: 'Promote' },
    demote: { title: `Remove admin from @${handle}?`, body: 'They keep their account and trading access.', cta: 'Remove admin', danger: true },
  }
  const busy = update.isPending || freeze.isPending || unfreeze.isPending
  return (
    <>
      <Popover
        label={`Actions for @${handle}`}
        renderTrigger={(props) => (
          <IconButton label={`Actions for @${handle}`} size="sm" {...props}>
            <IconMore size={18} />
          </IconButton>
        )}
      >
        {(close) => (
          <>
            <MenuItem onClick={() => (close(), void navigate({ to: '/admin/players/$handle', params: { handle } }))}>Open details</MenuItem>
            <MenuItem onClick={() => (close(), void navigate({ to: '/admin/money', search: { handle } }))}>Deposit / withdraw…</MenuItem>
            {p.kind === 'AI' ? <MenuItem onClick={() => (close(), onIssueKey(handle))}>Issue API key…</MenuItem> : null}
            {p.status === 'FROZEN' ? (
              <MenuItem onClick={() => (close(), setPending('unfreeze'))}>Unfreeze</MenuItem>
            ) : (
              <MenuItem tone="danger" disabled={self} onClick={() => (close(), setPending('freeze'))}>
                Freeze (kill switch)…
              </MenuItem>
            )}
            {p.status === 'DISABLED' ? (
              <MenuItem onClick={() => (close(), setPending('enable'))}>Enable</MenuItem>
            ) : (
              <MenuItem tone="danger" disabled={self} onClick={() => (close(), setPending('disable'))}>
                Disable…
              </MenuItem>
            )}
            {p.kind === 'HUMAN' && p.role !== 'ADMIN' ? <MenuItem onClick={() => (close(), setPending('promote'))}>Make admin…</MenuItem> : null}
            {p.role === 'ADMIN' && !self ? (
              <MenuItem tone="danger" onClick={() => (close(), setPending('demote'))}>
                Remove admin…
              </MenuItem>
            ) : null}
          </>
        )}
      </Popover>
      <ConfirmDialog
        open={pending !== null}
        onClose={() => setPending(null)}
        title={pending ? copy[pending].title : ''}
        confirmLabel={pending ? copy[pending].cta : 'Confirm'}
        tone={pending && copy[pending].danger ? 'danger' : 'primary'}
        pending={busy}
        onConfirm={() => {
          if (pending === 'freeze' && reason.trim().length < 3) return
          run()
        }}
      >
        <p>{pending ? copy[pending].body : ''}</p>
        {pending === 'freeze' ? (
          <div className="space-y-2 pt-1">
            <TextField label="Reason (audited)" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={200} placeholder="e.g. Runaway bot loop" />
            <Checkbox checked={revokeKeys} onChange={setRevokeKeys}>
              Also revoke all of their API keys
            </Checkbox>
          </div>
        ) : null}
      </ConfirmDialog>
    </>
  )
}
