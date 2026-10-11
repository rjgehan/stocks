/**
 * Chart toolbar controls (§22.10): the chart-type picker (Candles, Hollow candles, Heikin Ashi, Line) and the
 * indicator picker (a popover on tablet and desktop, a bottom sheet on the phone) with a switch and the parameters
 * of every indicator. Settings persist per player on this device (`useIndicatorSettings`).
 */
import { Suspense, lazy, useMemo, useState } from 'react'
import { useMe } from '@/lib/api/hooks/identity'
import { readLocal, writeLocal } from '@/lib/prefs/local'
import { Dialog } from '@/components/ui/Dialog'
import { Sheet } from '@/components/ui/Sheet'
import { cx } from '@/components/ui/cx'
import { IconArea, IconCandles } from '@/components/ui/icons'
import {
  type ChartType,
  CHART_TYPES,
  CHART_TYPE_LABELS,
  type IndicatorSettings,
  activeLabels,
  loadIndicators,
  parseChartType,
  sanitizeIndicators,
  saveIndicators,
} from './indicatorSettings'

/** The panel with every indicator's switch and parameters loads when the picker first opens. */
const IndicatorPanel = lazy(() => import('./IndicatorPanel').then((m) => ({ default: m.IndicatorPanel })))

const TYPE_KEY = 'stocks.chart.style'

/** The chart type, remembered per device; candles by default and a line on the phone's 1D view (§22.4). */
export function useChartType(phone: boolean, oneDay: boolean): [ChartType, (t: ChartType) => void] {
  const [chosen, setChosen] = useState<ChartType | null>(() => parseChartType(readLocal(TYPE_KEY, '')))
  const type: ChartType = chosen ?? (phone && oneDay ? 'area' : 'candles')
  return [
    type,
    (t) => {
      setChosen(t)
      writeLocal(TYPE_KEY, t)
    },
  ]
}

/** The indicator settings of the signed-in player on this device. */
export function useIndicatorSettings(): [IndicatorSettings, (next: IndicatorSettings) => void] {
  const me = useMe()
  const playerId = me.data?.playerId ?? null
  const [edited, setEdited] = useState<{ player: string | null; settings: IndicatorSettings } | null>(null)
  const stored = useMemo(() => loadIndicators(playerId), [playerId])
  return [
    edited && edited.player === playerId ? edited.settings : stored,
    (next) => {
      const clean = sanitizeIndicators(next)
      saveIndicators(playerId, clean)
      setEdited({ player: playerId, settings: clean })
    },
  ]
}

const IconHollow = ({ size = 16 }: { size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" aria-hidden="true">
    <path d="M7 3v4M7 17v4M17 6v3M17 15v5" />
    <rect x="5" y="7" width="4" height="10" rx="1" />
    <rect x="15" y="9" width="4" height="6" rx="1" fill="currentColor" />
  </svg>
)

const TYPE_ICONS: Record<ChartType, React.ReactNode> = {
  candles: <IconCandles size={16} />,
  hollow: <IconHollow />,
  heikin: (
    <span className="text-[11px] font-bold tracking-tight" aria-hidden="true">
      HA
    </span>
  ),
  area: <IconArea size={16} />,
}

/** Four chart types as a compact radio group (icons with labels for screen readers and tooltips). */
export function ChartTypePicker({ value, onChange }: { value: ChartType; onChange: (t: ChartType) => void }) {
  return (
    <div role="radiogroup" aria-label="Chart type" className="inline-flex rounded-md bg-bg-2 p-0.5">
      {CHART_TYPES.map((t) => (
        <button
          key={t}
          type="button"
          role="radio"
          aria-checked={t === value}
          title={CHART_TYPE_LABELS[t]}
          onClick={() => onChange(t)}
          className={cx(
            'flex h-11 min-w-11 items-center justify-center rounded-[6px] px-2 transition-colors tablet:h-7 tablet:min-w-8',
            t === value ? 'bg-bg-1 text-text-1 shadow-sm' : 'text-text-2 hover:text-text-1',
          )}
        >
          {TYPE_ICONS[t]}
          <span className="sr-only">{CHART_TYPE_LABELS[t]}</span>
        </button>
      ))}
    </div>
  )
}

/** The "Indicators" toolbar button: a dialog (tablet, desktop) or a bottom sheet (phone). */
export function IndicatorPicker({ value, onChange, phone, intraday }: { value: IndicatorSettings; onChange: (s: IndicatorSettings) => void; phone: boolean; intraday: boolean }) {
  const [open, setOpen] = useState(false)
  const count = activeLabels(value).length
  const triggerClass = 'inline-flex h-11 items-center gap-1.5 rounded-md bg-bg-2 px-2.5 text-caption font-semibold text-text-2 hover:text-text-1 tablet:h-7'
  const panel = open ? (
    <Suspense fallback={<div className="h-64" aria-busy="true" />}>
      <IndicatorPanel value={value} onChange={onChange} intraday={intraday} />
    </Suspense>
  ) : null
  return (
    <>
      <button type="button" className={triggerClass} onClick={() => setOpen(true)} aria-haspopup="dialog" title={activeLabels(value).join(' · ') || undefined}>
        <span className="font-mono text-[13px] italic" aria-hidden="true">
          ƒx
        </span>
        <span>Indicators</span>
        {count > 0 ? <span className="rounded-full bg-accent px-1.5 text-micro font-bold text-text-inverse">{count}</span> : null}
      </button>
      {phone ? (
        <Sheet open={open} onClose={() => setOpen(false)} title="Indicators">
          {panel}
        </Sheet>
      ) : (
        <Dialog open={open} onClose={() => setOpen(false)} title="Indicators">
          {panel}
        </Dialog>
      )}
    </>
  )
}
