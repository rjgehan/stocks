import { useEffect, useState } from 'react'
import { useClock } from '@/lib/api/hooks/market'
import type { SessionType } from '@/lib/api/types'
import { formatCountdown, formatTimeEt } from '@/lib/format/time'
import { cx } from '@/components/ui/cx'

const SESSION: Record<SessionType, { label: string; dot: string; verb: string }> = {
  REGULAR: { label: 'Market Open', dot: 'bg-up', verb: 'closes' },
  PRE: { label: 'Pre-market', dot: 'bg-session-ext', verb: 'opens' },
  POST: { label: 'After hours', dot: 'bg-session-ext', verb: 'ends' },
  CLOSED: { label: 'Market Closed', dot: 'bg-session-closed', verb: 'opens' },
}

function useNow(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(t)
  }, [intervalMs])
  return now
}

/** "● Market Open · closes 2:14:07" (§22.3), driven by GET /market/clock and /topic/market.session. */
export function SessionChip({ compact, detailClassName }: { compact?: boolean; detailClassName?: string }) {
  const { data: clock } = useClock()
  const now = useNow(1000)
  if (!clock) return <span className="h-7 w-40 animate-pulse rounded-full bg-bg-2" aria-hidden="true" />
  const s = SESSION[clock.session]
  const target = clock.session === 'CLOSED' ? null : clock.sessionEndsAt
  const detail = target ? `${s.verb} ${formatCountdown(Date.parse(target) - now)}` : `opens ${formatTimeEt(clock.nextSessionAt)}`
  return (
    <span
      className="inline-flex h-7 shrink-0 items-center gap-1.5 rounded-full border border-border bg-bg-2 px-2.5 text-caption font-medium whitespace-nowrap"
      role="status"
      aria-live="off"
      title={`${s.label} · ${detail}`}
    >
      <span className={cx('size-2 rounded-full', s.dot, clock.session === 'REGULAR' && 'motion-safe:animate-pulse')} aria-hidden="true" />
      <span className="text-text-1">{s.label}</span>
      {compact ? null : <span className={cx('text-text-3', detailClassName)}>· {detail}</span>}
    </span>
  )
}
