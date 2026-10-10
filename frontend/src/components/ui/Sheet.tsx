import { useEffect, useId, useRef } from 'react'
import { createPortal } from 'react-dom'
import { IconClose } from './icons'
import { cx } from './cx'

export interface SheetProps {
  open: boolean
  onClose: () => void
  title: React.ReactNode
  /** bottom: phone ticket (92% height, §22.3); right: tablet side sheet (400 px); left: watchlist drawer. */
  side?: 'bottom' | 'right' | 'left'
  children: React.ReactNode
  footer?: React.ReactNode
  className?: string
}

/** Modal sheet with backdrop, Escape to close, focus moved in and restored on close. 200 ms ease-out. */
export function Sheet({ open, onClose, title, side = 'bottom', children, footer, className }: SheetProps) {
  const titleId = useId()
  const panel = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const previous = document.activeElement as HTMLElement | null
    panel.current?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = overflow
      previous?.focus?.()
    }
  }, [open, onClose])

  if (!open) return null
  return createPortal(
    <div className="fixed inset-0 z-50">
      <div className="absolute inset-0 animate-fade-in bg-black/50" onClick={onClose} aria-hidden="true" />
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={cx(
          'absolute flex flex-col bg-bg-1 shadow-2xl outline-none',
          side === 'bottom' && 'inset-x-0 bottom-0 h-[92dvh] animate-sheet-up rounded-t-lg pb-safe',
          side === 'right' && 'inset-y-0 right-0 w-[400px] max-w-full animate-sheet-left border-l border-border pt-safe pb-safe',
          side === 'left' && 'inset-y-0 left-0 w-[300px] max-w-[85vw] border-r border-border pt-safe pb-safe',
          className,
        )}
      >
        {side === 'bottom' ? <div className="mx-auto mt-2 h-1 w-10 shrink-0 rounded-full bg-bg-3" aria-hidden="true" /> : null}
        <div className="flex h-12 shrink-0 items-center justify-between border-b border-divider px-4">
          <h2 id={titleId} className="text-section font-semibold">
            {title}
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="-mr-2 inline-flex size-11 items-center justify-center rounded-md text-text-2 hover:bg-bg-2 hover:text-text-1"
          >
            <IconClose />
          </button>
        </div>
        <div className="scrollbar-thin min-h-0 flex-1 overflow-y-auto">{children}</div>
        {footer ? <div className="shrink-0 border-t border-divider p-4">{footer}</div> : null}
      </div>
    </div>,
    document.body,
  )
}
