import { useMemo, useState } from 'react'
import { Link, createFileRoute } from '@tanstack/react-router'
import { useAdminPlayers } from '@/lib/api/hooks/admin'
import { useMe } from '@/lib/api/hooks/identity'
import type { AdminPlayer, ApiKeyCreated } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'
import { formatPct, moneyDirection, pctDirection } from '@/lib/format/number'
import { formatDateTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { Pill, type PillTone } from '@/components/ui/Pill'
import { Segmented } from '@/components/ui/Segmented'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { IconPlus } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'
import { CreatePlayerDialog } from '@/features/admin/CreatePlayerDialog'
import { IssueKeyDialog } from '@/features/admin/IssueKeyDialog'
import { PlayerActions } from '@/features/admin/PlayerActions'
import { KeyRevealDialog } from '@/features/keys/ApiKeys'
import { AiChip, Avatar } from '@/features/league/PlayerBadge'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { toast } from '@/features/notifications/toastStore'

export const Route = createFileRoute('/_app/admin/players/')({
  component: PlayersPage,
})

const STATUS_TONE: Record<string, PillTone> = { ACTIVE: 'up', FROZEN: 'warn', DISABLED: 'flat' }

function PlayerCell({ p }: { p: AdminPlayer }) {
  return (
    <Link to="/admin/players/$handle" params={{ handle: p.handle ?? '' }} className="flex min-h-11 min-w-0 items-center gap-2.5 hover:text-accent">
      <Avatar player={p} size={30} />
      <span className="min-w-0">
        <span className="flex flex-wrap items-center gap-1.5">
          <span className="truncate font-semibold">{p.displayName}</span>
          {p.kind === 'AI' ? <AiChip model={p.aiModel} /> : null}
          {p.role === 'ADMIN' ? <Pill tone="accent">Admin</Pill> : null}
        </span>
        <span className="block truncate text-caption text-text-3">
          @{p.handle}
          {p.ownerHandle ? ` · run by @${p.ownerHandle}` : ''}
        </span>
      </span>
    </Link>
  )
}

/** An AI bot's manual console (§20.8). */
function ConsoleLink({ p }: { p: AdminPlayer }) {
  if (p.kind !== 'AI' || !p.handle) return null
  return (
    <Link
      to="/bots/$handle"
      params={{ handle: p.handle }}
      aria-label={`Console of @${p.handle}`}
      className="inline-flex min-h-11 shrink-0 items-center px-1 text-caption font-semibold text-accent hover:text-accent-hover tablet:min-h-0"
    >
      Console
    </Link>
  )
}

/** Admin · Players (§21.3): every account with its balances, status, keys and last activity; create humans and bots. */
function PlayersPage() {
  const { data, isPending, isError } = useAdminPlayers()
  const { data: me } = useMe()
  const [kind, setKind] = useState<'ALL' | 'HUMAN' | 'AI'>('ALL')
  const [creating, setCreating] = useState(false)
  const [revealed, setRevealed] = useState<{ key: ApiKeyCreated; handle: string } | null>(null)
  const [issueFor, setIssueFor] = useState<string | null>(null)
  const players = useMemo(() => (data ?? []).filter((p) => kind === 'ALL' || p.kind === kind), [data, kind])

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <Segmented
          label="Kind"
          size="sm"
          value={kind}
          onChange={setKind}
          options={[
            { value: 'ALL', label: `All${data ? ` (${data.length})` : ''}` },
            { value: 'HUMAN', label: 'Humans' },
            { value: 'AI', label: 'AI bots' },
          ]}
        />
        <div className="flex-1" />
        <Link to="/admin/invites" className="inline-flex min-h-11 items-center text-caption font-semibold text-accent hover:text-accent-hover tablet:min-h-0">
          Invite friends
        </Link>
        <Button variant="primary" size="sm" onClick={() => setCreating(true)}>
          <IconPlus size={16} /> Add player
        </Button>
      </div>

      {isPending ? (
        <div className="rounded-lg border border-divider bg-bg-1 px-4">
          <SkeletonRows rows={5} cols={6} />
        </div>
      ) : isError ? (
        <EmptyState title="Players are unavailable right now" />
      ) : players.length === 0 ? (
        <EmptyState title="No players yet" body="Invite friends or add an AI bot." />
      ) : (
        <>
          <ul className="divide-y divide-divider rounded-lg border border-divider bg-bg-1 desktop:hidden" aria-label="Players">
            {players.map((p) => (
              <li key={p.handle} className="flex items-center gap-2 px-3 py-3">
                <div className="min-w-0 flex-1">
                  <PlayerCell p={p} />
                  <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-0.5 pl-10 text-caption">
                    <Pill tone={p.status ? STATUS_TONE[p.status] : 'neutral'}>{p.status}</Pill>
                    <span>{formatMoney(p.equity)}</span>
                    <span className={DIRECTION_TEXT[pctDirection(p.return)]}>{formatPct(p.return)}</span>
                    <span className="text-text-3">NC {formatMoney(p.netContributions)}</span>
                  </div>
                </div>
                <ConsoleLink p={p} />
                <PlayerActions p={p} myHandle={me?.handle} onIssueKey={setIssueFor} />
              </li>
            ))}
          </ul>
          <div className="hidden overflow-x-auto rounded-lg border border-divider bg-bg-1 desktop:block">
            <table className="w-full text-table" aria-label="Players">
              <thead className="text-left text-micro tracking-wide text-text-3 uppercase">
                <tr className="h-9 border-b border-divider">
                  <th className="pl-4 font-medium">Player</th>
                  <th className="px-2 font-medium">Status</th>
                  <th className="px-2 text-right font-medium">Equity</th>
                  <th className="px-2 text-right font-medium">Cash</th>
                  <th className="px-2 text-right font-medium">Net contrib.</th>
                  <th className="px-2 text-right font-medium">P&amp;L</th>
                  <th className="px-2 text-right font-medium">Return</th>
                  <th className="px-2 text-right font-medium">Open</th>
                  <th className="px-2 text-right font-medium">Keys</th>
                  <th className="px-2 font-medium">Last seen</th>
                  <th className="pr-3"><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-divider">
                {players.map((p) => (
                  <tr key={p.handle} className={cx('h-14 hover:bg-bg-2/50', p.status === 'DISABLED' && 'opacity-60')}>
                    <td className="max-w-[280px] py-2 pl-4">
                      <PlayerCell p={p} />
                    </td>
                    <td className="px-2">
                      <Pill tone={p.status ? STATUS_TONE[p.status] : 'neutral'}>{p.status}</Pill>
                      {p.restriction && p.restriction !== 'NONE' && p.restriction !== 'FROZEN' ? (
                        <Pill tone="warn" className="ml-1">
                          {p.restriction.replaceAll('_', ' ').toLowerCase()}
                        </Pill>
                      ) : null}
                    </td>
                    <td className="px-2 text-right font-medium">{formatMoney(p.equity)}</td>
                    <td className="px-2 text-right">{formatMoney(p.cash)}</td>
                    <td className="px-2 text-right">{formatMoney(p.netContributions)}</td>
                    <td className={cx('px-2 text-right', DIRECTION_TEXT[moneyDirection(p.pnl)])}>{formatMoney(p.pnl, { signed: true })}</td>
                    <td className={cx('px-2 text-right font-semibold', DIRECTION_TEXT[pctDirection(p.return)])}>{formatPct(p.return)}</td>
                    <td className="px-2 text-right">{p.openOrders}</td>
                    <td className="px-2 text-right">{p.activeKeys}</td>
                    <td className="px-2 text-caption text-text-3">{p.lastSeenAt ? formatDateTimeEt(p.lastSeenAt) : 'never'}</td>
                    <td className="pr-3 text-right whitespace-nowrap">
                      <ConsoleLink p={p} />
                      <PlayerActions p={p} myHandle={me?.handle} onIssueKey={setIssueFor} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      <CreatePlayerDialog
        open={creating}
        onClose={() => setCreating(false)}
        onCreated={(res) => {
          setCreating(false)
          toast({ tone: 'up', title: `Created @${res.player?.handle ?? ''}`, body: `Funded with ${formatMoney(res.player?.netContributions)}.` })
          if (res.apiKey) setRevealed({ key: res.apiKey, handle: res.player?.handle ?? '' })
        }}
      />
      <KeyRevealDialog created={revealed?.key ?? null} owner={revealed?.handle} onClose={() => setRevealed(null)} />
      <IssueKeyDialog handle={issueFor} onClose={() => setIssueFor(null)} />
    </div>
  )
}
