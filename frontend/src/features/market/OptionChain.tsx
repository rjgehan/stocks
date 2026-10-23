import { useMemo, useState } from 'react'
import { usePositions } from '@/lib/api/hooks/account'
import { useOptionChain, useOptionExpirations } from '@/lib/api/hooks/market'
import { describeError } from '@/lib/api/errors'
import type { OptionContract } from '@/lib/api/types'
import { abs, sub } from '@/lib/format/decimal'
import { formatMoney } from '@/lib/format/money'
import { formatInteger, formatPrice } from '@/lib/format/number'
import { etDatePlusDays, formatTimeEt } from '@/lib/format/time'
import { type OptionRight, contractsLabel, daysToExpiry, formatOptionPrice, inTheMoney, isOption, optionLabel, parseOption, underlyingOf } from '@/lib/options'
import { EmptyState } from '@/components/ui/EmptyState'
import { IconInfo } from '@/components/ui/icons'
import { Pill } from '@/components/ui/Pill'
import { Segmented } from '@/components/ui/Segmented'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { Signed } from '@/features/account/PositionsTable'

const MONTH_DAY = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' })

/** "Nov 20" for a YYYY-MM-DD expiration. */
const shortDate = (date: string): string => MONTH_DAY.format(new Date(`${date}T12:00:00Z`))

/** The strike nearest the underlying price (the first of two equally near). */
function nearestStrike(rows: readonly OptionContract[], price: string | null | undefined): string | null {
  if (!price) return null
  let best: { strike: string; gap: string } | null = null
  for (const r of rows) {
    if (!r.strike) continue
    const gap = abs(sub(r.strike, price))
    if (!best || Number(gap) < Number(best.gap)) best = { strike: r.strike, gap }
  }
  return best?.strike ?? null
}

/**
 * The Options tab of a stock or ETF (§30): the player's contracts on this underlying, an expiration picker (nearest
 * first, with days to expiry), Calls · Puts, and one row per strike with bid, ask, last, volume and open interest.
 * In-the-money rows are shaded against the chain's underlying price; the strike nearest the money is marked. The chain
 * re-polls every 10 s while the page is visible. Choosing a row selects that contract for the trade ticket.
 */
