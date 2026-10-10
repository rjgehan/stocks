import { useState } from 'react'
import { createFileRoute } from '@tanstack/react-router'
import { describeError } from '@/lib/api/errors'
import { useCreateInvite, useInvites, useRevokeInvite } from '@/lib/api/hooks/admin'
import type { Invite, InviteCreated } from '@/lib/api/types'
import { formatDateTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { CopyField, copyText } from '@/components/ui/CopyField'
import { EmptyState } from '@/components/ui/EmptyState'
import { SelectField } from '@/components/ui/Field'
import { Pill } from '@/components/ui/Pill'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { IconLink } from '@/components/ui/icons'
import { toast } from '@/features/notifications/toastStore'

export const Route = createFileRoute('/_app/admin/invites')({
  component: InvitesPage,
})

/** The shareable link: the server's `url`, or `<origin>/join/<code>` (§22.2) when it sends none. */
function inviteLink(created: InviteCreated): string {
  if (created.url && /^https?:\/\//.test(created.url)) return created.url
  return `${window.location.origin}/join/${encodeURIComponent(created.code ?? '')}`
}

/** The native share sheet on phones; copy to the clipboard elsewhere (or when sharing is cancelled). */
async function share(link: string) {
  if (navigator.share) {
    try {
      await navigator.share({ title: 'Join our stocks league', text: 'Paper-trade the real market with us (fake money).', url: link })
      return
    } catch {
      /* cancelled: fall back to copy */
    }
  }
  if (await copyText(link)) toast({ tone: 'neutral', title: 'Invite link copied' })
}

function inviteState(i: Invite): { label: string; tone: 'up' | 'flat' | 'down' | 'warn' } {
  if (i.revokedAt) return { label: 'Revoked', tone: 'down' }
  if (i.expiresAt && Date.parse(i.expiresAt) < Date.now()) return { label: 'Expired', tone: 'flat' }
  if (i.uses >= i.maxUses) return { label: 'Used up', tone: 'warn' }
  return { label: 'Active', tone: 'up' }
}

/** Admin · Invites (§21.3): create a signup link (copy and share), list, revoke. Signups start with the league's starting cash. */
function InvitesPage() {
  const invites = useInvites()
  const create = useCreateInvite()
  const revoke = useRevokeInvite()
  const [maxUses, setMaxUses] = useState('5')
  const [hours, setHours] = useState('168')
  const [created, setCreated] = useState<InviteCreated | null>(null)
  const [revoking, setRevoking] = useState<Invite | null>(null)
  return (
    <div className="space-y-4">
      <section className="space-y-3 rounded-lg border border-divider bg-bg-1 p-4">
        <h2 className="text-section font-semibold">New invite link</h2>
        <p className="text-caption text-text-2">Anyone with the link can create an account until it expires or runs out of uses. Each new player starts with the league's starting cash.</p>
        <div className="grid gap-3 tablet:grid-cols-[1fr_1fr_auto] tablet:items-end">
          <SelectField
            label="Uses"
            value={maxUses}
            onChange={(e) => setMaxUses(e.target.value)}
            options={['1', '2', '5', '10', '25', '50'].map((v) => ({ value: v, label: v === '1' ? '1 person' : `${v} people` }))}
          />
          <SelectField
            label="Expires"
            value={hours}
            onChange={(e) => setHours(e.target.value)}
            options={[
              { value: '24', label: 'In 1 day' },
              { value: '72', label: 'In 3 days' },
              { value: '168', label: 'In 1 week' },
              { value: '720', label: 'In 30 days' },
            ]}
          />
          <Button
            variant="primary"
            size="lg"
            disabled={create.isPending}
            onClick={() =>
              create.mutate(
                { maxUses: Number(maxUses), expiresInHours: Number(hours) },
                { onSuccess: setCreated, onError: (e) => toast({ tone: 'error', title: 'Invite not created', body: describeError(e).title }) },
              )
            }
          >
            <IconLink size={18} /> Create link
          </Button>
        </div>
        {created ? (
          <div className="space-y-2 rounded-md border border-up/30 bg-up-bg p-3">
            <p className="text-caption font-semibold text-text-1">Share this link{created.expiresAt ? ` (expires ${formatDateTimeEt(created.expiresAt)})` : ''}:</p>
            <CopyField label="Invite link" value={inviteLink(created)} />
            <div className="flex gap-2">
              <Button size="sm" onClick={() => void share(inviteLink(created))}>
                Share…
              </Button>
              <span className="self-center font-mono text-caption text-text-3">code {created.code}</span>
            </div>
          </div>
        ) : null}
      </section>

      <section className="rounded-lg border border-divider bg-bg-1">
        <h2 className="px-4 pt-3 pb-1 text-section font-semibold">Invites</h2>
        {invites.isPending ? (
          <div className="px-4">
            <SkeletonRows rows={3} cols={4} />
          </div>
        ) : (invites.data ?? []).length === 0 ? (
          <EmptyState title="No invites yet" body="Create a link above and send it to your friends." />
        ) : (
          <ul className="divide-y divide-divider">
            {(invites.data ?? []).map((i) => {
              const st = inviteState(i)
              return (
                <li key={i.inviteId} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3">
                  <Pill tone={st.tone}>{st.label}</Pill>
                  <span className="font-mono text-caption text-text-2">{i.inviteId}</span>
                  <span className="text-table">
                    {i.uses}/{i.maxUses} used
                  </span>
                  <span className="text-caption text-text-3">{i.revokedAt ? `revoked ${formatDateTimeEt(i.revokedAt)}` : `expires ${formatDateTimeEt(i.expiresAt)}`}</span>
                  <span className="flex-1" />
                  {st.label === 'Active' ? (
                    <Button size="sm" variant="danger" onClick={() => setRevoking(i)}>
                      Revoke
                    </Button>
                  ) : null}
                </li>
              )
            })}
          </ul>
        )}
      </section>
      <ConfirmDialog
        open={revoking !== null}
        onClose={() => setRevoking(null)}
        title="Revoke this invite?"
        confirmLabel="Revoke"
        tone="danger"
        pending={revoke.isPending}
        onConfirm={() =>
          revoking?.inviteId &&
          revoke.mutate(revoking.inviteId, {
            onSuccess: () => {
              setRevoking(null)
              toast({ tone: 'neutral', title: 'Invite revoked' })
            },
          })
        }
      >
        The link stops working immediately. Players who already joined keep their accounts.
      </ConfirmDialog>
    </div>
  )
}
