import { useEffect, useId, useRef, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useAssetSearch } from '@/lib/api/hooks/market'
import { useDebounced } from '@/lib/useDebounced'
import { readLocal, writeLocal } from '@/lib/prefs/local'
import { IconSearch } from '@/components/ui/icons'
import { Kbd } from '@/components/ui/Kbd'
import { cx } from '@/components/ui/cx'

const RECENT_KEY = 'stocks.search.recent'

function readRecent(): string[] {
  return readLocal(RECENT_KEY, '')
    .split(',')
    .filter((s) => /^[A-Z0-9.]{1,10}$/.test(s))
    .slice(0, 6)
}

function pushRecent(symbol: string) {
  writeLocal(RECENT_KEY, [symbol, ...readRecent().filter((s) => s !== symbol)].slice(0, 6).join(','))
}

/**
 * Symbol search (§22.1): the TopBar field on desktop with a ⌘K / Ctrl+K shortcut; the /search route uses the
 * same component full-screen on phones; the watchlist uses it to add symbols (`onSelect`). Results come from
 * GET /assets/search (debounced 150 ms). WAI-ARIA combobox: the input owns the keyboard (↑ ↓ Enter Esc) and points at
 * the active option with aria-activedescendant.
 */
export function SymbolSearch({
  focusOnMount,
  variant = 'bar',
  onDone,
  onSelect,
  initialQuery = '',
  onQueryChange,
  placeholder = 'Search symbols or companies',
  exclude,
}: {
  focusOnMount?: boolean
  variant?: 'bar' | 'page' | 'inline'
  onDone?: () => void
  /** Pick instead of navigating to the quote page (watchlist "add symbol"). */
  onSelect?: (symbol: string) => void
  /** Starting text, e.g. the `/search?q=` deep link (§22.2). */
  initialQuery?: string
  /** Called as the user types (the /search page mirrors it into `?q=`). */
  onQueryChange?: (q: string) => void
  placeholder?: string
  /** Symbols to mark as already added. */
  exclude?: readonly string[]
}) {
  const [q, setQ] = useState(initialQuery)
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const [recent, setRecent] = useState(readRecent)
  const input = useRef<HTMLInputElement>(null)
  const listId = useId()
  const navigate = useNavigate()
  const query = useDebounced(q, 150)
  const { data, isFetching } = useAssetSearch(query)
  const results = q.trim() ? (data ?? []).filter((r): r is typeof r & { symbol: string } => Boolean(r.symbol)) : []

  useEffect(() => {
    if (focusOnMount) input.current?.focus()
  }, [focusOnMount])

  useEffect(() => {
    if (variant !== 'bar') return
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        input.current?.focus()
        setOpen(true)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [variant])

  const go = (symbol: string) => {
    setQ('')
    onQueryChange?.('')
    setOpen(false)
    if (onSelect) {
      onSelect(symbol)
      input.current?.focus()
      return
    }
    pushRecent(symbol)
    setRecent(readRecent())
    input.current?.blur()
    onDone?.()
    void navigate({ to: '/stock/$symbol', params: { symbol } })
  }

  const showRecent = variant === 'page' && !onSelect && q.trim().length === 0 && recent.length > 0
  const showList = (variant === 'page' || open) && q.trim().length > 0
  const optionId = (i: number) => `${listId}-opt-${i}`
  return (
    <div className={cx('relative', variant === 'bar' ? 'w-full max-w-[380px]' : 'w-full')}>
      <div className={cx('flex items-center gap-2 rounded-md border border-border bg-bg-2 px-3 focus-within:border-accent', variant === 'bar' ? 'h-9' : variant === 'inline' ? 'h-11 tablet:h-10' : 'h-11')}>
        <IconSearch size={16} className="shrink-0 text-text-3" />
        <input
          ref={input}
          value={q}
          onChange={(e) => {
            setQ(e.target.value)
            setActive(0)
            setOpen(true)
            onQueryChange?.(e.target.value)
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => setTimeout(() => setOpen(false), 150)}
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown') {
              e.preventDefault()
              setActive((a) => Math.min(a + 1, Math.max(results.length - 1, 0)))
            } else if (e.key === 'ArrowUp') {
              e.preventDefault()
              setActive((a) => Math.max(a - 1, 0))
            } else if (e.key === 'Enter') {
              e.preventDefault()
              const pick = results[active] ?? results[0]
              if (pick) go(pick.symbol)
            } else if (e.key === 'Escape') {
              if (q) {
                setQ('')
                onQueryChange?.('')
              } else input.current?.blur()
            }
          }}
          placeholder={placeholder}
          aria-label="Search symbols"
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={showList}
          aria-controls={listId}
          aria-activedescendant={showList && results[active] ? optionId(active) : undefined}
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          enterKeyHint="search"
          className="h-full min-w-0 flex-1 self-stretch bg-transparent text-body text-text-1 outline-none placeholder:text-text-3"
        />
        {variant === 'bar' ? <Kbd>⌘K</Kbd> : null}
      </div>
      <div
        id={listId}
        role="listbox"
        aria-label="Search results"
        hidden={!showList}
        className={cx(
          'z-40 overflow-hidden rounded-md border border-border bg-bg-1 py-1 shadow-xl',
          variant === 'page' ? 'mt-2' : 'absolute inset-x-0 top-[calc(100%+4px)]',
        )}
      >
        {showList && results.length === 0 ? (
          <div className="px-3 py-2 text-caption text-text-3">{isFetching || query !== q ? 'Searching…' : `No symbols match “${q.trim()}”`}</div>
        ) : null}
        {showList
          ? results.map((r, i) => {
              const added = exclude?.includes(r.symbol)
              return (
                <div
                  key={r.symbol}
                  id={optionId(i)}
                  role="option"
                  aria-selected={i === active}
                  aria-disabled={added || undefined}
                  tabIndex={-1}
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => !added && go(r.symbol)}
                  onKeyDown={(e) => e.key === 'Enter' && !added && go(r.symbol)}
                  onMouseEnter={() => setActive(i)}
                  className={cx('flex w-full cursor-pointer items-center gap-3 px-3 py-2 text-left', i === active ? 'bg-bg-2' : 'hover:bg-bg-2', added && 'cursor-default opacity-50')}
                >
                  <span className="w-16 shrink-0 text-body font-semibold">{r.symbol}</span>
                  <span className="min-w-0 flex-1 truncate text-caption text-text-2">{r.name}</span>
                  <span className="ml-auto shrink-0 text-micro text-text-3">{added ? 'Added' : r.type === 'ETF' ? 'ETF' : r.exchange}</span>
                </div>
              )
            })
          : null}
      </div>
      {showRecent ? (
        <div className="mt-4">
          <h2 className="mb-1 text-micro font-semibold tracking-wide text-text-3 uppercase">Recent</h2>
          <div className="flex flex-wrap gap-2">
            {recent.map((s) => (
              <button key={s} type="button" onClick={() => go(s)} className="h-11 rounded-full border border-border bg-bg-1 px-3 text-caption font-semibold hover:border-accent tablet:h-9">
                {s}
              </button>
            ))}
          </div>
        </div>
      ) : null}
    </div>
  )
}
