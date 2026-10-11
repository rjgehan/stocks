/**
 * News for a symbol (§22.10): headline, a two-line summary, the source, a relative time (the full ET time on hover)
 * and symbol chips, paged with "Load more". Third-party text comes from the `untrusted` envelope and is rendered as
 * plain text (§17.8); only http(s) links open, in a new tab, with `rel="noopener noreferrer nofollow"`.
 */
import { useEffect, useState } from 'react'
import { useNewsPages } from '@/lib/api/hooks/marketExtras'
import { formatDateTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { IconExternal } from '@/components/ui/icons'
import { safeHttpUrl, timeAgo } from './research'

/** A clock that re-renders once a minute (relative times). */
function useMinuteClock(): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 60_000)
    return () => clearInterval(id)
  }, [])
  return now
}

export function NewsFeed({ symbols, pageSize = 10 }: { symbols: string; pageSize?: number }) {
  const q = useNewsPages(symbols, pageSize)
  const now = useMinuteClock()
  if (q.isPending) return <SkeletonRows rows={4} cols={2} />
  // a page boundary can repeat the item published exactly at `before`: keep each story once
  const seen = new Set<string>()
  const items = (q.data?.pages ?? [])
    .flatMap((p) => p.items ?? [])
    .filter((n) => {
      const key = n.id ?? n.url ?? ''
      if (!n.untrusted?.headline || (key && seen.has(key))) return false
      if (key) seen.add(key)
      return true
    })
  if (q.isError || items.length === 0) {
    return <EmptyState title="No news right now" body="Headlines appear here when the market-data news sources have stories for this symbol." />
  }
  return (
    <div data-testid="news-feed">
      <ul className="divide-y divide-divider">
        {items.map((n) => {
          const url = safeHttpUrl(n.url)
          const body = (
            <>
              <span className="block text-body font-medium text-text-1">{n.untrusted?.headline}</span>
              {n.untrusted?.summary ? <span className="mt-0.5 line-clamp-2 block text-caption text-text-2">{n.untrusted.summary}</span> : null}
              <span className="mt-1 flex flex-wrap items-center gap-1.5 text-micro text-text-3">
                {n.source ? <span className="font-semibold text-text-2">{n.source}</span> : null}
                <time dateTime={n.publishedAt ?? undefined} title={formatDateTimeEt(n.publishedAt)}>
                  · {timeAgo(n.publishedAt, now)}
                </time>
                {(n.symbols ?? []).slice(0, 4).map((s) => (
                  <span key={s} className="rounded-sm bg-bg-2 px-1 font-semibold text-text-2">
                    {s}
                  </span>
                ))}
                {url ? <IconExternal size={12} className="ml-auto" /> : null}
              </span>
            </>
          )
          return (
            <li key={n.id ?? n.url ?? n.publishedAt}>
              {url ? (
                <a href={url} target="_blank" rel="noopener noreferrer nofollow" className="block py-3 hover:bg-bg-2/50">
                  {body}
                  <span className="sr-only"> (opens in a new tab)</span>
                </a>
              ) : (
                <div className="py-3">{body}</div>
              )}
            </li>
          )
        })}
      </ul>
      {q.hasNextPage ? (
        <div className="flex justify-center pt-2">
          <Button size="sm" variant="secondary" onClick={() => void q.fetchNextPage()} disabled={q.isFetchingNextPage}>
            {q.isFetchingNextPage ? 'Loading…' : 'Load more'}
          </Button>
        </div>
      ) : null}
    </div>
  )
}
