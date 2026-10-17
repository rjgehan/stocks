/**
 * The screener (§22.10): preset chips, a filter bar (price, market cap, change, relative volume, sectors, type), a
 * sortable table on tablet and desktop and cards on the phone, "Load more" through the server's cursor, and the
 * snapshot's time and coverage. Every filter lives in the URL, so a screen can be bookmarked or shared.
 */
import { Link } from '@tanstack/react-router'
import { type ScreenSort, type ScreenerRow, useScreener } from '@/lib/api/hooks/marketExtras'
import { abbreviate, formatPct, formatPrice, pctDirection } from '@/lib/format/number'
import { formatTimeEt } from '@/lib/format/time'
import { PushedHeader } from '@/app/PushedHeader'
import type { Breakpoint } from '@/app/useBreakpoint'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { Popover } from '@/components/ui/Popover'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { IconArrowDown, IconArrowUp, IconChevronDown } from '@/components/ui/icons'
import { StarButton } from '@/features/watchlist/StarButton'
import { toPath } from '@/lib/symbols'
import {
  CAPS,
  CHANGES,
  type CapBucket,
  type ChangeBucket,
  PRESETS,
  REL_VOLUMES,
  SECTORS,
  SECTOR_LABEL,
  type ScreenerSearch,
  activeFilterCount,
  nextSort,
  toScreenParams,
} from './screenerParams'

const PILL = { up: 'bg-up text-on-updown', down: 'bg-down text-on-updown', flat: 'bg-bg-3 text-text-1' } as const

const COLUMNS: readonly { sort: ScreenSort; label: string; className: string; title?: string }[] = [
  { sort: 'PRICE', label: 'Price', className: 'w-24 text-right' },
  { sort: 'CHANGE_PCT', label: '% Chg', className: 'w-24 text-right' },
  { sort: 'VOLUME', label: 'Volume', className: 'w-24 text-right' },
  { sort: 'REL_VOLUME', label: 'Rel. vol', className: 'w-20 text-right', title: 'Volume today ÷ 20-day average volume' },
  { sort: 'MARKET_CAP', label: 'Mkt cap', className: 'w-24 text-right' },
]

function Select<T extends string>({ label, value, options, onChange }: { label: string; value: T | undefined; options: readonly { value: T; label: string }[]; onChange: (v: T | undefined) => void }) {
  return (
    <label className="flex flex-col gap-0.5">
      <span className="text-micro font-medium text-text-3">{label}</span>
      <select
        aria-label={label}
        value={value ?? ''}
        onChange={(e) => onChange((e.target.value || undefined) as T | undefined)}
        className="h-11 rounded-md border border-border bg-bg-2 px-2 text-caption text-text-1 outline-none focus:border-accent tablet:h-8"
      >
        <option value="">Any</option>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  )
}

function PriceInput({ label, value, onCommit }: { label: string; value: string | undefined; onCommit: (v: string | undefined) => void }) {
  return (
    <label className="flex flex-col gap-0.5">
      <span className="text-micro font-medium text-text-3">{label}</span>
      <input
        key={value ?? ''}
        aria-label={label}
        inputMode="decimal"
        defaultValue={value ?? ''}
        placeholder="$"
        onBlur={(e) => {
          const v = e.target.value.trim()
          onCommit(/^\d{1,7}(\.\d{1,4})?$/.test(v) ? v : undefined)
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
        }}
        className="h-11 w-20 rounded-md border border-border bg-bg-2 px-2 text-right text-caption text-text-1 tabular-nums outline-none focus:border-accent tablet:h-8"
      />
    </label>
  )
}

function SectorPicker({ value, onChange }: { value: string | undefined; onChange: (v: string | undefined) => void }) {
  const chosen = new Set(value ? value.split(',') : [])
  const toggle = (s: string) => {
    const next = new Set(chosen)
    if (next.has(s)) next.delete(s)
    else next.add(s)
    onChange(next.size ? SECTORS.map((x) => x.value).filter((x) => next.has(x)).join(',') : undefined)
  }
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-micro font-medium text-text-3">Sectors</span>
      <Popover
        label="Sectors"
        align="start"
        className="w-72"
        renderTrigger={(props) => (
          <button type="button" {...props} className="flex h-11 items-center gap-1 rounded-md border border-border bg-bg-2 px-2 text-caption text-text-1 tablet:h-8">
            {chosen.size === 0 ? 'Any' : chosen.size === 1 ? SECTOR_LABEL[[...chosen][0]!] : `${chosen.size} sectors`}
            <IconChevronDown size={14} />
          </button>
        )}
      >
        <div className="max-h-80 overflow-y-auto px-3 py-2">
          {SECTORS.map((s) => (
            <label key={s.value} className="flex min-h-10 cursor-pointer items-center gap-2 text-caption">
              <input type="checkbox" checked={chosen.has(s.value)} onChange={() => toggle(s.value)} className="size-4 accent-[var(--accent)]" />
              {s.label}
            </label>
          ))}
          {chosen.size ? (
            <Button size="sm" variant="ghost" onClick={() => onChange(undefined)}>
              Clear sectors
            </Button>
          ) : null}
        </div>
      </Popover>
    </div>
  )
}

