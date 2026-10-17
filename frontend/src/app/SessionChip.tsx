import { useEffect, useState } from 'react'
import { useClock } from '@/lib/api/hooks/market'
import type { SessionType } from '@/lib/api/types'
import { formatCountdown, formatSessionStartEt, serverNowMs } from '@/lib/format/time'
import { cx } from '@/components/ui/cx'

const SESSION: Record<SessionType, { label: string; short: string; dot: string; verb: string }> = {
  REGULAR: { label: 'Market Open', short: 'Open', dot: 'bg-up', verb: 'closes' },
  PRE: { label: 'Pre-market', short: 'Pre', dot: 'bg-session-ext', verb: 'opens' },
  POST: { label: 'After hours', short: 'After', dot: 'bg-session-ext', verb: 'ends' },
  CLOSED: { label: 'Market Closed', short: 'Closed', dot: 'bg-session-closed', verb: 'opens' },
}

function useNow(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(t)
  }, [intervalMs])
  return now
}

/**
 * "● Market Open · closes 2:14:07" (§22.3), driven by GET /market/clock and /topic/market.session. The countdown
 * runs on the server's clock (`Clock.now` plus the time since it was fetched), not the device's: the dev and e2e
 * backends may run an offset or anchored SIM clock (§15.7). The chip is the US stock market's; while the stock market is
 * closed and crypto is on, a quiet "Crypto 24/7" says the pairs still trade.
 */
export function SessionChip({ compact, detailClassName, truncate }: { compact?: boolean; detailClassName?: string; truncate?: boolean }) {
  const { data: clock, dataUpdatedAt } = useClock()
  const localNow = useNow(1000)
  if (!clock?.session) return <span className="h-7 w-40 animate-pulse rounded-full bg-bg-2" aria-hidden="true" />
  const now = clock.now ? serverNowMs(clock.now, dataUpdatedAt, localNow) : localNow
  const s = SESSION[clock.session]
  const target = clock.session === 'CLOSED' ? null : clock.sessionEndsAt
  // PRE "opens" means the regular open; POST "ends" at 20:00 ET
  const detail = target
    ? `${clock.session === 'PRE' ? 'opens' : s.verb} ${formatCountdown(Date.parse(target) - now)}`
    : `opens ${formatSessionStartEt(clock.nextSessionAt, now)}`
  const cryptoOpen = clock.session === 'CLOSED' && clock.crypto?.enabled === true
  return (
    <span
      className={cx(
        'inline-flex h-7 items-center gap-1.5 rounded-full border border-border bg-bg-2 px-2.5 text-caption font-medium whitespace-nowrap',
        // phone top bar: the long closed-market detail truncates instead of pushing the NAV onto two lines
        truncate ? 'min-w-0 shrink' : 'shrink-0',
      )}
      role="status"
      aria-live="off"
      title={`${s.label} · ${detail}${clock.earlyClose ? ' · early close today' : ''}${cryptoOpen ? ' · Crypto trades 24/7' : ''}`}
    >
      <span className={cx('size-2 shrink-0 rounded-full', s.dot, clock.session === 'REGULAR' && 'motion-safe:animate-pulse')} aria-hidden="true" />
      <span className="shrink-0 text-text-1">{s.label}</span>
      {compact ? null : <span className={cx('text-text-3', truncate && 'min-w-0 truncate', detailClassName)}>· {detail}</span>}
      {cryptoOpen && !compact && !truncate ? <span className={cx('text-text-3', detailClassName)}>· Crypto 24/7</span> : null}
    </span>
  )
}
