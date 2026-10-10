import { describeError, errorBody } from '@/lib/api/errors'
import { useAddToWatchlist, useRemoveFromWatchlist, useWatchlists } from '@/lib/api/hooks/watchlists'
import { IconButton } from '@/components/ui/Button'
import { Popover } from '@/components/ui/Popover'
import { IconCheck, IconStar } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'
import { toast } from '@/features/notifications/toastStore'

function onError(e: unknown) {
  const d = describeError(e)
  toast({ tone: 'error', title: 'Watchlist not updated', body: errorBody(d) ?? d.title })
}

/**
 * Watch toggle on the quote page (§22.1 StarButton): filled when the symbol is in any list. One list: a plain toggle
 * of that list; several: a popover with a check per list.
 */
export function StarButton({ symbol, size = 'md' }: { symbol: string; size?: 'md' | 'touch' }) {
  const { data: lists } = useWatchlists()
  const add = useAddToWatchlist()
  const remove = useRemoveFromWatchlist()
  const usable = (lists ?? []).filter((w): w is typeof w & { id: string } => Boolean(w.id)).sort((a, b) => a.sortOrder - b.sortOrder)
  const inAny = usable.some((w) => w.symbols?.includes(symbol))
  const toggle = (id: string, name: string | null, has: boolean) => {
    if (has) remove.mutate({ id, symbol }, { onError })
    else add.mutate({ id, symbol }, { onSuccess: () => toast({ tone: 'neutral', title: `Added ${symbol} to ${name ?? 'your list'}` }), onError })
  }
  const label = inAny ? `${symbol} is on your watchlist` : `Add ${symbol} to watchlist`
  const star = <IconStar filled={inAny} className={cx(inAny && 'text-gold')} />

  if (usable.length <= 1) {
    const only = usable[0]
    return (
      <IconButton label={label} size={size} disabled={!only} aria-pressed={inAny} onClick={() => only && toggle(only.id, only.name, inAny)}>
        {star}
      </IconButton>
    )
  }
  return (
    <Popover
      label="Watchlists"
      renderTrigger={(props) => (
        <IconButton label={label} size={size} {...props}>
          {star}
        </IconButton>
      )}
    >
      <div className="px-3 pt-1 pb-1 text-micro font-semibold tracking-wide text-text-3 uppercase">Add {symbol} to</div>
      {usable.map((w) => {
        const has = w.symbols?.includes(symbol) ?? false
        return (
          <button
            key={w.id}
            type="button"
            role="menuitemcheckbox"
            aria-checked={has}
            onClick={() => toggle(w.id, w.name, has)}
            className="flex min-h-11 w-full tablet:min-h-10 items-center justify-between gap-3 px-3 text-left text-body hover:bg-bg-2"
          >
            <span className="truncate">{w.name}</span>
            <span className={cx('flex size-5 items-center justify-center rounded-sm border', has ? 'border-accent bg-accent text-white' : 'border-border')}>
              {has ? <IconCheck size={14} /> : null}
            </span>
          </button>
        )
      })}
    </Popover>
  )
}
