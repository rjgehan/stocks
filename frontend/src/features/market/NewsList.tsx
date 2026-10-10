import { useNews } from '@/lib/api/hooks/market'
import { formatDateTimeEt } from '@/lib/format/time'
import { EmptyState } from '@/components/ui/EmptyState'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { IconExternal } from '@/components/ui/icons'

/**
 * News (§22.4, §18.2 `GET /news`): third-party headlines, rendered as plain text from the `untrusted` envelope (§17.8)
 * and linked out with `rel="noopener noreferrer nofollow"`. Empty when no source is configured.
 */
export function NewsList({ symbols, limit = 8 }: { symbols: string; limit?: number }) {
  const { data, isPending, isError } = useNews(symbols)
  if (isPending) return <SkeletonRows rows={4} cols={2} />
  const items = (data?.items ?? []).filter((n) => n.untrusted?.headline).slice(0, limit)
  if (isError || items.length === 0) return <EmptyState title="No news right now" body="Headlines appear here when the market-data news source has stories for this symbol." />
  return (
    <ul className="divide-y divide-divider">
      {items.map((n) => {
        const safeUrl = n.url && /^https?:\/\//.test(n.url) ? n.url : null
        const body = (
          <>
            <span className="block text-body font-medium text-text-1">{n.untrusted?.headline}</span>
            {n.untrusted?.summary ? <span className="mt-0.5 line-clamp-2 block text-caption text-text-2">{n.untrusted.summary}</span> : null}
            <span className="mt-1 flex items-center gap-1.5 text-micro text-text-3">
              {n.source ? <span>{n.source}</span> : null}
              <span>· {formatDateTimeEt(n.publishedAt)}</span>
              {(n.symbols ?? []).slice(0, 4).map((s) => (
                <span key={s} className="rounded-sm bg-bg-2 px-1 font-semibold text-text-2">
                  {s}
                </span>
              ))}
              {safeUrl ? <IconExternal size={12} className="ml-auto" /> : null}
            </span>
          </>
        )
        return (
          <li key={n.id ?? n.url ?? n.publishedAt}>
            {safeUrl ? (
              <a href={safeUrl} target="_blank" rel="noopener noreferrer nofollow" className="block py-3 hover:bg-bg-2/50">
                {body}
              </a>
            ) : (
              <div className="py-3">{body}</div>
            )}
          </li>
        )
      })}
    </ul>
  )
}
