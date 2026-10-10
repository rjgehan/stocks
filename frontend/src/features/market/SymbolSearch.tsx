import { useEffect, useId, useRef, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useAssetSearch } from '@/lib/api/hooks/market'
import { IconSearch } from '@/components/ui/icons'
import { Kbd } from '@/components/ui/Kbd'
import { cx } from '@/components/ui/cx'

/**
 * Symbol search (§22.1): the TopBar field on desktop with a ⌘K / Ctrl+K shortcut; the /search route uses the
 * same component full-screen on phones. Results come from GET /assets/search. WAI-ARIA combobox pattern:
 * the input owns the keyboard (↑ ↓ Enter Esc) and points at the active option with aria-activedescendant.
 */
export function SymbolSearch({ focusOnMount, variant = 'bar', onDone }: { focusOnMount?: boolean; variant?: 'bar' | 'page'; onDone?: () => void }) {
  const [q, setQ] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const input = useRef<HTMLInputElement>(null)
  const listId = useId()
  const navigate = useNavigate()
  const { data } = useAssetSearch(q)
  const results = data ?? []

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
    setOpen(false)
    input.current?.blur()
    onDone?.()
    void navigate({ to: '/stock/$symbol', params: { symbol } })
  }

  const showList = (variant === 'page' || open) && q.trim().length > 0
  const optionId = (i: number) => `${listId}-opt-${i}`
  return (
    <div className={cx('relative', variant === 'bar' ? 'w-full max-w-[360px]' : 'w-full')}>
      <div className={cx('flex items-center gap-2 rounded-md border border-border bg-bg-2 px-3 focus-within:border-accent', variant === 'bar' ? 'h-9' : 'h-11')}>
        <IconSearch size={16} className="shrink-0 text-text-3" />
        <input
          ref={input}
          value={q}
          onChange={(e) => {
            setQ(e.target.value)
            setActive(0)
            setOpen(true)
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => setTimeout(() => setOpen(false), 120)}
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown') {
              e.preventDefault()
              setActive((a) => Math.min(a + 1, Math.max(results.length - 1, 0)))
            } else if (e.key === 'ArrowUp') {
              e.preventDefault()
              setActive((a) => Math.max(a - 1, 0))
            } else if (e.key === 'Enter' && results[active]) {
              go(results[active].symbol)
            } else if (e.key === 'Escape') {
              input.current?.blur()
            }
          }}
          placeholder="Search symbols or companies"
          aria-label="Search symbols"
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={showList}
          aria-controls={listId}
          aria-activedescendant={showList && results[active] ? optionId(active) : undefined}
          autoComplete="off"
          spellCheck={false}
          className="min-w-0 flex-1 bg-transparent text-body text-text-1 outline-none placeholder:text-text-3"
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
          variant === 'bar' ? 'absolute inset-x-0 top-11' : 'mt-2',
        )}
      >
        {showList && results.length === 0 ? <div className="px-3 py-2 text-caption text-text-3">No matches</div> : null}
        {showList
          ? results.map((r, i) => (
              <div
                key={r.symbol}
                id={optionId(i)}
                role="option"
                aria-selected={i === active}
                tabIndex={-1}
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => go(r.symbol)}
                onKeyDown={(e) => e.key === 'Enter' && go(r.symbol)}
                onMouseEnter={() => setActive(i)}
                className={cx('flex w-full cursor-pointer items-center gap-3 px-3 py-2 text-left', i === active ? 'bg-bg-2' : 'hover:bg-bg-2')}
              >
                <span className="w-14 shrink-0 text-body font-semibold">{r.symbol}</span>
                <span className="truncate text-caption text-text-2">{r.name}</span>
                <span className="ml-auto text-micro text-text-3">{r.type === 'ETF' ? 'ETF' : r.exchange}</span>
              </div>
            ))
          : null}
      </div>
    </div>
  )
}
