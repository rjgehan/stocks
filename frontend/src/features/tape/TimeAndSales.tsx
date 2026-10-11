/**
 * Time & Sales (§22.10): the symbol's recent prints from `GET /assets/{symbol}/trades` plus `/topic/trades.{SYM}`
 * (a VIEWING lease, so the stream covers the symbol while someone watches), merged by print id, newest first. Each
 * print shows time (ET), price as traded, size and its side by color AND glyph. Honest empty states: no prints are
 * ever made up when no trade stream covers the symbol.
 */
import { useEffect, useMemo, useState } from 'react'
import { type TimeAndSales as Tape, useTimeAndSales } from '@/lib/api/hooks/marketExtras'
import { formatInteger } from '@/lib/format/number'
import { usePollWhenOffline } from '@/lib/realtime/status'
import { realtime } from '@/lib/realtime/stomp'
import { Popover } from '@/components/ui/Popover'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { IconInfo } from '@/components/ui/icons'
import { formatPrintPrice, formatPrintTime, mergePrints, type Print, sideView } from './tape'

const SOURCE_LABEL: Record<string, { badge: string; note: string }> = {
  SIP: { badge: 'SIP', note: 'Consolidated prints from every exchange.' },
  IEX: { badge: 'IEX', note: 'Prints from the IEX exchange only (a few percent of all volume), from the free data plan.' },
  SIMULATED: { badge: 'SIM', note: 'Simulated prints from the SIM market.' },
}

export function TimeAndSales({ symbol, className, maxHeight = 'max-h-[320px]' }: { symbol: string; className?: string; maxHeight?: string }) {
  // while the socket is down the REST seed polls instead (§19 fallback)
  const poll = usePollWhenOffline(5_000)
  const seed = useTimeAndSales(symbol, 60, poll)
  // prints the topic delivered (the topic callback is the only writer); the REST seed is merged in at render
  const [live, setLive] = useState<{ symbol: string; prints: Print[]; meta: Pick<Tape, 'source' | 'streaming'> | null }>({ symbol, prints: [], meta: null })

  useEffect(() => {
    return realtime().subscribe(`/topic/trades.${symbol}`, (body) => {
      const msg = body as Tape
      setLive((s) => ({
        symbol,
        prints: mergePrints(s.symbol === symbol ? s.prints : [], (msg.prints ?? []) as Print[]),
        meta: { source: msg.source, streaming: msg.streaming },
      }))
    })
  }, [symbol])

  const mine = live.symbol === symbol ? live : null
  const prints = useMemo(() => mergePrints(mine?.prints ?? [], (seed.data?.prints ?? []) as Print[]), [mine?.prints, seed.data])
  const meta = mine?.meta ?? (seed.data ? { source: seed.data.source, streaming: seed.data.streaming } : null)
  const source = meta?.source ? SOURCE_LABEL[meta.source] : undefined

  return (
    <section aria-label={`${symbol} time and sales`} className={cx('flex min-h-0 flex-col', className)} data-testid="time-and-sales">
      <div className="flex h-8 shrink-0 items-center justify-between gap-2">
        <h2 className="text-caption font-semibold tracking-wide text-text-2 uppercase">Time &amp; Sales</h2>
        <div className="flex items-center gap-1">
          {source ? <span className="rounded-sm bg-bg-2 px-1.5 text-micro font-semibold text-text-2" title={source.note}>{source.badge}</span> : null}
          <Popover
            label="About Time & Sales"
            renderTrigger={(props) => (
              <button type="button" className="flex size-7 items-center justify-center rounded-md text-text-3 hover:text-text-1" aria-label="About Time & Sales" {...props}>
                <IconInfo size={15} />
              </button>
            )}
          >
            <div className="max-w-72 space-y-1.5 px-3 py-2 text-caption text-text-2">
              <p>
                Each print is one trade: <span className="text-up">▲ at the ask</span> (a buyer paid up), <span className="text-down">▼ at the bid</span> (a seller hit the bid), or • in between.
              </p>
              <p>The side is judged against the quote when the print arrived; on the free data plan that quote is IEX&apos;s, so it is an approximation.</p>
              {source ? <p>{source.note}</p> : null}
            </div>
          </Popover>
        </div>
      </div>
      <div className="grid shrink-0 grid-cols-[1fr_auto_auto] gap-x-3 border-b border-divider pb-1 text-micro font-medium text-text-3 uppercase">
        <span>Time ET</span>
        <span className="text-right">Price</span>
        <span className="w-14 text-right">Size</span>
      </div>
      {seed.isPending && prints.length === 0 ? (
        <SkeletonRows rows={6} cols={3} />
      ) : prints.length === 0 ? (
        <p className="py-4 text-caption text-text-3" data-testid="tape-empty">
          {meta?.source === 'NONE' || !meta
            ? `No live trade stream covers ${symbol} right now. Prints appear here while a stream slot is free; none are ever made up.`
            : 'Waiting for the next print…'}
        </p>
      ) : (
        <ol className={cx('scrollbar-thin min-h-0 overflow-y-auto font-mono text-caption tabular-nums', maxHeight)} aria-live="off">
          {prints.map((p) => {
            const v = sideView(p.side)
            return (
              <li key={p.id} className={cx('grid grid-cols-[1fr_auto_auto] gap-x-3 py-0.5', v.tone)} title={`${formatPrintPrice(p.price)} × ${formatInteger(p.size)} ${v.label}${p.venue ? ` · ${p.venue}` : ''}`}>
                <span className="text-text-3">{formatPrintTime(p.t)}</span>
                <span className="text-right">
                  <span aria-hidden="true" className="mr-1 inline-block w-2.5 text-center">{v.glyph}</span>
                  {formatPrintPrice(p.price)}
                  <span className="sr-only"> {v.label}</span>
                </span>
                <span className="w-14 text-right">{formatInteger(p.size)}</span>
              </li>
            )
          })}
        </ol>
      )}
    </section>
  )
}
