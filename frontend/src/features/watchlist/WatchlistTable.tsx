/**
 * The desktop watchlist as a table (§22.10): chosen columns (Last, Change, % Change, Volume, Market cap, Bid/Ask,
 * Day range), header sorting (descending → ascending → list order; a live sort re-orders at most every 3 s), and
 * drag-and-drop reordering in list order, saved with `PUT /watchlists/{id}`. Rows subscribe to their own tick
 * (LIST lease); volume, bid/ask and the day range come from the bulk quote seed, refreshed every 10 s while shown.
 */
import { Fragment, memo, useEffect, useMemo, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { api, unwrap } from '@/lib/api/client'
import { useAsset } from '@/lib/api/hooks/market'
import { queryKeys } from '@/lib/api/queryKeys'
import type { Asset, Quote } from '@/lib/api/types'
import { abbreviate, formatChange, formatPct, formatPrice, changeDirection, pctDirection } from '@/lib/format/number'
import { useQuoteStore } from '@/lib/realtime/quoteStore'
import { useQuote } from '@/lib/realtime/useQuote'
import { Popover } from '@/components/ui/Popover'
import { cx } from '@/components/ui/cx'
import { IconArrowDown, IconArrowUp, IconGrip } from '@/components/ui/icons'
import { usePriceFlash } from '@/features/market/usePriceFlash'
import { COLUMNS, type ColumnId, type WatchlistSort, columnValue, cycleSort, sortSymbols } from './columns'
import { toPath } from '@/lib/symbols'

const PILL = { up: 'bg-up text-on-updown', down: 'bg-down text-on-updown', flat: 'bg-bg-3 text-text-1' } as const
const UP_DOWN = { up: 'text-up', down: 'text-down', flat: 'text-text-1' } as const
const SYMBOL_WIDTH = 112
/** Columns that need the full quote (not just the tick). */
const FULL_QUOTE: readonly ColumnId[] = ['volume', 'bidAsk', 'dayRange']

/** Keeps the full quotes of `symbols` fresh (one `GET /quotes` per 50 symbols, every 10 s) while enabled. */
function useFullQuotes(symbols: readonly string[], enabled: boolean): void {
  const key = [...new Set(symbols)].sort().join(',')
  useQuery({
    queryKey: ['watchlistFullQuotes', key] as const,
    enabled: enabled && key.length > 0,
    refetchInterval: 10_000,
    staleTime: 8_000,
    queryFn: async () => {
      const all = key.split(',')
      for (let i = 0; i < all.length; i += 50) {
        const page = await unwrap(api.GET('/api/v1/quotes', { params: { query: { symbols: all.slice(i, i + 50).join(',') } } }))
        useQuoteStore.getState().upsertQuotes(page.quotes ?? [])
      }
      return all.length
    },
  })
}

/** The quote store now; `epoch` is the 3 s re-sort clock (a new epoch means a fresh read). */
function quotesAt(epoch: number): Record<string, Quote> {
  void epoch
  return useQuoteStore.getState().quotes
}

const Cell = ({ width, children, className }: { width: number; children: React.ReactNode; className?: string }) => (
  <span className={cx('shrink-0 text-right tabular-nums', className)} style={{ width }}>
    {children}
  </span>
)

const Row = memo(function Row({
  symbol,
  columns,
  active,
  drag,
}: {
  symbol: string
  columns: readonly ColumnId[]
  active: boolean
  drag: { dragging: boolean; onDragStart: () => void; onDragOver: () => void; onDrop: () => void } | null
}) {
  const quote = useQuote(symbol, 'tick', { seed: false })
  const { data: asset } = useAsset(symbol)
  const flash = usePriceFlash(quote?.last)
  const cells: Record<ColumnId, React.ReactNode> = {
    last: (
      <span key={flash.key} className={cx('rounded-sm px-1 font-semibold', flash.dir === 'up' && 'animate-flash-up', flash.dir === 'down' && 'animate-flash-down')}>
        {formatPrice(quote?.last)}
      </span>
    ),
    change: <span className={UP_DOWN[changeDirection(quote?.change, quote?.last)]}>{formatChange(quote?.change, quote?.last)}</span>,
    changePct: (
      <span className={cx('inline-block w-[72px] rounded-sm py-0.5 text-center text-caption font-semibold', PILL[pctDirection(quote?.changePct)])}>{formatPct(quote?.changePct)}</span>
    ),
    volume: <span title={quote?.volumeIsPartial ? 'IEX volume only' : undefined}>{abbreviate(quote?.volume)}</span>,
    marketCap: abbreviate(asset?.fundamentals?.marketCap),
    bidAsk: (
      <span>
        <span className="text-up">{formatPrice(quote?.bid)}</span>
        <span className="text-text-3"> / </span>
        <span className="text-down">{formatPrice(quote?.ask)}</span>
      </span>
    ),
    dayRange: `${formatPrice(quote?.dayLow)} – ${formatPrice(quote?.dayHigh)}`,
  }
  return (
    <div
      role="row"
      draggable={drag !== null}
      onDragStart={(e) => {
        if (!drag) return
        e.dataTransfer.effectAllowed = 'move'
        e.dataTransfer.setData('text/plain', symbol)
        drag.onDragStart()
      }}
      onDragOver={(e) => {
        if (!drag) return
        e.preventDefault()
        drag.onDragOver()
      }}
      onDrop={(e) => {
        if (!drag) return
        e.preventDefault()
        drag.onDrop()
      }}
      onDragEnd={() => drag?.onDrop()}
      data-symbol={symbol}
      className={cx(
        'group flex min-h-[48px] w-max min-w-full items-center gap-2 border-l-2 pr-3 text-table transition-colors',
        active ? 'border-accent bg-accent-bg' : 'border-transparent hover:bg-bg-2',
        drag?.dragging && 'opacity-60',
      )}
    >
      <Link
        to="/stock/$symbol"
        params={{ symbol: toPath(symbol) }}
        aria-current={active ? 'page' : undefined}
        aria-label={`${symbol} ${asset?.name ?? ''} ${formatPrice(quote?.last)}`.trim()}
        className={cx('sticky left-0 z-[1] flex shrink-0 items-center gap-1 self-stretch py-1.5 pl-1', active ? 'bg-accent-bg' : 'bg-bg-1 group-hover:bg-bg-2')}
        style={{ width: SYMBOL_WIDTH }}
      >
        <span
          aria-hidden="true"
          className={cx('flex w-4 shrink-0 cursor-grab justify-center text-text-3 opacity-0 transition-opacity active:cursor-grabbing', drag && 'group-hover:opacity-100')}
        >
          {drag ? <IconGrip size={14} /> : null}
        </span>
        <span className="min-w-0">
          <span className="block text-body leading-5 font-semibold text-text-1">{symbol}</span>
          <span className="block truncate text-micro text-text-3">{asset?.name ?? ' '}</span>
        </span>
      </Link>
      {columns.map((c) => (
        <Cell key={c} width={COLUMNS.find((x) => x.id === c)!.width}>
          {cells[c]}
        </Cell>
      ))}
    </div>
  )
})

export function WatchlistTable({
  symbols,
  activeSymbol,
  columns,
  sort,
  onSort,
  onReorder,
}: {
  symbols: readonly string[]
  activeSymbol?: string
  columns: readonly ColumnId[]
  sort: WatchlistSort | null
  onSort: (s: WatchlistSort | null) => void
  /** Null when the list cannot be reordered (Holdings). */
  onReorder: ((next: string[]) => void) | null
}) {
  const qc = useQueryClient()
  useFullQuotes(symbols, columns.some((c) => FULL_QUOTE.includes(c)))
  // a live sort is recomputed at most every 3 s, so rows do not jump on every tick
  const [epoch, setEpoch] = useState(0)
  useEffect(() => {
    if (!sort) return
    const id = setInterval(() => setEpoch((e) => e + 1), 3_000)
    return () => clearInterval(id)
  }, [sort])
  const sorted = useMemo(() => {
    const quotes = quotesAt(epoch)
    return sortSymbols(symbols, sort, (s) => columnValue(sort?.col ?? 'symbol', s, quotes[s], qc.getQueryData<Asset>(queryKeys.asset(s))))
  }, [symbols, sort, epoch, qc])
  const [drag, setDrag] = useState<{ symbol: string; order: string[] } | null>(null)
  // the drag in progress, also for the handlers (drop and dragend both end it; only the first one saves)
  const dragRef = useRef<{ symbol: string; order: string[] } | null>(null)
  const shown = drag?.order ?? sorted
  const canDrag = onReorder !== null && sort === null
  const startDrag = (symbol: string) => {
    dragRef.current = { symbol, order: [...sorted] }
    setDrag(dragRef.current)
  }
  const dragOver = (target: string) => {
    const d = dragRef.current
    if (!d || d.symbol === target) return
    const next = [...d.order]
    next.splice(next.indexOf(d.symbol), 1)
    next.splice(next.indexOf(target), 0, d.symbol)
    dragRef.current = { ...d, order: next }
    setDrag(dragRef.current)
  }
  const endDrag = () => {
    const d = dragRef.current
    dragRef.current = null
    setDrag(null)
    if (d && onReorder && d.order.join() !== symbols.join()) onReorder(d.order)
  }

  const header = (col: WatchlistSort['col'], label: string, width?: number) => {
    const activeSort = sort?.col === col ? sort.dir : null
    return (
      <span
        role="columnheader"
        aria-sort={activeSort === 'asc' ? 'ascending' : activeSort === 'desc' ? 'descending' : 'none'}
        className={cx('block shrink-0', width ? 'text-right' : 'sticky left-0 z-[1] bg-bg-1')}
        style={{ width: width ?? SYMBOL_WIDTH }}
      >
        <button type="button" onClick={() => onSort(cycleSort(sort, col))} className={cx('inline-flex items-center gap-0.5 uppercase hover:text-text-1', activeSort && 'text-text-1', !width && 'pl-5')}>
          {label}
          {activeSort === 'asc' ? <IconArrowUp size={11} /> : activeSort === 'desc' ? <IconArrowDown size={11} /> : null}
        </button>
      </span>
    )
  }

  return (
    <div role="table" aria-label="Watchlist symbols" className="min-h-0 flex-1 overflow-auto scrollbar-thin">
      <div role="row" className="sticky top-0 z-[2] flex h-7 w-max min-w-full items-center gap-2 bg-bg-1 pr-3 text-micro font-medium tracking-wide text-text-3">
        {header('symbol', 'Symbol')}
        {columns.map((c) => {
          const def = COLUMNS.find((x) => x.id === c)!
          return <Fragment key={c}>{header(c, def.header, def.width)}</Fragment>
        })}
      </div>
      {sort && onReorder ? (
        <p className="px-3 pb-1 text-micro text-text-3">
          Sorted by {sort.col === 'symbol' ? 'symbol' : COLUMNS.find((c) => c.id === sort.col)?.label.toLowerCase()}.{' '}
          <button type="button" className="text-accent hover:text-accent-hover" onClick={() => onSort(null)}>
            Clear the sort to reorder
          </button>
        </p>
      ) : null}
      <div role="rowgroup" className="divide-y divide-divider">
        {shown.map((s) => (
          <Row
            key={s}
            symbol={s}
            columns={columns}
            active={s === activeSymbol}
            drag={
              canDrag
                ? { dragging: drag?.symbol === s, onDragStart: () => startDrag(s), onDragOver: () => dragOver(s), onDrop: endDrag }
                : null
            }
          />
        ))}
      </div>
    </div>
  )
}

/** The column chooser (a popover of checkboxes; at least one column stays on). */
export function ColumnChooser({ columns, onChange }: { columns: readonly ColumnId[]; onChange: (c: ColumnId[]) => void }) {
  return (
    <Popover
      label="Watchlist columns"
      className="w-56"
      renderTrigger={(props) => (
        <button type="button" {...props} className="inline-flex h-7 items-center rounded-md px-1.5 text-micro font-semibold tracking-wide text-text-2 uppercase hover:bg-bg-2 hover:text-text-1">
          Columns
        </button>
      )}
    >
      <div className="px-3 py-2" data-testid="column-chooser">
        {COLUMNS.map((c) => {
          const on = columns.includes(c.id)
          return (
            <label key={c.id} className="flex min-h-9 cursor-pointer items-center gap-2 text-caption text-text-1">
              <input
                type="checkbox"
                checked={on}
                disabled={on && columns.length === 1}
                onChange={() => onChange(on ? columns.filter((x) => x !== c.id) : COLUMNS.map((x) => x.id).filter((x) => x === c.id || columns.includes(x)))}
                className="size-4 accent-[var(--accent)]"
              />
              {c.label}
            </label>
          )
        })}
      </div>
    </Popover>
  )
}
