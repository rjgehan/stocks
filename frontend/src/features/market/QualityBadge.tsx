import type { DataQuality } from '@/lib/api/types'
import { Pill, type PillTone } from '@/components/ui/Pill'

const LABELS: Record<DataQuality, { label: string; tone: PillTone; title: string } | null> = {
  REALTIME_CONSOLIDATED: null,
  REALTIME_SINGLE_VENUE: { label: 'IEX', tone: 'neutral', title: 'Real-time IEX quote (single venue)' },
  DELAYED_15M: { label: '15m', tone: 'warn', title: 'Delayed 15 minutes' },
  SYNTHETIC: { label: 'Est.', tone: 'warn', title: 'Estimated from the last consolidated trade' },
  SIMULATED: { label: 'SIM', tone: 'ai', title: 'Simulated market data' },
  STALE: { label: 'Stale', tone: 'down', title: 'No fresh quote' },
  NONE: { label: 'No data', tone: 'flat', title: 'No quote available' },
  INDICATIVE: { label: 'Ind.', tone: 'warn', title: 'Indicative option quote (Alpaca free feed), not OPRA' },
}

/** Data-quality label (§22.1: IEX · SIM · Est. · Stale · Ind.). Consolidated real-time data needs no badge. */
export function QualityBadge({ quality }: { quality: DataQuality | null | undefined }) {
  const meta = quality ? LABELS[quality] : null
  if (!meta) return null
  return (
    <Pill tone={meta.tone} title={meta.title}>
      {meta.label}
    </Pill>
  )
}
