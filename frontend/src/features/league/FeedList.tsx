import { useMemo, useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useFeed, useLiveFeed } from '@/lib/api/hooks/league'
import type { FeedItem } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'
import { formatPrice, formatQty, moneyDirection } from '@/lib/format/number'
import { formatTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { Segmented } from '@/components/ui/Segmented'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { IconBolt, IconCash, IconRotate, IconTrophy, IconUserPlus } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { AiChip, Avatar } from './PlayerBadge'

type Kind = NonNullable<FeedItem['kind']>
export type FeedFilter = 'ALL' | 'TRADES' | 'LEADERS' | 'MONEY' | 'PLAYERS'

/** Which kinds each filter chip shows. */
export const FEED_FILTER_KINDS: Record<FeedFilter, readonly Kind[] | null> = {
  ALL: null,
  TRADES: ['FILL', 'POSITION_CLOSED'],
  LEADERS: ['NEW_LEADER'],
  MONEY: ['DEPOSIT'],
  PLAYERS: ['JOINED', 'RESET'],
}

const KIND_ICON: Record<Kind, { icon: (p: { size?: number }) => React.ReactNode; className: string; label: string }> = {
  FILL: { icon: IconBolt, className: 'bg-accent-bg text-accent', label: 'Trade' },
  POSITION_CLOSED: { icon: IconBolt, className: 'bg-bg-2 text-text-2', label: 'Closed a position' },
  NEW_LEADER: { icon: IconTrophy, className: 'bg-gold/15 text-gold', label: 'New leader' },
  RESET: { icon: IconRotate, className: 'bg-bg-2 text-text-2', label: 'Reset' },
  DEPOSIT: { icon: IconCash, className: 'bg-up-bg text-up', label: 'Deposit' },
  JOINED: { icon: IconUserPlus, className: 'bg-human/15 text-human', label: 'Joined' },
}

const PERIOD_WORD: Record<string, string> = { TODAY: 'today', WEEK: 'the week', MONTH: 'the month', YTD: 'the year', SEASON: 'the season', ALL: 'all time' }

function SymbolLink({ symbol }: { symbol: string | null | undefined }) {
  return (
    <Link to="/stock/$symbol" params={{ symbol: symbol ?? '' }} className="font-semibold text-text-1 hover:text-accent">
      {symbol}
    </Link>
  )
}

function describe(i: FeedItem): React.ReactNode {
  const p = i.payload
  switch (i.kind) {
    case 'FILL':
      return (
        <>
          <span className={p?.side === 'SELL' ? 'text-down' : 'text-up'}>{p?.side === 'SELL' ? 'sold' : 'bought'}</span> {formatQty(p?.qty)} <SymbolLink symbol={i.symbol} /> @ {formatPrice(p?.price)}
        </>
      )
    case 'POSITION_CLOSED':
      return (
        <>
          closed <SymbolLink symbol={i.symbol} />
          {p?.realizedPnl ? (
            <>
              {' '}
              for <span className={cx('font-semibold', DIRECTION_TEXT[moneyDirection(p.realizedPnl)])}>{formatMoney(p.realizedPnl, { signed: true })}</span>
            </>
          ) : null}
        </>
      )
    case 'NEW_LEADER':
      return <>took the lead{p?.period ? ` for ${PERIOD_WORD[p.period] ?? p.period.toLowerCase()}` : ''}</>
    case 'RESET':
      return <>reset their account (losses stay on the board)</>
    case 'DEPOSIT':
      return (
        <>
          received a deposit of <span className="font-semibold text-text-1">{formatMoney(p?.amount)}</span>
        </>
      )
    case 'JOINED':
      return <>joined the league</>
    default:
      return null
  }
}

const etDay = (ms: number): string => new Date(ms).toLocaleDateString('en-CA', { timeZone: 'America/New_York' })

/** "Today", "Yesterday" or "Wed, Oct 7" in ET, for the day headers. */
export function feedDayLabel(at: string | null | undefined, nowMs: number): string {
  if (!at) return ''
  const day = etDay
  const d = day(Date.parse(at))
  if (d === day(nowMs)) return 'Today'
  if (d === day(nowMs - 86_400_000)) return 'Yesterday'
  return new Date(Date.parse(at)).toLocaleDateString('en-US', { timeZone: 'America/New_York', weekday: 'short', month: 'short', day: 'numeric' })
}

function FeedRow({ i, fresh }: { i: FeedItem; fresh: boolean }) {
  const rationale = i.payload?.untrusted?.rationale
  const memo = i.payload?.untrusted?.memo
  const k = i.kind ? KIND_ICON[i.kind] : null
  return (
    <li className={cx('flex gap-3 px-4 py-3', fresh && 'animate-feed-in')}>
      <span className="relative flex shrink-0 self-start">
        <Avatar player={i.player} size={36} />
        {k ? (
          <span className={cx('absolute -right-1 -bottom-1 flex size-[18px] items-center justify-center rounded-full ring-2 ring-bg-1', k.className)} title={k.label}>
            <k.icon size={11} />
          </span>
        ) : null}
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-body text-text-2">
          <Link to="/players/$handle" params={{ handle: i.player?.handle ?? '' }} className="font-semibold text-text-1 hover:text-accent">
            {i.player?.displayName ?? i.player?.handle}
          </Link>{' '}
          {i.player?.kind === 'AI' ? <AiChip model={i.player.aiModel} /> : null} {describe(i)}
        </p>
        {rationale ? (
          <p className="mt-1 rounded-sm border-l-2 border-ai/50 bg-bg-2 px-2 py-1 text-caption text-text-2" title="The player's own note (shown as written)">
            “{rationale}”
          </p>
        ) : null}
        {memo ? <p className="mt-1 text-caption text-text-2">“{memo}”</p> : null}
        <p className="mt-0.5 text-micro text-text-3">
          {formatTimeEt(i.at)}
          {fresh ? <span className="ml-1.5 font-semibold text-accent">New</span> : null}
        </p>
      </div>
    </li>
  )
}

/**
 * League feed (§22.4 Feed sub-view): delayed trades (with public rationales), closed positions, new leaders,
 * deposits, joins and resets, grouped by day. Live: `/topic/feed` items (§19) slide in at the top while it is open.
 */
export function FeedList() {
  const q = useFeed()
  const fresh = useLiveFeed()
  const [filter, setFilter] = useState<FeedFilter>('ALL')
  const [now] = useState(() => Date.now())
  const items = useMemo(() => {
    const all = q.data?.pages.flatMap((p) => p.items ?? []) ?? []
    const kinds = FEED_FILTER_KINDS[filter]
    return kinds ? all.filter((i) => i.kind && kinds.includes(i.kind)) : all
  }, [q.data, filter])
  const groups = useMemo(() => {
    const out: Array<{ label: string; items: FeedItem[] }> = []
    for (const i of items) {
      const label = feedDayLabel(i.at, now)
      const last = out.at(-1)
      if (last && last.label === label) last.items.push(i)
      else out.push({ label, items: [i] })
    }
    return out
  }, [items, now])

  return (
    <div className="space-y-3">
      <div className="scrollbar-none -mx-4 flex items-center gap-2 overflow-x-auto px-4 tablet:mx-0 tablet:px-0">
        <Segmented
          label="Feed filter"
          size="sm"
          value={filter}
          onChange={setFilter}
          options={[
            { value: 'ALL', label: 'All' },
            { value: 'TRADES', label: 'Trades' },
            { value: 'LEADERS', label: 'Leaders' },
            { value: 'MONEY', label: 'Deposits' },
            { value: 'PLAYERS', label: 'Players' },
          ]}
        />
        <span className="ml-auto flex shrink-0 items-center gap-1.5 text-micro text-text-3" title="New events appear as they happen">
          <span className="size-1.5 animate-pulse rounded-full bg-up" aria-hidden="true" /> Live
        </span>
      </div>
      {q.isPending ? (
        <div className="rounded-lg border border-divider bg-bg-1 px-4">
          <SkeletonRows rows={6} cols={3} />
        </div>
      ) : q.isError ? (
        <EmptyState title="The feed is unavailable right now" body="It reconnects by itself." />
      ) : items.length === 0 ? (
        <EmptyState title={filter === 'ALL' ? 'Nothing in the feed yet' : 'Nothing of this kind yet'} body="Trades appear here after the league's feed delay." />
      ) : (
        <div className="overflow-hidden rounded-lg border border-divider bg-bg-1">
          <ul aria-label="League feed" aria-live="polite" aria-relevant="additions">
            {groups.map((g) => (
              <li key={g.label}>
                <h3 className="sticky top-0 z-10 border-b border-divider bg-bg-1/95 px-4 py-1.5 text-micro font-semibold tracking-wide text-text-3 uppercase backdrop-blur">{g.label}</h3>
                <ul className="divide-y divide-divider">
                  {g.items.map((i) => (
                    <FeedRow key={i.id ?? `${i.at}-${i.kind}`} i={i} fresh={Boolean(i.id && fresh.has(i.id))} />
                  ))}
                </ul>
              </li>
            ))}
          </ul>
          {q.hasNextPage ? (
            <div className="flex justify-center border-t border-divider p-3">
              <Button size="sm" onClick={() => void q.fetchNextPage()} disabled={q.isFetchingNextPage}>
                {q.isFetchingNextPage ? 'Loading…' : 'Load more'}
              </Button>
            </div>
          ) : null}
        </div>
      )}
      <p className="text-micro text-text-3">Trades show after the league&apos;s delay, so nobody can copy them live. Notes are the players&apos; own words.</p>
    </div>
  )
}
