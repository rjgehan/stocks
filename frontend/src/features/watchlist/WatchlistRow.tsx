import { memo } from 'react'
import { Link } from '@tanstack/react-router'
import { useAsset } from '@/lib/api/hooks/market'
import { useQuote } from '@/lib/realtime/useQuote'
import { changeDirection, formatChange, formatPct, formatPrice, pctDirection } from '@/lib/format/number'
import { IconArrowDown, IconArrowUp, IconClose, IconGrip } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'
import { usePriceFlash } from '@/features/market/usePriceFlash'

export type PillMode = 'pct' | 'change'

const PILL = { up: 'bg-up text-on-updown', down: 'bg-down text-on-updown', flat: 'bg-bg-3 text-text-1' } as const

export interface RowEditing {
  index: number
  count: number
  onMove: (from: number, to: number) => void
  onRemove: () => void
  dragging: boolean
  onDragStart: () => void
  onDragOver: () => void
  onDragEnd: () => void
}

/**
 * One watchlist row (§22.4): symbol, name, last price with a 400 ms flash, and a % change pill that cycles
 * % → $ when tapped. Subscribes to `/topic/tick.{SYM}` (LIST lease) and re-renders only for its own symbol.
 * In edit mode the row shows a drag handle, move up/down and remove instead.
 */
export const WatchlistRow = memo(function WatchlistRow({
  symbol,
  active,
  pillMode,
  onCyclePill,
  dense,
  editing,
}: {
  symbol: string
  active?: boolean
  pillMode: PillMode
  onCyclePill: () => void
  dense?: boolean
  editing?: RowEditing
}) {
  const quote = useQuote(symbol, 'tick', { seed: false })
  const { data: asset } = useAsset(symbol)
  const flash = usePriceFlash(quote?.last)
  const ext = quote?.extended
  const pill = pillMode === 'pct' ? formatPct(quote?.changePct) : formatChange(quote?.change, quote?.last)
  // the pill's color follows the value it shows: "0.00%" is flat, never red or green (§22.7)
  const dir = pillMode === 'pct' ? pctDirection(quote?.changePct) : changeDirection(quote?.change, quote?.last)

  if (editing) {
    const { index, count, onMove, onRemove, dragging, onDragStart, onDragOver, onDragEnd } = editing
    return (
      <div
        draggable
        onDragStart={(e) => {
          e.dataTransfer.effectAllowed = 'move'
          onDragStart()
        }}
        onDragOver={(e) => {
          e.preventDefault()
          onDragOver()
        }}
        onDragEnd={onDragEnd}
        className={cx('flex min-h-[52px] items-center gap-1 pr-2 pl-1', dragging && 'bg-accent-bg opacity-70')}
      >
        <span className="flex size-9 cursor-grab items-center justify-center text-text-3 active:cursor-grabbing" aria-hidden="true">
          <IconGrip size={18} />
        </span>
        <div className="min-w-0 flex-1">
          <div className="text-body font-semibold">{symbol}</div>
          <div className="truncate text-caption text-text-3">{asset?.name ?? ' '}</div>
        </div>
        <button type="button" aria-label={`Move ${symbol} up`} disabled={index === 0} onClick={() => onMove(index, index - 1)} className="flex size-10 items-center justify-center rounded-md text-text-2 hover:bg-bg-2 disabled:opacity-30">
          <IconArrowUp size={18} />
        </button>
        <button type="button" aria-label={`Move ${symbol} down`} disabled={index === count - 1} onClick={() => onMove(index, index + 1)} className="flex size-10 items-center justify-center rounded-md text-text-2 hover:bg-bg-2 disabled:opacity-30">
          <IconArrowDown size={18} />
        </button>
        <button type="button" aria-label={`Remove ${symbol}`} onClick={onRemove} className="flex size-10 items-center justify-center rounded-md text-down hover:bg-down-bg">
          <IconClose size={18} />
        </button>
      </div>
    )
  }

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
        aria-label={`${symbol} ${asset?.name ?? ''} ${formatPrice(quote?.last)}`.trim()}
      >
        <div className="min-w-0 flex-1">
          <div className="text-body leading-5 font-semibold text-text-1">{symbol}</div>
          <div className="truncate text-caption text-text-3">{asset?.name ?? ' '}</div>
          {ext ? (
            <div className="truncate text-micro text-text-3">
              <span className="font-semibold text-session-ext">{ext.session === 'PRE' ? 'Pre' : 'Post'}</span> {formatPrice(ext.price)} {formatPct(ext.changePct)}
            </div>
          ) : null}
        </div>
        <span key={flash.key} className={cx('rounded-sm px-1 text-body font-semibold', flash.dir === 'up' && 'animate-flash-up', flash.dir === 'down' && 'animate-flash-down')}>
          {formatPrice(quote?.last)}
        </span>
      </Link>
      <button
        type="button"
        onClick={onCyclePill}
        aria-label={`${symbol} change ${pill}; tap to switch between percent and dollars`}
        className={cx('h-7 w-[76px] shrink-0 rounded-sm text-caption font-semibold transition-colors', PILL[dir])}
      >
        {pill}
      </button>
    </div>
  )
})
