import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import type { PnlAnalysis } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'
import { moneyDirection } from '@/lib/format/number'
import { Pill } from '@/components/ui/Pill'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { symbolBars } from './bySymbol'

/**
 * P&L by symbol (§16.10): diverging horizontal bars sorted by total P&L over the window; tapping or hovering a row
 * splits it into realized, unrealized (the change over the window) and dividends. "Other" is P&L corrections.
 */
export function PnlBySymbol({ analysis, isPending }: { analysis: PnlAnalysis | undefined; isPending: boolean }) {
  const [open, setOpen] = useState<string | null>(null)
  const bars = symbolBars(analysis?.bySymbol, analysis?.other)
  return (
    <section aria-label="P&L by symbol" className="rounded-lg border border-divider bg-bg-1">
      <div className="px-4 pt-3">
        <h2 className="text-section font-semibold">P&amp;L by symbol</h2>
        <p className="text-micro text-text-3">Realized + unrealized + dividends over the same range; tap a row for the split.</p>
      </div>
      {isPending ? (
        <div className="px-4">
          <SkeletonRows rows={3} cols={3} />
        </div>
      ) : bars.length === 0 ? (
        <p className="px-4 py-4 text-caption text-text-3">No trades or holdings in this range yet.</p>
      ) : (
        <ul className="divide-y divide-divider" aria-label="Symbols">
          {bars.map((b) => {
            const expanded = open === b.symbol
            const r = b.row
            return (
              <li key={b.symbol}>
                <button
                  type="button"
                  aria-expanded={expanded}
                  onPointerEnter={(e) => {
                    if (e.pointerType === 'mouse') setOpen(b.symbol)
                  }}
                  onClick={() => setOpen((o) => (o === b.symbol ? null : b.symbol))}
                  className="grid min-h-11 w-full grid-cols-[4.5rem_1fr_5.5rem] items-center gap-2 px-4 py-1.5 text-left text-table"
                >
                  <span className="flex min-w-0 items-center gap-1 font-semibold">
                    <span className="truncate">{b.symbol}</span>
                    {r?.held ? null : r ? <span className="sr-only">(closed)</span> : null}
                  </span>
                  <span className="relative h-3" aria-hidden="true">
                    <span className="absolute inset-y-0 left-1/2 w-px bg-divider" />
                    <span
                      className={cx('absolute inset-y-0 rounded-sm', b.positive ? 'left-1/2 bg-up' : 'right-1/2 bg-down')}
                      style={{ width: `${(b.width * 50).toFixed(1)}%` }}
                    />
                  </span>
                  <span className={cx('text-right font-semibold tabular-nums', DIRECTION_TEXT[moneyDirection(b.total)])}>{formatMoney(b.total, { signed: true })}</span>
                </button>
                {expanded ? (
                  <div className="flex flex-wrap gap-x-4 gap-y-1 px-4 pb-2 text-caption text-text-2">
                    {r ? (
                      <>
                        <span>
                          Realized <b className={DIRECTION_TEXT[moneyDirection(r.realized)]}>{formatMoney(r.realized, { signed: true })}</b>
                        </span>
                        <span>
                          Unrealized <b className={DIRECTION_TEXT[moneyDirection(r.unrealized)]}>{formatMoney(r.unrealized, { signed: true })}</b>
                        </span>
                        <span>
                          Dividends <b className={DIRECTION_TEXT[moneyDirection(r.dividends)]}>{formatMoney(r.dividends, { signed: true })}</b>
                        </span>
                        {r.held ? <Pill tone="accent">Held</Pill> : <Pill tone="neutral">Closed</Pill>}
                        <Link to="/stock/$symbol" params={{ symbol: b.symbol }} className="text-accent">
                          Quote
                        </Link>
                      </>
                    ) : (
                      <span>P&amp;L corrections by the admin (they belong to no symbol).</span>
                    )}
                  </div>
                ) : null}
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
