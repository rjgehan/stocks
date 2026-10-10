import { type ReactNode, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { cx } from './cx'

/**
 * A small anchored popover (menus, formula info). Click outside or Escape closes it; focus returns to the trigger.
 * `renderTrigger` receives the toggle props to spread on its button. The panel is kept inside the viewport (8 px
 * margin): one that would open past an edge is shifted back horizontally.
 */
export function Popover({
  renderTrigger,
  children,
  align = 'end',
  className,
  label,
}: {
  renderTrigger: (props: { onClick: () => void; 'aria-expanded': boolean; 'aria-controls': string; 'aria-haspopup': 'true' }) => ReactNode
  children: ReactNode | ((close: () => void) => ReactNode)
  align?: 'start' | 'end'
  className?: string
  label: string
}) {
  const [open, setOpen] = useState(false)
  const root = useRef<HTMLDivElement>(null)
  const panel = useRef<HTMLDivElement>(null)
  const id = useId()
  // collision handling: measured once per opening, before paint (a style on the node, no re-render)
  useLayoutEffect(() => {
    const el = panel.current
    if (!open || !el) return
    el.style.transform = ''
    const r = el.getBoundingClientRect()
    const margin = 8
    const right = document.documentElement.clientWidth - margin
    let dx = r.right > right ? right - r.right : 0
    if (r.left + dx < margin) dx = margin - r.left
    if (dx) el.style.transform = `translateX(${Math.round(dx)}px)`
  }, [open])
  useEffect(() => {
    if (!open) return
    const onDown = (e: PointerEvent) => {
      if (root.current && !root.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        setOpen(false)
        root.current?.querySelector<HTMLElement>('[aria-haspopup]')?.focus()
      }
    }
    document.addEventListener('pointerdown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('pointerdown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])
  const close = () => setOpen(false)
  return (
    <div ref={root} className="relative inline-flex">
      {renderTrigger({ onClick: () => setOpen((o) => !o), 'aria-expanded': open, 'aria-controls': id, 'aria-haspopup': 'true' })}
      {open ? (
        <div
          ref={panel}
          id={id}
          role="dialog"
          aria-label={label}
          className={cx(
            'absolute top-full z-40 mt-1 min-w-48 animate-fade-in rounded-md border border-border bg-bg-1 py-1 shadow-xl',
            align === 'end' ? 'right-0' : 'left-0',
            className,
          )}
        >
          {typeof children === 'function' ? children(close) : children}
        </div>
      ) : null}
    </div>
  )
}

export function MenuItem({ onClick, children, tone, disabled }: { onClick: () => void; children: ReactNode; tone?: 'danger'; disabled?: boolean }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={cx(
        'flex min-h-11 w-full items-center tablet:min-h-10 gap-2 px-3 text-left text-body hover:bg-bg-2 disabled:opacity-40',
        tone === 'danger' ? 'text-down' : 'text-text-1',
      )}
    >
      {children}
    </button>
  )
}
