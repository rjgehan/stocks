import { usePlayerActivity } from '@/lib/api/hooks/league'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { ActivityRow } from '@/features/account/ActivityList'

/**
 * A player's account activity (public mode, §16.7; profile → History → Activity): the active run's fills, funding,
 * deposits, dividends, splits and account events, newest first, 30 at a time. The server leaves out admin and player
 * memos and the old "admin viewed" rows; fills do not link to an order page (that page is the owner's only).
 */
export function PlayerActivityList({ handle }: { handle: string }) {
  const q = usePlayerActivity(handle)
  if (q.isPending)
    return (
      <div className="px-4">
        <SkeletonRows rows={4} cols={3} />
      </div>
    )
  if (q.isError)
    return (
      <EmptyState
        title="Activity is unavailable right now"
        action={
          <Button size="sm" onClick={() => void q.refetch()}>
            Retry
          </Button>
        }
      />
    )
  const items = q.data.pages.flatMap((p) => p.items ?? [])
  if (!items.length) return <EmptyState title="No activity yet." body="Fills, deposits and account events appear here, newest first." />
  return (
    <div>
      <ul className="divide-y divide-divider" aria-label="Activity">
        {items.map((a) => (
          <li key={a.id ?? `${a.at}-${a.type}`}>
            <ActivityRow a={a} linkOrders={false} />
          </li>
        ))}
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
