/**
 * The desktop watchlist column (§22.10): resizable from its right edge (drag, or the arrow keys on the handle),
 * 260–720 px within what the viewport leaves for the center and the ticket; the width is remembered per device.
 */
import { useEffect, useRef, useState } from 'react'
import { readLocal, writeLocal } from '@/lib/prefs/local'
import { WIDTH_KEY, clampWidth, widthBounds } from './columns'

function useViewportWidth(): number {
  const [w, setW] = useState(() => (typeof window === 'undefined' ? 1440 : window.innerWidth))
  useEffect(() => {
    const on = () => setW(window.innerWidth)
    window.addEventListener('resize', on)
    return () => window.removeEventListener('resize', on)
  }, [])
  return w
}

export function ResizableAside({ wide, children }: { wide: boolean; children: React.ReactNode }) {
  const viewport = useViewportWidth()
  const bounds = widthBounds(viewport, wide)
  const [stored, setStored] = useState<number | null>(() => {
    const n = Number(readLocal(WIDTH_KEY, ''))
    return Number.isFinite(n) && n > 0 ? n : null
  })
  const width = clampWidth(stored ?? bounds.initial, bounds)
  const drag = useRef<{ x: number; width: number } | null>(null)
  const save = (w: number) => {
    const c = clampWidth(w, bounds)
    setStored(c)
    writeLocal(WIDTH_KEY, String(c))
  }
  return (
    <aside className="relative shrink-0 border-r border-divider" style={{ width }} data-testid="watchlist-aside">
      {children}
      <div
        role="separator"
        aria-orientation="vertical"
        aria-label="Resize watchlist"
        aria-valuenow={width}
        aria-valuemin={bounds.min}
        aria-valuemax={bounds.max}
        tabIndex={0}
        onKeyDown={(e) => {
          if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
            e.preventDefault()
            save(width + (e.key === 'ArrowRight' ? 20 : -20))
          }
        }}
        onPointerDown={(e) => {
          drag.current = { x: e.clientX, width }
          e.currentTarget.setPointerCapture(e.pointerId)
        }}
        onPointerMove={(e) => {
          if (drag.current) setStored(clampWidth(drag.current.width + e.clientX - drag.current.x, bounds))
        }}
        onPointerUp={(e) => {
          if (!drag.current) return
          save(drag.current.width + e.clientX - drag.current.x)
          drag.current = null
        }}
        className="absolute top-0 -right-1 z-10 h-full w-2 cursor-col-resize outline-none hover:bg-accent/30 focus-visible:bg-accent/40"
      />
    </aside>
  )
}
