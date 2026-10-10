/**
 * Analyst ratings card (§22.10): the latest month of Finnhub's free recommendation trends as a stacked bar with
 * counts and a consensus label, the previous months as small stacked columns, and the source. Hidden when the source
 * is "none" (no Finnhub token, an ETF) or the call fails: there are no price targets (premium only).
 */
import { type RecommendationPeriod, useRecommendations } from '@/lib/api/hooks/marketExtras'
import { cx } from '@/components/ui/cx'
import { consensus, monthLabel } from './research'

const SEGMENTS = [
  { key: 'strongBuy', label: 'Strong buy', bar: 'bg-up', text: 'text-up' },
  { key: 'buy', label: 'Buy', bar: 'bg-up/60', text: 'text-up' },
  { key: 'hold', label: 'Hold', bar: 'bg-text-3/60', text: 'text-text-2' },
  { key: 'sell', label: 'Sell', bar: 'bg-down/60', text: 'text-down' },
  { key: 'strongSell', label: 'Strong sell', bar: 'bg-down', text: 'text-down' },
] as const

const count = (p: RecommendationPeriod, k: (typeof SEGMENTS)[number]['key']) => p[k] ?? 0

export function AnalystRatings({ symbol }: { symbol: string }) {
  const { data } = useRecommendations(symbol)
  const periods = data?.periods ?? []
  const latest = periods[0]
  if (!data || data.source === 'none' || !latest || !latest.total) return null
  const c = consensus({ strongBuy: count(latest, 'strongBuy'), buy: count(latest, 'buy'), hold: count(latest, 'hold'), sell: count(latest, 'sell'), strongSell: count(latest, 'strongSell') })
  const history = periods.slice(0, 6).toReversed()
  const sourceName = data.source === 'finnhub' ? 'Finnhub' : data.source === 'simulated' ? 'SIM (simulated)' : data.source
  return (
    <section aria-label="Analyst ratings" data-testid="analyst-ratings">
      <div className="mb-2 flex items-baseline justify-between gap-2">
        <h2 className="text-section font-semibold">Analyst ratings</h2>
        {c ? (
          <span className={cx('text-body font-semibold', c.score <= 2.5 ? 'text-up' : c.score > 3.5 ? 'text-down' : 'text-text-1')}>
            {c.label}
            <span className="ml-1 text-caption font-normal text-text-3">· {latest.total} analysts</span>
          </span>
        ) : null}
      </div>
      <div className="flex h-3 overflow-hidden rounded-full bg-bg-2" role="img" aria-label={SEGMENTS.map((s) => `${s.label} ${count(latest, s.key)}`).join(', ')}>
        {SEGMENTS.map((s) => {
          const n = count(latest, s.key)
          return n > 0 ? <div key={s.key} className={s.bar} style={{ width: `${(n / latest.total!) * 100}%` }} /> : null
        })}
      </div>
      <dl className="mt-2 grid grid-cols-5 gap-1 text-center">
        {SEGMENTS.map((s) => (
          <div key={s.key}>
            <dt className="text-micro text-text-3">{s.label}</dt>
            <dd className={cx('text-body font-semibold tabular-nums', s.text)}>{count(latest, s.key)}</dd>
          </div>
        ))}
      </dl>
      {history.length > 1 ? (
        <div className="mt-3 flex items-end gap-2" aria-label="Ratings by month">
          {history.map((p) => (
            <div key={p.period} className="flex flex-1 flex-col items-center gap-1">
              <div className="flex h-12 w-full max-w-8 flex-col-reverse overflow-hidden rounded-sm bg-bg-2" title={`${monthLabel(p.period)}: ${SEGMENTS.map((s) => `${s.label} ${count(p, s.key)}`).join(', ')}`}>
                {SEGMENTS.map((s) => {
                  const n = count(p, s.key)
                  return n > 0 && p.total ? <div key={s.key} className={s.bar} style={{ height: `${(n / p.total) * 100}%` }} /> : null
                })}
              </div>
              <span className="text-micro text-text-3">{monthLabel(p.period, true)}</span>
            </div>
          ))}
        </div>
      ) : null}
      <p className="mt-2 text-micro text-text-3">
        Source: {sourceName} · as of {monthLabel(latest.period)}. Recommendation counts only; price targets are not available on the free data plan.
      </p>
    </section>
  )
}
