import { useState } from 'react'
import { useApiCalls } from '@/lib/api/hooks/agents'
import { useMe } from '@/lib/api/hooks/identity'
import { isApiError } from '@/lib/api/problem'
import type { ApiCall } from '@/lib/api/types'
import { formatDateTimeEt, formatTimeEt } from '@/lib/format/time'
import { useDebounced } from '@/lib/useDebounced'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { TextField } from '@/components/ui/Field'
import { Pill } from '@/components/ui/Pill'
import { Segmented } from '@/components/ui/Segmented'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { IconActivity } from '@/components/ui/icons'
import { channelLabel, outcomeTone } from './agentView'

function CallRow({ c, showPlayer }: { c: ApiCall; showPlayer: boolean }) {
  return (
    <li className="px-4 py-2.5">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-table">
        <Pill tone={channelLabel(c.channel) === 'MCP' ? 'ai' : 'accent'}>{channelLabel(c.channel)}</Pill>
        <span className="min-w-0 font-mono text-caption break-all text-text-1">{c.endpoint}</span>
        <Pill tone={outcomeTone(c)} title={c.ok ? 'Succeeded' : 'Failed'}>
          {c.ok ? '✓' : '✕'} {c.outcome}
        </Pill>
        <span className="ml-auto text-caption whitespace-nowrap text-text-3">
          last {formatTimeEt(c.lastAt)} <span className="hidden tablet:inline">· hour of {formatDateTimeEt(c.hourStart)}</span>
        </span>
      </div>
      <p className="mt-0.5 text-caption text-text-2">
        <span className="font-semibold text-text-1">{c.calls}</span> call{c.calls === 1 ? '' : 's'} · avg {c.avgMs} ms · max {c.maxMs} ms
        {showPlayer ? (
          <>
            {' '}
            · <span className="font-semibold text-text-1">@{c.handle}</span>
            {c.playerKind === 'AI' ? ' (bot)' : ''}
          </>
        ) : null}
        {' · '}key {c.keyName ? `${c.keyName} ` : ''}
        <span className="font-mono">{c.keyId}</span>
      </p>
      {c.lastIp || c.client ? (
        <p className="mt-0.5 text-micro break-all text-text-3">
          {c.lastIp ?? ''}
          {c.lastIp && c.client ? ' · ' : ''}
          {c.client ?? ''}
        </p>
      ) : null}
    </li>
  )
}

/**
 * Recent API calls (§17.10): hourly buckets of what your API keys and your bots did (REST and MCP), newest first.
 * The admin can switch to every player's ("Everyone"). Kept 90 days.
 */
export function ApiCallsList() {
  const { data: me } = useMe()
  const isAdmin = me?.role === 'ADMIN'
  const [scope, setScope] = useState<'mine' | 'everyone'>('mine')
  const [player, setPlayer] = useState('')
  const handle = useDebounced(player.trim().replace(/^@/, '').toLowerCase(), 300)
  const q = useApiCalls(isAdmin ? scope : 'mine', handle)
  const rows = (q.data?.pages ?? []).flatMap((p) => p.items ?? [])
  const notFound = q.isError && isApiError(q.error) && q.error.code === 'PLAYER_NOT_FOUND'
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-end gap-3">
        {isAdmin ? (
          <Segmented
            label="Whose calls"
            size="sm"
            fit
            value={scope}
            onChange={setScope}
            options={[
              { value: 'mine', label: 'Mine' },
              { value: 'everyone', label: 'Everyone' },
            ]}
          />
        ) : null}
        <TextField
          label={scope === 'everyone' && isAdmin ? 'Player' : 'Player (you or one of your bots)'}
          value={player}
          onChange={(e) => setPlayer(e.target.value)}
          placeholder="handle"
          autoCapitalize="none"
          className="min-w-48 flex-1 tablet:max-w-xs"
        />
      </div>
      <section className="rounded-lg border border-divider bg-bg-1">
        {q.isPending ? (
          <div className="px-4">
            <SkeletonRows rows={5} cols={3} />
          </div>
        ) : notFound ? (
          <EmptyState title={`No player @${handle}${scope === 'mine' ? ' among yours' : ''}`} />
        ) : q.isError ? (
          <EmptyState title="Recent API calls are unavailable right now" />
        ) : rows.length === 0 ? (
          <EmptyState
            icon={<IconActivity size={28} />}
            title="No API calls yet"
            body="Calls made with your API keys, and those of the bots you own, appear here within seconds."
          />
        ) : (
          <ul aria-label="API calls" className="divide-y divide-divider">
            {rows.map((c) => (
              <CallRow key={c.id} c={c} showPlayer={scope === 'everyone' || c.handle !== me?.handle} />
            ))}
          </ul>
        )}
        {q.hasNextPage ? (
          <div className="flex justify-center border-t border-divider p-3">
            <Button size="sm" onClick={() => void q.fetchNextPage()} disabled={q.isFetchingNextPage}>
              Load more
            </Button>
          </div>
        ) : null}
      </section>
      <p className="text-micro text-text-3">One row per key, endpoint and result per hour. Bodies and query strings are never logged.</p>
    </div>
  )
}
