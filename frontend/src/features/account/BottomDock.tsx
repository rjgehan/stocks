import { useCallback, useRef, useState } from 'react'
import { readLocal, writeLocal } from '@/lib/prefs/local'
import { Tabs } from '@/components/ui/Tabs'
import { EmptyState } from '@/components/ui/EmptyState'
import { OrdersTable } from './OrdersTable'
import { PositionsTable } from './PositionsTable'

type DockTab = 'POSITIONS' | 'ORDERS' | 'ACTIVITY'

const MIN = 120
const MAX = 520

/** Desktop bottom dock (§22.3): Positions · Orders · Activity, resizable by dragging its top edge. */
export function BottomDock() {
  const [tab, setTab] = useState<DockTab>('POSITIONS')
  const [height, setHeight] = useState(() => {
    const raw = readLocal('stocks.dock.height', '220')
    return /^\d+$/.test(raw) ? Math.min(MAX, Math.max(MIN, Number(raw))) : 220
  })
  const drag = useRef<{ startY: number; startH: number } | null>(null)

  const onPointerDown = useCallback(
    (e: React.PointerEvent<HTMLDivElement>) => {
      drag.current = { startY: e.clientY, startH: height }
      e.currentTarget.setPointerCapture(e.pointerId)
    },
    [height],
  )
  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!drag.current) return
    setHeight(Math.min(MAX, Math.max(MIN, drag.current.startH - (e.clientY - drag.current.startY))))
  }
  const onPointerUp = () => {
    if (drag.current) writeLocal('stocks.dock.height', String(height))
    drag.current = null
  }

  return (
    <section aria-label="Positions and orders" className="flex shrink-0 flex-col border-t border-divider bg-bg-1" style={{ height }}>
      <div
        role="separator"
        aria-orientation="horizontal"
        aria-label="Resize dock"
        tabIndex={0}
        onKeyDown={(e) => {
          if (e.key === 'ArrowUp') setHeight((h) => Math.min(MAX, h + 24))
          if (e.key === 'ArrowDown') setHeight((h) => Math.max(MIN, h - 24))
        }}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        className="group flex h-2 shrink-0 cursor-row-resize items-center justify-center"
      >
        <span className="h-0.5 w-10 rounded-full bg-bg-3 group-hover:bg-accent" />
      </div>
      <Tabs
        label="Dock"
        className="px-4"
        value={tab}
        onChange={setTab}
        items={[
          { value: 'POSITIONS', label: 'Positions' },
          { value: 'ORDERS', label: 'Orders' },
          { value: 'ACTIVITY', label: 'Activity' },
        ]}
      />
      <div className="scrollbar-thin min-h-0 flex-1 overflow-y-auto">
        {tab === 'POSITIONS' ? <PositionsTable compact /> : null}
        {tab === 'ORDERS' ? <OrdersTable status="ALL" /> : null}
        {tab === 'ACTIVITY' ? <EmptyState title="Activity arrives in Phase 1" body="Fills, deposits (with memos) and corporate actions, newest first." /> : null}
      </div>
    </section>
  )
}
