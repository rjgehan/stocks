/**
 * The indicator picker's panel (§22.10): every indicator with its switch and parameters. Its own module, so the quote
 * page loads it only when the picker opens.
 */
import { useState } from 'react'
import { Button } from '@/components/ui/Button'
import { cx } from '@/components/ui/cx'
import { DEFAULT_INDICATORS, INDICATOR_LABELS, type IndicatorSettings, LIMITS } from './indicatorSettings'

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
