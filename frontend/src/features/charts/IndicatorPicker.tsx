/**
 * Chart toolbar controls (§22.10): the chart-type picker (Candles, Hollow candles, Heikin Ashi, Line) and the
 * indicator picker (a popover on tablet and desktop, a bottom sheet on the phone) with a switch and the parameters
 * of every indicator. Settings persist per player on this device (`useIndicatorSettings`).
 */
import { useMemo, useState } from 'react'
import { useMe } from '@/lib/api/hooks/identity'
import { readLocal, writeLocal } from '@/lib/prefs/local'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { Sheet } from '@/components/ui/Sheet'
import { cx } from '@/components/ui/cx'
import { IconArea, IconCandles } from '@/components/ui/icons'
import {
  type ChartType,
  CHART_TYPES,
  CHART_TYPE_LABELS,
  DEFAULT_INDICATORS,
  INDICATOR_LABELS,
  type IndicatorSettings,
  LIMITS,
  activeLabels,
  loadIndicators,
  parseChartType,
  sanitizeIndicators,
  saveIndicators,
} from './indicatorSettings'

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
  heikin: <span className="text-[11px] font-bold tracking-tight">HA</span>,
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

/** A bounded number input that commits valid values as they are typed and restores the last good one on blur. */
function NumberInput({ label, value, min, max, step = 1, onCommit }: { label: string; value: number; min: number; max: number; step?: number; onCommit: (v: number) => void }) {
  // the text being typed (null when not editing): an invalid draft never reaches the settings
  const [draft, setDraft] = useState<string | null>(null)
  return (
    <label className="flex items-center gap-1 text-caption text-text-3">
      <span className="sr-only">{label}</span>
      <input
        type="number"
        inputMode="decimal"
        aria-label={label}
        min={min}
        max={max}
        step={step}
        value={draft ?? String(value)}
        onChange={(e) => {
          setDraft(e.target.value)
          const n = Number(e.target.value)
          if (e.target.value !== '' && Number.isFinite(n) && n >= min && n <= max) onCommit(n)
        }}
        onBlur={() => setDraft(null)}
        className="h-11 w-16 rounded-md border border-border bg-bg-2 px-2 text-right text-body text-text-1 tabular-nums outline-none focus:border-accent tablet:h-8 tablet:w-14 tablet:text-caption"
      />
    </label>
  )
}

function Row({ id, label, hint, on, onToggle, children }: { id: string; label: string; hint: string; on: boolean; onToggle: (on: boolean) => void; children?: React.ReactNode }) {
  return (
    <div className="flex min-h-12 flex-wrap items-center justify-between gap-2 py-1.5">
      <label className="flex min-w-0 cursor-pointer items-center gap-2.5" title={hint}>
        <input id={id} type="checkbox" aria-label={label} checked={on} onChange={(e) => onToggle(e.target.checked)} className="size-5 accent-[var(--accent)] tablet:size-4" />
        <span className="text-body font-semibold text-text-1">{label}</span>
        <span className="hidden truncate text-caption text-text-3 wide:inline">{hint}</span>
      </label>
      <div className={cx('flex items-center gap-1.5', !on && 'opacity-50')}>{children}</div>
    </div>
  )
}

const id = (k: string) => `indicator-${k}`

