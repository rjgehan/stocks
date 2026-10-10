import { useEffect, useRef, useState } from 'react'
import type { Asset, Quote } from '@/lib/api/types'
import { formatPrice } from '@/lib/format/number'
import { formatTimeEt } from '@/lib/format/time'
import { Pill } from '@/components/ui/Pill'
import { Skeleton } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { Change } from './Change'
import { QualityBadge } from './QualityBadge'
import { usePriceFlash } from './usePriceFlash'

/**
 * Quote page header (§22.4): symbol, name and exchange; the last price large (32/600 phone, 28/600
 * desktop); signed change; the extended-hours line; fractionable and data-quality badges. On scroll a
 * compact sticky bar takes over ("sticky, shrinking").
 */
export function QuoteHeader({
  symbol,
  asset,
  quote,
  actions,
  scrollRoot,
  stickyTopClass = 'top-0',
}: {
  symbol: string
  asset: Asset | undefined
  quote: Quote | undefined
  actions?: React.ReactNode
  /** The scrolling element the header lives in (for the compact sticky bar). */
  scrollRoot?: React.RefObject<HTMLElement | null>
  /** Offset of the compact bar when another sticky header sits above it (phone pushed screens). */
  stickyTopClass?: string
}) {
  const sentinel = useRef<HTMLDivElement>(null)
  const [compact, setCompact] = useState(false)
  const flash = usePriceFlash(quote?.last)

  useEffect(() => {
    const el = sentinel.current
    if (!el || typeof IntersectionObserver === 'undefined') return
    const io = new IntersectionObserver(([entry]) => setCompact(!entry?.isIntersecting), { root: scrollRoot?.current ?? null, threshold: 0 })
    io.observe(el)
    return () => io.disconnect()
  }, [scrollRoot])

  const ext = quote?.extended
  return (
    <>
      <div
        className={cx(
          'sticky z-10 flex items-center gap-3 overflow-hidden bg-bg-1/95 px-4 backdrop-blur transition-opacity duration-200',
          stickyTopClass,
          compact ? 'h-11 border-b border-divider opacity-100' : 'pointer-events-none -mb-px h-0 opacity-0',
        )}
        aria-hidden={!compact}
      >
        <span className="text-section font-semibold">{symbol}</span>
        <span className="text-section font-semibold">{formatPrice(quote?.last)}</span>
        <Change change={quote?.change} pct={quote?.changePct} reference={quote?.last} className="text-caption" />
      </div>

      <header className="px-4 pt-4 pb-3 desktop:px-5">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="text-[20px] leading-7 font-bold tracking-tight">{symbol}</h1>
              {asset?.fractionable ? <Pill tone="accent" title="Fractional shares and dollar orders allowed">Fractional</Pill> : null}
              <QualityBadge quality={quote?.quality} />
              {quote?.halted ? <Pill tone="down">Halted</Pill> : null}
            </div>
            <p className="mt-0.5 truncate text-caption text-text-2">
              {asset ? (
                <>
                  {asset.name}
                  {asset.exchange ? <span className="text-text-3"> · {asset.exchange}</span> : null}
                </>
              ) : (
                <Skeleton className="h-3 w-40" />
              )}
            </p>
          </div>
          {actions ? <div className="flex shrink-0 items-center gap-1">{actions}</div> : null}
        </div>

        <div ref={sentinel} className="mt-3 flex flex-wrap items-baseline gap-x-3 gap-y-1">
          {quote ? (
            <span
              key={flash.key}
              className={cx(
                'rounded-sm px-0.5 -mx-0.5 text-hero-phone font-semibold tablet:text-hero',
                flash.dir === 'up' && 'animate-flash-up',
                flash.dir === 'down' && 'animate-flash-down',
              )}
            >
              {formatPrice(quote.last)}
            </span>
          ) : (
            <Skeleton className="h-8 w-32" />
          )}
          {quote ? <Change change={quote.change} pct={quote.changePct} reference={quote.last} className="text-body" /> : null}
        </div>
        <p className="mt-1 text-caption text-text-3">
          {ext ? (
            <>
              <span className="font-semibold text-session-ext">{ext.session === 'PRE' ? 'Pre-market' : 'After hours'}</span>{' '}
              <span className="text-text-1">{formatPrice(ext.price)}</span>{' '}
              <Change change={ext.change} pct={ext.changePct} reference={ext.price} showArrow={false} /> · {formatTimeEt(ext.at)}
            </>
          ) : quote?.asOf ? (
            <>As of {formatTimeEt(quote.asOf)}</>
          ) : (
            ' '
          )}
        </p>
      </header>
    </>
  )
}
