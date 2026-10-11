import { useState } from 'react'
import { describeError } from '@/lib/api/errors'
import { useAdminOAuthGrants, useAdminRevokeOAuthGrant } from '@/lib/api/hooks/admin'
import type { AdminOAuthGrant } from '@/lib/api/types'
import { formatDateTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { Pill } from '@/components/ui/Pill'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { SCOPE_INFO, clientName, clientOrigin, orderedScopes } from '@/features/oauth/scopes'
import { toast } from '@/features/notifications/toastStore'

/**
 * Admin → Player → Connected apps (§21.1, §21.3): the player's live OAuth connectors (claude.ai, ChatGPT, Claude
 * Code…), those acting as the player and those the player approved, with revoke. Like API keys, so the kill switch
 * reaches connectors too (a freeze with "revoke keys" ends them as well).
 */
export function OAuthGrantsSection({ handle }: { handle: string }) {
  const q = useAdminOAuthGrants(handle)
  const revoke = useAdminRevokeOAuthGrant()
  const [confirm, setConfirm] = useState<AdminOAuthGrant | null>(null)
  const grants = q.data ?? []
  return (
    <section className="rounded-lg border border-divider bg-bg-1" aria-label="Connected apps">
      <div className="px-4 pt-3 pb-1">
        <h2 className="text-section font-semibold">Connected apps</h2>
        <p className="text-micro text-text-3">OAuth connectors acting as this player or approved by them. Revoking ends one at once.</p>
      </div>
      {q.isPending ? (
        <div className="px-4">
          <SkeletonRows rows={2} cols={3} />
        </div>
      ) : grants.length === 0 ? (
        <p className="px-4 pb-3 text-caption text-text-3">{q.isError ? 'Connected apps are unavailable right now.' : 'No connected apps.'}</p>
      ) : (
        <ul className="divide-y divide-divider" aria-label="Connected apps of this player">
          {grants.map((g) => (
            <li key={g.grantId} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5 text-table">
              <div className="min-w-0 flex-1">
                <p className="font-semibold">
                  {clientName(g.client)} <span className="font-normal text-text-3">· {clientOrigin(g.client)} → {g.redirectHost}</span>
                </p>
                <p className="text-caption text-text-2">
                  Acts as @{g.actAs?.handle ?? '?'} · approved by @{g.approvedBy?.handle ?? '?'} · {g.lastUsedAt ? `last used ${formatDateTimeEt(g.lastUsedAt)}` : g.connected ? 'not used yet' : 'sign-in not completed'}
                </p>
                <p className="mt-1 flex flex-wrap gap-1">
                  {orderedScopes(g.scopes).map((s) => (
                    <Pill key={s} tone={s === 'trade' ? 'warn' : 'neutral'}>
                      {SCOPE_INFO[s]?.label ?? s}
                    </Pill>
                  ))}
                </p>
              </div>
              <Button size="sm" variant="danger" onClick={() => setConfirm(g)} aria-label={`Revoke ${clientName(g.client)} acting as @${g.actAs?.handle ?? ''}`}>
                Revoke
              </Button>
            </li>
          ))}
        </ul>
      )}
      <ConfirmDialog
        open={confirm !== null}
        onClose={() => setConfirm(null)}
        title={`Revoke ${clientName(confirm?.client)}?`}
        confirmLabel="Revoke"
        tone="danger"
        pending={revoke.isPending}
        onConfirm={() => {
          const g = confirm
          setConfirm(null)
          if (!g?.grantId) return
          revoke.mutate(
            { grantId: g.grantId, handle },
            {
              onSuccess: () => toast({ tone: 'neutral', title: `Revoked ${clientName(g.client)}` }),
              onError: (e) => toast({ tone: 'error', title: 'Not revoked', body: describeError(e).title }),
            },
          )
        }}
      >
        It stops on its next request, and its owner has to connect it again. Audited.
      </ConfirmDialog>
    </section>
  )
}
