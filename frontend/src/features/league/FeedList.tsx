import { Link } from '@tanstack/react-router'
import { useFeed } from '@/lib/api/hooks/league'
import type { FeedItem } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'
import { direction, formatPrice, formatQty } from '@/lib/format/number'
import { formatDateTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { AiChip, Avatar } from './PlayerBadge'

function describe(i: FeedItem): React.ReactNode {
  const p = i.payload
  switch (i.kind) {
    case 'FILL':
      return (
        <>
          <span className={p?.side === 'SELL' ? 'text-down' : 'text-up'}>{p?.side === 'SELL' ? 'sold' : 'bought'}</span> {formatQty(p?.qty)}{' '}
          <Link to="/stock/$symbol" params={{ symbol: i.symbol ?? '' }} className="font-semibold hover:text-accent">
            {i.symbol}
          </Link>{' '}
          @ {formatPrice(p?.price)}
        </>
      )
    case 'POSITION_CLOSED':
      return (
        <>
          closed{' '}
          <Link to="/stock/$symbol" params={{ symbol: i.symbol ?? '' }} className="font-semibold hover:text-accent">
            {i.symbol}
          </Link>
          {p?.realizedPnl ? (
            <>
              {' '}
              for <span className={DIRECTION_TEXT[direction(p.realizedPnl)]}>{formatMoney(p.realizedPnl, { signed: true })}</span>
            </>
          ) : null}
        </>
      )
    case 'NEW_LEADER':
      return <>took the lead{p?.period ? ` for the ${p.period.toLowerCase()}` : ''} 🏆</>
    case 'RESET':
      return <>reset their account</>
    case 'DEPOSIT':
      return <>received a deposit of {formatMoney(p?.amount)}</>
    case 'JOINED':
      return <>joined the league</>
    default:
      return null
  }
}

/** League feed (§22.4 Feed sub-view): delayed trades (with rationales when public), deposits, leaders, joins. */
export function FeedList() {
  const q = useFeed()
  if (q.isPending) return <SkeletonRows rows={6} cols={3} />
  if (q.isError) return <EmptyState title="The feed is unavailable right now" />
  const items = q.data.pages.flatMap((p) => p.items ?? [])
  if (!items.length) return <EmptyState title="Nothing in the feed yet" body="Trades appear here 15 minutes after they fill." />
  return (
    <div>
      <ul className="divide-y divide-divider rounded-lg border border-divider bg-bg-1">
        {items.map((i) => {
          const rationale = i.payload?.untrusted?.rationale
          const memo = i.payload?.untrusted?.memo
          return (
            <li key={i.id ?? `${i.at}-${i.kind}`} className="flex gap-3 px-4 py-3">
              <Avatar player={i.player} size={32} />
              <div className="min-w-0 flex-1">
                <p className="text-body">
                  <Link to="/players/$handle" params={{ handle: i.player?.handle ?? '' }} className="font-semibold hover:text-accent">
                    {i.player?.displayName ?? i.player?.handle}
                  </Link>{' '}
                  {i.player?.kind === 'AI' ? <AiChip model={i.player.aiModel} /> : null} {describe(i)}
                </p>
                {rationale ? <p className="mt-1 rounded-sm bg-bg-2 px-2 py-1 text-caption text-text-2">“{rationale}”</p> : null}
                {memo ? <p className="mt-1 text-caption text-text-2">“{memo}”</p> : null}
                <p className={cx('mt-0.5 text-micro text-text-3')}>{formatDateTimeEt(i.at)}</p>
              </div>
            </li>
          )
        })}
      </ul>
      {q.hasNextPage ? (
        <div className="flex justify-center p-3">
          <Button size="sm" onClick={() => void q.fetchNextPage()} disabled={q.isFetchingNextPage}>
            {q.isFetchingNextPage ? 'Loading…' : 'Load more'}
          </Button>
        </div>
      ) : null}
    </div>
  )
}
