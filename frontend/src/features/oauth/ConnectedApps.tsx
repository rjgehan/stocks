import { useState } from 'react'
import type { ConnectedApp } from '@/lib/api/types'
import { describeError } from '@/lib/api/errors'
import { formatDateTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { EmptyState } from '@/components/ui/EmptyState'
import { Pill } from '@/components/ui/Pill'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { IconLink } from '@/components/ui/icons'
import { Avatar } from '@/features/league/PlayerBadge'
import { toast } from '@/features/notifications/toastStore'
import { useConnectedApps, useDisconnectApp } from './api'
import { SCOPE_INFO, clientName, clientOrigin, orderedScopes } from './scopes'

/**
 * Settings → Connected apps (§17.9.1): every live OAuth grant you approved (claude.ai, ChatGPT, Claude Code, …), the
 * player each acts as and its scopes. Disconnect revokes the grant at once: the app's tokens stop on its next request.
 */
export function ConnectedApps() {
  const apps = useConnectedApps()
  const disconnect = useDisconnectApp()
  const [confirm, setConfirm] = useState<ConnectedApp | null>(null)

  if (apps.isPending) {
    return (
      <div className="px-4">
        <SkeletonRows rows={2} cols={3} />
      </div>
    )
  }
  if (apps.error) return <p role="alert" className="px-4 py-3 text-caption text-down">{describeError(apps.error).title}</p>
  const list = apps.data ?? []
  if (!list.length) {
    return (
      <EmptyState
        icon={<IconLink size={28} />}
        title="No connected apps"
        body="Add stocks as a connector in claude.ai or ChatGPT (see the agent guide); after you approve it, it shows up here."
      />
    )
  }
  return (
    <>
      <ul className="divide-y divide-divider" aria-label="Connected apps">
        {list.map((app) => (
          <li key={app.grantId} className="flex flex-wrap items-center gap-x-3 gap-y-2 px-4 py-3">
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-1.5">
                <span className="font-semibold">{clientName(app.client)}</span>
                <span className="text-caption text-text-3">{clientOrigin(app.client)}</span>
              </div>
              <div className="mt-1 flex items-center gap-2 text-caption text-text-2">
                <Avatar player={app.actAs} size={20} />
                <span>
                  Acts as <span className="font-semibold text-text-1">@{app.actAs?.handle}</span>
                  {app.actAs?.kind === 'AI' ? ' (your bot)' : ''}
                </span>
              </div>
              <div className="mt-1 flex flex-wrap gap-1">
                {orderedScopes(app.scopes).map((s) => (
                  <Pill key={s} tone={s === 'trade' ? 'warn' : 'neutral'} title={SCOPE_INFO[s]?.detail}>
                    {SCOPE_INFO[s]?.label ?? s}
                  </Pill>
                ))}
              </div>
              <p className="mt-1 text-micro text-text-3">
                Approved {formatDateTimeEt(app.createdAt)} · last used {app.lastUsedAt ? formatDateTimeEt(app.lastUsedAt) : 'never'} · returns to {app.redirectHost}
              </p>
            </div>
            <Button size="sm" variant="danger" onClick={() => setConfirm(app)} disabled={disconnect.isPending} aria-label={`Disconnect ${clientName(app.client)}`}>
              Disconnect
            </Button>
          </li>
        ))}
      </ul>
      <ConfirmDialog
        open={confirm !== null}
        onClose={() => setConfirm(null)}
        title={`Disconnect ${clientName(confirm?.client)}?`}
        confirmLabel="Disconnect"
        tone="danger"
        pending={disconnect.isPending}
        onConfirm={() => {
          const app = confirm
          setConfirm(null)
          if (!app?.grantId) return
          disconnect.mutate(app.grantId, {
            onSuccess: () => toast({ tone: 'neutral', title: `Disconnected ${clientName(app.client)}` }),
            onError: (e) => toast({ tone: 'error', title: 'Not disconnected', body: describeError(e).title }),
          })
        }}
      >
        It stops working immediately. To use it again, connect it again from the app.
      </ConfirmDialog>
    </>
  )
}
