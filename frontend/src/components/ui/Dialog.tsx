import { useId, useRef } from 'react'
import { createPortal } from 'react-dom'
import { IconClose } from './icons'
import { ModalDepthContext, useModal } from './useModal'

/**
 * Centered modal dialog (review sheet on desktop, confirmations). Focus moves in once on open, is trapped inside,
 * and returns on close; Escape closes only this dialog even when it sits on top of a sheet (`useModal`).
 */
export function Dialog({ open, onClose, title, children, footer }: { open: boolean; onClose: () => void; title: React.ReactNode; children: React.ReactNode; footer?: React.ReactNode }) {
  const titleId = useId()
  const panel = useRef<HTMLDivElement>(null)
  const childDepth = useModal(open, onClose, panel)
  if (!open) return null
  return createPortal(
    <div className="fixed inset-0 z-50 flex items-end justify-center p-0 tablet:items-center tablet:p-6">
      <div className="absolute inset-0 animate-fade-in bg-black/50" onClick={onClose} aria-hidden="true" />
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className="relative flex max-h-[92dvh] w-full animate-sheet-up flex-col rounded-t-lg bg-bg-1 pb-safe shadow-2xl outline-none tablet:max-w-[420px] tablet:animate-fade-in tablet:rounded-lg tablet:pb-0"
      >
        <div className="flex h-12 shrink-0 items-center justify-between border-b border-divider px-4">
          <h2 id={titleId} className="text-section font-semibold">
            {title}
          </h2>
          <button type="button" onClick={onClose} aria-label="Close" className="-mr-2 inline-flex size-10 items-center justify-center rounded-md text-text-2 hover:bg-bg-2">
            <IconClose />
          </button>
        </div>
        <ModalDepthContext.Provider value={childDepth}>
          <div className="scrollbar-thin min-h-0 flex-1 overflow-y-auto">{children}</div>
          {footer ? <div className="shrink-0 border-t border-divider p-4">{footer}</div> : null}
        </ModalDepthContext.Provider>
      </div>
    </div>,
    document.body,
  )
}