/** Every indicator with its switch and parameters. */
export function IndicatorPanel({ value, onChange, intraday }: { value: IndicatorSettings; onChange: (s: IndicatorSettings) => void; intraday: boolean }) {
  const set = <K extends keyof IndicatorSettings>(key: K, patch: Partial<IndicatorSettings[K]>) => onChange({ ...value, [key]: { ...value[key], ...patch } })
  const periodInputs = (key: 'ma' | 'ema', slots: number) =>
    Array.from({ length: slots }, (_, i) => (
      <NumberInput
        key={i}
        label={`${INDICATOR_LABELS[key].short} period ${i + 1}`}
        value={value[key].periods[i] ?? 0}
        min={0}
        max={LIMITS.period.max}
        onCommit={(n) => {
          const next = [...value[key].periods]
          next[i] = n
          set(key, { periods: next.filter((p) => p > 0) })   // 0 switches that line off
        }}
      />
    ))
  return (
    <div className="space-y-3 px-4 py-3" data-testid="indicator-panel">
      <section>
        <h3 className="text-micro font-semibold tracking-wide text-text-3 uppercase">On the price chart</h3>
        <div className="divide-y divide-divider">
          <Row id={id('ma')} label="MA" hint={INDICATOR_LABELS.ma.long} on={value.ma.on} onToggle={(on) => set('ma', { on })}>
            {periodInputs('ma', 3)}
          </Row>
          <Row id={id('ema')} label="EMA" hint={INDICATOR_LABELS.ema.long} on={value.ema.on} onToggle={(on) => set('ema', { on })}>
            {periodInputs('ema', 3)}
          </Row>
          <Row id={id('boll')} label="BOLL" hint={INDICATOR_LABELS.boll.long} on={value.boll.on} onToggle={(on) => set('boll', { on })}>
            <NumberInput label="BOLL period" value={value.boll.n} min={2} max={LIMITS.period.max} onCommit={(n) => set('boll', { n })} />
            <NumberInput label="BOLL standard deviations" value={value.boll.k} min={LIMITS.bollK.min} max={LIMITS.bollK.max} step={0.1} onCommit={(k) => set('boll', { k })} />
          </Row>
          <Row id={id('vwap')} label="VWAP" hint={INDICATOR_LABELS.vwap.long} on={value.vwap.on} onToggle={(on) => set('vwap', { on })}>
            {!intraday && value.vwap.on ? <span className="text-caption text-text-3">Intraday ranges only</span> : null}
          </Row>
        </div>
      </section>
      <section>
        <h3 className="text-micro font-semibold tracking-wide text-text-3 uppercase">Below the chart</h3>
        <div className="divide-y divide-divider">
          <Row id={id('macd')} label="MACD" hint={INDICATOR_LABELS.macd.long} on={value.macd.on} onToggle={(on) => set('macd', { on })}>
            <NumberInput label="MACD fast" value={value.macd.fast} min={LIMITS.osc.min} max={LIMITS.osc.max} onCommit={(fast) => set('macd', { fast })} />
            <NumberInput label="MACD slow" value={value.macd.slow} min={LIMITS.osc.min} max={LIMITS.osc.max} onCommit={(slow) => set('macd', { slow })} />
            <NumberInput label="MACD signal" value={value.macd.signal} min={LIMITS.osc.min} max={LIMITS.osc.max} onCommit={(signal) => set('macd', { signal })} />
          </Row>
          <Row id={id('rsi')} label="RSI" hint={INDICATOR_LABELS.rsi.long} on={value.rsi.on} onToggle={(on) => set('rsi', { on })}>
            <NumberInput label="RSI period" value={value.rsi.n} min={LIMITS.rsi.min} max={LIMITS.rsi.max} onCommit={(n) => set('rsi', { n })} />
          </Row>
          <Row id={id('kdj')} label="KDJ" hint={INDICATOR_LABELS.kdj.long} on={value.kdj.on} onToggle={(on) => set('kdj', { on })}>
            <NumberInput label="KDJ period" value={value.kdj.n} min={LIMITS.osc.min} max={LIMITS.osc.max} onCommit={(n) => set('kdj', { n })} />
            <NumberInput label="KDJ K smoothing" value={value.kdj.m1} min={LIMITS.osc.min} max={LIMITS.osc.max} onCommit={(m1) => set('kdj', { m1 })} />
            <NumberInput label="KDJ D smoothing" value={value.kdj.m2} min={LIMITS.osc.min} max={LIMITS.osc.max} onCommit={(m2) => set('kdj', { m2 })} />
          </Row>
        </div>
      </section>
      <div className="flex justify-end">
        <Button size="sm" variant="ghost" onClick={() => onChange(DEFAULT_INDICATORS)}>
          Reset to defaults
        </Button>
      </div>
    </div>
  )
}

/** The "Indicators" toolbar button: a dialog (tablet, desktop) or a bottom sheet (phone). */
export function IndicatorPicker({ value, onChange, phone, intraday }: { value: IndicatorSettings; onChange: (s: IndicatorSettings) => void; phone: boolean; intraday: boolean }) {
  const [open, setOpen] = useState(false)
  const count = activeLabels(value).length
  const triggerClass = 'inline-flex h-11 items-center gap-1.5 rounded-md bg-bg-2 px-2.5 text-caption font-semibold text-text-2 hover:text-text-1 tablet:h-7'
  const panel = <IndicatorPanel value={value} onChange={onChange} intraday={intraday} />
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
