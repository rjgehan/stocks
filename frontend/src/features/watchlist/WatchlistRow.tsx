import { memo } from 'react'
import { Link } from '@tanstack/react-router'
import { useAsset } from '@/lib/api/hooks/market'
import { useQuote } from '@/lib/realtime/useQuote'
import { direction, formatChange, formatPct, formatPrice } from '@/lib/format/number'
import { cx } from '@/components/ui/cx'
import { usePriceFlash } from '@/features/market/usePriceFlash'

export type PillMode = 'pct' | 'change'

const PILL = { up: 'bg-up text-white', down: 'bg-down text-white', flat: 'bg-bg-3 text-text-1' } as const

/**
 * One watchlist row (§22.4): symbol, name, last price with a 400 ms flash, and a % change pill that cycles
 * % → $ when tapped. Subscribes to `/topic/tick.{SYM}` (LIST lease) and re-renders only for its own symbol.
 */
export const WatchlistRow = memo(function WatchlistRow({
  symbol,
  active,
  pillMode,
  onCyclePill,
  dense,
}: {
  symbol: string
  active?: boolean
  pillMode: PillMode
  onCyclePill: () => void
  dense?: boolean
}) {
  const quote = useQuote(symbol, 'tick', { seed: false })
  const { data: asset } = useAsset(symbol)
  const flash = usePriceFlash(quote?.last)
  const dir = direction(quote?.change)
  const ext = quote?.extended
  return (
    <div
      className={cx(
        'group flex items-center gap-2 border-l-2 pr-3 transition-colors',
        active ? 'border-accent bg-accent-bg' : 'border-transparent hover:bg-bg-2',
        dense ? 'min-h-[52px]' : 'min-h-[60px]',
      )}
    >
      <Link
        to="/stock/$symbol"
        params={{ symbol }}
        className="flex min-w-0 flex-1 items-center gap-2 self-stretch py-2 pl-3"
        aria-current={active ? 'page' : undefined}
      >
        <div className="min-w-0 flex-1">
          <div className="text-body font-semibold leading-5 text-text-1">{symbol}</div>
          <div className="truncate text-caption text-text-3">{asset?.name ?? ' '}</div>
          {ext ? (
            <div className="truncate text-micro text-text-3">
              <span className="font-semibold text-session-ext">{ext.session === 'PRE' ? 'Pre' : 'Post'}</span> {formatPrice(ext.price)}{' '}
              {formatPct(ext.changePct)}
            </div>
          ) : null}
        </div>
        <span
          key={flash.key}
          className={cx(
            'rounded-sm px-1 text-body font-semibold',
            flash.dir === 'up' && 'animate-flash-up',
            flash.dir === 'down' && 'animate-flash-down',
          )}
        >
          {formatPrice(quote?.last)}
        </span>
      </Link>
      <button
        type="button"
        onClick={onCyclePill}
        aria-label={`${symbol} change ${pillMode === 'pct' ? formatPct(quote?.changePct) : formatChange(quote?.change, quote?.last)}; tap to switch between percent and dollars`}
        className={cx('h-7 w-[76px] shrink-0 rounded-sm text-caption font-semibold transition-colors', PILL[dir])}
      >
        {pillMode === 'pct' ? formatPct(quote?.changePct) : formatChange(quote?.change, quote?.last)}
      </button>
    </div>
  )
})
