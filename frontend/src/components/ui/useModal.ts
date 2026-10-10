import { createContext, type RefObject, useContext, useEffect, useLayoutEffect, useRef } from 'react'

/**
 * How many modals enclose this part of the React tree. A modal rendered inside another one (the review Dialog
 * inside the trade Sheet's ticket) is above it even though React runs the child's effects first.
 */
export const ModalDepthContext = createContext(0)

interface OpenModal {
  panel: RefObject<HTMLElement | null>
  depth: number
  seq: number
}

/** Open modals. Only the topmost (deepest, then most recently opened) reacts to Escape and traps Tab. */
const openModals: OpenModal[] = []
let nextSeq = 0

function topmost(): OpenModal | undefined {
  let top: OpenModal | undefined
  for (const m of openModals) {
    if (!top || m.depth > top.depth || (m.depth === top.depth && m.seq > top.seq)) top = m
  }
  return top
}

const FOCUSABLE =
  'a[href], area[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), ' +
  'textarea:not([disabled]), iframe, [contenteditable="true"], [tabindex]:not([tabindex="-1"])'

function focusables(panel: HTMLElement): HTMLElement[] {
  return Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((el) => !el.hasAttribute('inert') && el.getAttribute('aria-hidden') !== 'true')
}

/**
 * Modal behavior shared by Sheet and Dialog (§22.3/§22.5):
 * - on open: focus moves into the panel (once: re-renders of the parent, e.g. quote ticks that hand in a new
 *   `onClose`, never move it again); on close: focus returns to what had it before;
 * - Escape closes only the topmost modal, so a review dialog over the trade sheet closes alone;
 * - Tab / Shift+Tab wrap inside the topmost panel (focus trap);
 * - optional page scroll lock.
 * `onClose` is read through a ref, so the effects depend on `isOpen` alone. Returns the depth the modal's children
 * must see: wrap them in `<ModalDepthContext.Provider value={childDepth}>`.
 */
export function useModal(isOpen: boolean, onClose: () => void, panel: RefObject<HTMLElement | null>, options: { lockScroll?: boolean } = {}): number {
  const depth = useContext(ModalDepthContext)
  const onCloseRef = useRef(onClose)
  useLayoutEffect(() => {
    onCloseRef.current = onClose
  })
  const lockScroll = options.lockScroll ?? false

  useEffect(() => {
    if (!isOpen) return
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const self: OpenModal = { panel, depth, seq: nextSeq++ }
    openModals.push(self)
    if (topmost() === self) panel.current?.focus()

    const onKey = (e: KeyboardEvent) => {
      if (topmost() !== self) return
      const el = panel.current
      if (e.key === 'Escape') {
        e.stopPropagation()
        onCloseRef.current()
        return
      }
      if (e.key !== 'Tab' || !el) return
      const items = focusables(el)
      if (items.length === 0) {
        e.preventDefault()
        el.focus()
        return
      }
      const first = items[0]!
      const last = items[items.length - 1]!
      const active = document.activeElement
      const inside = active instanceof Node && el.contains(active)
      if (e.shiftKey && (!inside || active === first || active === el)) {
        e.preventDefault()
        last.focus()
      } else if (!e.shiftKey && (!inside || active === last)) {
        e.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKey)

    const overflow = document.body.style.overflow
    if (lockScroll) document.body.style.overflow = 'hidden'

    return () => {
      document.removeEventListener('keydown', onKey)
      const at = openModals.indexOf(self)
      if (at >= 0) openModals.splice(at, 1)
      if (lockScroll) document.body.style.overflow = overflow
      if (previous?.isConnected) previous.focus()
    }
  }, [isOpen, panel, depth, lockScroll])

  return depth + 1
}