export function OptionChain({ underlying, selected, onSelect }: { underlying: string; selected: string | undefined; onSelect: (contract: string) => void }) {
  const expirations = useOptionExpirations(underlying)
  const dates = useMemo(() => expirations.data?.expirations ?? [], [expirations.data])
  const selectedParts = parseOption(selected)
  const [picked, setPicked] = useState<string | null>(null)
  const [pickedSide, setPickedSide] = useState<OptionRight | null>(null)
  // the selected contract's expiration and side win until the player picks another one
  const expiration = picked ?? (selectedParts && dates.includes(selectedParts.expiration) ? selectedParts.expiration : (dates[0] ?? null))
  const side: OptionRight = pickedSide ?? selectedParts?.right ?? 'CALL'
  const chain = useOptionChain(underlying, expiration)
  // days to expiry count from the ET date the tab opened on
  const [today] = useState(() => etDatePlusDays(Date.now()))
  const positions = usePositions()
  const held = (positions.data ?? []).filter((p) => isOption(p.symbol) && underlyingOf(p.symbol ?? '') === underlying)

  const enabled = chain.data?.enabled ?? expirations.data?.enabled ?? true
  const feed = chain.data?.feed ?? expirations.data?.feed ?? null
  const rows = (side === 'CALL' ? chain.data?.calls : chain.data?.puts) ?? []
  const price = chain.data?.underlyingPrice ?? null
  const atm = nearestStrike(rows, price)
  const error = expirations.error ?? chain.error

  return (
    <section aria-label={`${underlying} options`} className="space-y-3">
      {!enabled ? (
        <p role="status" className="flex gap-1.5 rounded-sm bg-warn/10 p-2 text-caption text-warn">
          <IconInfo size={16} className="mt-0.5 shrink-0" />
          Options trading is off: buys to open are refused. If you hold contracts you can still sell to close.
        </p>
      ) : null}
      {feed === 'indicative' ? (
        <p className="flex items-start gap-1.5 rounded-sm bg-bg-2 p-2 text-caption text-text-2" data-testid="indicative-note">
          <Pill tone="warn" title="Indicative option quote (Alpaca free feed), not OPRA">
            Indicative quotes
          </Pill>
          <span>Derived option quotes from Alpaca's free feed, not the consolidated OPRA quotes; trades can be 15 minutes late.</span>
        </p>
      ) : null}

      {held.length ? (
        <div>
          <h3 className="mb-1 text-caption font-semibold tracking-wide text-text-2 uppercase">Your contracts</h3>
          <ul className="divide-y divide-divider rounded-md border border-divider" aria-label="Your contracts">
            {held.map((p) => (
              <li key={p.symbol}>
                <button
                  type="button"
                  onClick={() => {
                    const parts = parseOption(p.symbol)
                    if (parts && dates.includes(parts.expiration)) setPicked(parts.expiration)
                    if (parts) setPickedSide(parts.right)
                    onSelect(p.symbol!)
                  }}
                  aria-pressed={p.symbol === selected}
                  className={cx('flex min-h-11 w-full items-center justify-between gap-3 px-3 py-1.5 text-left text-table hover:bg-bg-2', p.symbol === selected && 'bg-accent-bg')}
                >
                  <span className="min-w-0">
                    <span className="block truncate font-semibold">{optionLabel(p.symbol)}</span>
                    <span className="text-caption text-text-3">
                      {contractsLabel(p.qty)} · avg {formatOptionPrice(p.avgCost)}
                    </span>
                  </span>
                  <span className="shrink-0 text-right">
                    <span className="block">{formatMoney(p.marketValue)}</span>
                    <Signed value={p.unrealizedPnl} pct={p.unrealizedReturn} className="text-caption" />
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {error && !dates.length ? (
        <EmptyState title="Options are unavailable right now" body={describeError(error).detail ?? describeError(error).title} />
      ) : (
        <>
          <div role="radiogroup" aria-label="Expiration" className="scrollbar-none -mx-1 flex gap-1 overflow-x-auto px-1">
            {expirations.isPending
              ? Array.from({ length: 5 }, (_, i) => <span key={i} className="h-11 w-20 shrink-0 animate-pulse rounded-sm bg-bg-2 tablet:h-9" />)
              : dates.map((d) => {
                  const dte = daysToExpiry(d, today)
                  return (
                    <button
                      key={d}
                      type="button"
                      role="radio"
                      aria-checked={d === expiration}
                      aria-label={`${shortDate(d)}, ${dte === 0 ? 'expires today' : `${dte} days to expiry`}`}
                      onClick={() => setPicked(d)}
                      className={cx(
                        'flex h-11 shrink-0 flex-col items-center justify-center rounded-sm px-2.5 text-caption font-semibold transition-colors tablet:h-9',
                        d === expiration ? 'bg-bg-3 text-text-1' : 'bg-bg-2/60 text-text-2 hover:bg-bg-2 hover:text-text-1',
                      )}
                    >
                      <span className="leading-4">{shortDate(d)}</span>
                      <span className="text-micro leading-3 font-medium text-text-3">{dte === 0 ? 'today' : `${dte}d`}</span>
                    </button>
                  )
                })}
          </div>

          <div className="flex flex-wrap items-center justify-between gap-2">
            <Segmented
              label="Calls or puts"
              size="sm"
              value={side}
              onChange={setPickedSide}
              options={[
                { value: 'CALL', label: 'Calls' },
                { value: 'PUT', label: 'Puts' },
              ]}
            />
            <p className="text-caption text-text-3">
              {underlying} <span className="font-semibold text-text-1">{formatPrice(price)}</span>
              {chain.data?.asOf ? ` · ${formatTimeEt(chain.data.asOf)}` : ''} · 1 contract = 100 shares
            </p>
          </div>

          <div className="-mx-4 overflow-x-auto tablet:mx-0">
            <table className="w-full text-table" aria-label={`${underlying} ${side === 'CALL' ? 'calls' : 'puts'}${expiration ? ` expiring ${shortDate(expiration)}` : ''}`}>
              <thead className="text-left text-micro tracking-wide text-text-3 uppercase">
                <tr className="h-8">
                  <th className="px-4 font-medium tablet:px-2">Strike</th>
                  <th className="px-2 text-right font-medium">Bid</th>
                  <th className="px-2 text-right font-medium">Ask</th>
                  <th className="px-2 text-right font-medium">Last</th>
                  <th className="px-2 text-right font-medium">Volume</th>
                  <th className="px-4 text-right font-medium tablet:px-2">Open int.</th>
                </tr>
              </thead>
              {chain.isPending && expiration ? (
                <tbody>
                  <tr>
                    <td colSpan={6} className="px-4 tablet:px-2">
                      <SkeletonRows rows={8} cols={6} />
                    </td>
                  </tr>
                </tbody>
              ) : (
                <tbody className="divide-y divide-divider">
                  {rows.map((c) => {
                    const itm = c.strike != null && c.right != null && inTheMoney(c.right, c.strike, price)
                    const isAtm = c.strike === atm
                    const isSelected = c.symbol === selected
                    return (
                      <tr
                        key={c.symbol}
                        data-itm={itm || undefined}
                        aria-selected={isSelected}
                        onClick={() => c.symbol && onSelect(c.symbol)}
                        className={cx(
                          'h-10 cursor-pointer hover:bg-bg-3/60',
                          itm && 'bg-bg-2',
                          isAtm && 'border-y-2 border-y-accent/50',
                          isSelected && 'bg-accent-bg outline-2 -outline-offset-2 outline-accent',
                        )}
                      >
                        <td className="px-4 font-semibold tablet:px-2">
                          <button
                            type="button"
                            onClick={(e) => {
                              e.stopPropagation()
                              if (c.symbol) onSelect(c.symbol)
                            }}
                            aria-label={`Trade ${optionLabel(c.symbol)}`}
                            className="inline-flex min-h-10 items-center gap-1.5 hover:text-accent"
                          >
                            {formatPrice(c.strike)}
                            {isAtm ? (
                              <Pill tone="accent" title={`Nearest the money (${underlying} ${formatPrice(price)})`}>
                                ATM
                              </Pill>
                            ) : null}
                          </button>
                        </td>
                        <td className="px-2 text-right">{formatOptionPrice(c.bid)}</td>
                        <td className="px-2 text-right">{formatOptionPrice(c.ask)}</td>
                        <td className="px-2 text-right text-text-2">{formatOptionPrice(c.last)}</td>
                        <td className="px-2 text-right text-text-2">{formatInteger(c.volume)}</td>
                        <td className="px-4 text-right text-text-2 tablet:px-2">{formatInteger(c.openInterest)}</td>
                      </tr>
                    )
                  })}
                </tbody>
              )}
            </table>
            {!chain.isPending && expiration && rows.length === 0 ? <p className="px-4 py-3 text-caption text-text-3">No contracts listed for this expiration.</p> : null}
          </div>
          <p className="text-micro text-text-3">
            Shaded rows are in the money. Prices are per share; a contract costs the price × 100. Long calls and puts only: buy to open, sell to
            close; at expiration an in-the-money contract is cash-settled at its intrinsic value.
          </p>
        </>
      )}
    </section>
  )
}
