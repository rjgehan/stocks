import { useCallback, useRef, useState } from 'react'
import { useOrders, usePositions } from '@/lib/api/hooks/account'
import { readLocal, writeLocal } from '@/lib/prefs/local'
import { Tabs } from '@/components/ui/Tabs'
import { ActivityList } from './ActivityList'
import { OrdersTable } from './OrdersTable'
import { PositionsTable } from './PositionsTable'

type DockTab = 'POSITIONS' | 'ORDERS' | 'ACTIVITY'

const MIN = 120
const MAX = 520

/** Desktop bottom dock (§22.3): Positions · Orders · Activity, resizable by dragging its top edge. */
export function BottomDock() {
  const [tab, setTab] = useState<DockTab>(() => {
    const t = readLocal('stocks.dock.tab', 'POSITIONS')
    return t === 'ORDERS' || t === 'ACTIVITY' ? t : 'POSITIONS'
  })
  const [height, setHeight] = useState(() => {
    const raw = readLocal('stocks.dock.height', '220')
    return /^\d+$/.test(raw) ? Math.min(MAX, Math.max(MIN, Number(raw))) : 220
  })
  const drag = useRef<{ startY: number; startH: number } | null>(null)
  const positions = usePositions()
  const open = useOrders('OPEN')
  const posCount = positions.data?.length ?? 0
  const openCount = open.data?.items?.length ?? 0

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
  const select = (t: DockTab) => {
    setTab(t)
    writeLocal('stocks.dock.tab', t)
  }

  return (
    <section aria-label="Positions and orders" className="flex shrink-0 flex-col border-t border-divider bg-bg-1" style={{ height }}>
      <div
        role="separator"
        aria-orientation="horizontal"
        aria-label="Resize dock"
        aria-valuemin={MIN}
        aria-valuemax={MAX}
        aria-valuenow={height}
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
        onChange={select}
        items={[
          { value: 'POSITIONS', label: posCount ? `Positions (${posCount})` : 'Positions' },
          { value: 'ORDERS', label: openCount ? `Orders (${openCount} open)` : 'Orders' },
          { value: 'ACTIVITY', label: 'Activity' },
        ]}
      />
      <div className="scrollbar-thin min-h-0 flex-1 overflow-y-auto">
        {tab === 'POSITIONS' ? <PositionsTable compact /> : null}
        {tab === 'ORDERS' ? <OrdersTable view="ALL" limit={50} /> : null}
        {tab === 'ACTIVITY' ? <ActivityList pageSize={20} compact /> : null}
      </div>
    </section>
  )
}