function SortHeader({ column, label, title, sort, order, onSort, className }: { column: ScreenSort; label: string; title?: string; sort: ScreenSort; order: 'ASC' | 'DESC'; onSort: (c: ScreenSort) => void; className?: string }) {
  const active = sort === column
  return (
    <th scope="col" aria-sort={active ? (order === 'ASC' ? 'ascending' : 'descending') : 'none'} className={cx('py-1.5 font-medium', className)} title={title}>
      <button type="button" onClick={() => onSort(column)} className={cx('inline-flex items-center gap-0.5 uppercase hover:text-text-1', active && 'text-text-1')}>
        {label}
        {active ? order === 'ASC' ? <IconArrowUp size={12} /> : <IconArrowDown size={12} /> : null}
      </button>
    </th>
  )
}

const relVol = (r: ScreenerRow) => (r.relVolume ? `${r.relVolume}×` : '--')
const vol = (r: ScreenerRow) => `${abbreviate(r.volume)}${r.volumeIsPartial ? '*' : ''}`

export function ScreenerScreen({ search, breakpoint, onSearch }: { search: ScreenerSearch; breakpoint: Breakpoint; onSearch: (patch: Partial<ScreenerSearch>) => void }) {
  const q = useScreener(toScreenParams(search))
  const phone = breakpoint === 'phone'
  const first = q.data?.pages[0]
  const rows = (q.data?.pages ?? []).flatMap((p) => p.items ?? []).filter((r) => r.symbol)
  const sort = first?.sort ?? search.sort ?? 'MARKET_CAP'
  const order = first?.order ?? search.order ?? 'DESC'
  const onSort = (c: ScreenSort) => onSearch(nextSort(search, c, { sort, order }))
  const partial = rows.some((r) => r.volumeIsPartial)
  const filters = activeFilterCount(search)
  // the previous screen stays on screen (dimmed, aria-busy) until the new one arrives
  const stale = q.isPlaceholderData

  const body = (
    <div className="mx-auto w-full max-w-6xl space-y-3 px-4 py-4 desktop:px-6">
      <div className="hidden items-baseline justify-between tablet:flex">
        <h1 className="text-[20px] leading-7 font-bold tracking-tight">Screener</h1>
        {first ? <span className="text-caption text-text-3">{first.total} matches</span> : null}
      </div>
      <div role="radiogroup" aria-label="Preset screens" className="scrollbar-none -mx-4 flex gap-1.5 overflow-x-auto px-4">
        {PRESETS.map((p) => (
          <button
            key={p.value}
            type="button"
            role="radio"
            aria-checked={search.preset === p.value}
            title={p.hint}
            onClick={() => onSearch({ preset: search.preset === p.value ? undefined : p.value, sort: undefined, order: undefined })}
            className={cx(
              'h-11 shrink-0 rounded-full border px-3 text-caption font-semibold transition-colors tablet:h-8',
              search.preset === p.value ? 'border-accent bg-accent-bg text-accent' : 'border-border text-text-2 hover:text-text-1',
            )}
          >
            {p.label}
          </button>
        ))}
      </div>
      <div className="flex flex-wrap items-end gap-2" aria-label="Filters" role="group">
        <PriceInput label="Min price" value={search.minPrice} onCommit={(v) => onSearch({ minPrice: v })} />
        <PriceInput label="Max price" value={search.maxPrice} onCommit={(v) => onSearch({ maxPrice: v })} />
        <Select<CapBucket> label="Market cap" value={search.cap} options={Object.entries(CAPS).map(([k, v]) => ({ value: k as CapBucket, label: v.label }))} onChange={(cap) => onSearch({ cap })} />
        <Select<ChangeBucket> label="Change" value={search.chg} options={Object.entries(CHANGES).map(([k, v]) => ({ value: k as ChangeBucket, label: v.label }))} onChange={(chg) => onSearch({ chg })} />
        <Select label="Rel. volume" value={search.rvol} options={REL_VOLUMES.map((v) => ({ value: v, label: `≥ ${v}×` }))} onChange={(rvol) => onSearch({ rvol })} />
        <SectorPicker value={search.sectors} onChange={(sectors) => onSearch({ sectors })} />
        <Select label="Type" value={search.type} options={[{ value: 'STOCKS' as const, label: 'Stocks' }, { value: 'ETF' as const, label: 'ETFs' }]} onChange={(type) => onSearch({ type })} />
        {filters > 0 ? (
          <Button size="sm" variant="ghost" onClick={() => onSearch({ minPrice: undefined, maxPrice: undefined, cap: undefined, chg: undefined, rvol: undefined, sectors: undefined, type: undefined })}>
            Clear filters
          </Button>
        ) : null}
      </div>
      {first?.warming ? (
        <p className="rounded-md bg-accent-bg px-3 py-2 text-caption text-text-1" role="status">
          Loading the market snapshot… ({first.coverage?.priced ?? 0} of {first.coverage?.universe ?? 0} symbols priced so far)
        </p>
      ) : null}
      {q.isPending ? (
        <SkeletonRows rows={10} cols={phone ? 3 : 7} />
      ) : q.isError ? (
        <EmptyState title="The screener is unavailable" body="Try again in a moment." />
      ) : rows.length === 0 ? (
        <EmptyState title="No stocks match these filters" body="Loosen a filter or pick another preset." />
      ) : phone ? (
        <ul className={cx('divide-y divide-divider transition-opacity', stale && 'opacity-60')} aria-label="Screener results" aria-busy={stale} data-testid="screener-results">
          {rows.map((r) => (
            <li key={r.symbol} className="flex items-center gap-2">
              <Link to="/stock/$symbol" params={{ symbol: toPath(r.symbol!) }} data-symbol={r.symbol} className="flex min-h-16 min-w-0 flex-1 items-center gap-3 py-2">
                <span className="min-w-0 flex-1">
                  <span className="block font-semibold">{r.symbol}</span>
                  <span className="block truncate text-caption text-text-3">{r.name ?? ''}</span>
                  {/* wraps instead of truncating, so the market cap is never cut off on narrow phones */}
                  <span className="block text-micro text-text-3">
                    {r.marketCap ? `Cap ${abbreviate(r.marketCap)} · ` : ''}Vol {vol(r)} · RV {relVol(r)}
                  </span>
                </span>
                <span className="text-body font-semibold tabular-nums">{formatPrice(r.price)}</span>
                <span className={cx('w-[76px] shrink-0 rounded-sm py-1 text-center text-caption font-semibold', PILL[pctDirection(r.changePct)])}>{formatPct(r.changePct)}</span>
              </Link>
              <StarButton symbol={r.symbol!} size="touch" />
            </li>
          ))}
        </ul>
      ) : (
        <div className={cx('overflow-x-auto transition-opacity', stale && 'opacity-60')}>
          <table className="w-full min-w-[720px] text-table tabular-nums" aria-label="Screener results" aria-busy={stale} data-testid="screener-results">
            <thead className="border-b border-divider text-micro text-text-3">
              <tr>
                <SortHeader column="SYMBOL" label="Symbol" sort={sort} order={order} onSort={onSort} className="text-left" />
                {COLUMNS.map((c) => (
                  <SortHeader key={c.sort} column={c.sort} label={c.label} title={c.title} sort={sort} order={order} onSort={onSort} className={c.className} />
                ))}
                <th scope="col" className="hidden py-1.5 pl-3 text-left font-medium uppercase desktop:table-cell">
                  Sector
                </th>
                <th scope="col" className="w-10">
                  <span className="sr-only">Watch</span>
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-divider">
              {rows.map((r) => (
                <tr key={r.symbol} className="hover:bg-bg-2">
                  <td className="py-1.5">
                    <Link to="/stock/$symbol" params={{ symbol: toPath(r.symbol!) }} aria-label={`${r.symbol} ${r.name ?? ''}`.trim()} data-symbol={r.symbol} className="block min-w-0">
                      <span className="font-semibold text-text-1">{r.symbol}</span>
                      <span className="block max-w-56 truncate text-caption text-text-3">{r.name ?? ''}</span>
                    </Link>
                  </td>
                  <td className="text-right font-semibold">{formatPrice(r.price)}</td>
                  <td className="text-right">
                    <span className={cx('inline-block w-[76px] rounded-sm py-0.5 text-center text-caption font-semibold', PILL[pctDirection(r.changePct)])}>{formatPct(r.changePct)}</span>
                  </td>
                  <td className="text-right">{vol(r)}</td>
                  <td className="text-right">{relVol(r)}</td>
                  <td className="text-right">{abbreviate(r.marketCap)}</td>
                  <td className="hidden max-w-40 truncate pl-3 text-caption text-text-2 desktop:table-cell">{r.sector ? SECTOR_LABEL[r.sector] : r.type === 'ETF' ? 'ETF' : '--'}</td>
                  <td>
                    <StarButton symbol={r.symbol!} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {q.hasNextPage ? (
        <div className="flex justify-center">
          <Button variant="secondary" size="sm" onClick={() => void q.fetchNextPage()} disabled={q.isFetchingNextPage}>
            {q.isFetchingNextPage ? 'Loading…' : 'Load more'}
          </Button>
        </div>
      ) : null}
      {first ? (
        <p className="text-micro text-text-3" data-testid="screener-footer">
          {rows.length} of {first.total} shown · as of {formatTimeEt(first.asOf)} · {first.coverage?.priced ?? 0} of {first.coverage?.universe ?? 0} symbols priced, {first.coverage?.withMarketCap ?? 0} with a market cap
          {partial ? ' · * IEX volume only (the free data plan); relative volume compares it with the IEX average' : ''}
          . The screener covers the league&apos;s universe (S&amp;P 500, Nasdaq-100 extras, popular stocks and ETFs).
        </p>
      ) : null}
    </div>
  )

  if (phone) {
    return (
      <div className="flex min-h-full flex-col">
        <PushedHeader title="Screener" subtitle={first ? `${first.total} matches` : undefined} />
        {body}
      </div>
    )
  }
  return <div className="scrollbar-thin h-full overflow-y-auto">{body}</div>
}
