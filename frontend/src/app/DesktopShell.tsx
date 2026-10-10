import { useState } from 'react'
import { Outlet, useParams } from '@tanstack/react-router'
import { readLocal, writeLocal } from '@/lib/prefs/local'
import { IconButton } from '@/components/ui/Button'
import { IconChevronLeft, IconChevronRight } from '@/components/ui/icons'
import { Sheet } from '@/components/ui/Sheet'
import { WatchlistPanel } from '@/features/watchlist/WatchlistPanel'
import { DataSourceFooter } from './DataSourceFooter'
import { LeftRail } from './LeftRail'
import { TopBar } from './TopBar'
import type { Breakpoint } from './useBreakpoint'
import { useRouteMeta } from './useRouteMeta'

/**
 * Tablet and desktop shell (§22.3):
 *  - ≥ 1024: 56 px rail · watchlist panel 260 px (300 px ≥ 1440, collapsible) · center (the route).
 *    The stock route adds the ticket column and the bottom dock itself.
 *  - 600–1023: rail · content, with the watchlist as a collapsible left drawer.
 */
export function DesktopShell({ breakpoint }: { breakpoint: Breakpoint }) {
  const meta = useRouteMeta()
  const params = useParams({ strict: false }) as { symbol?: string }
  const activeSymbol = params.symbol?.toUpperCase()
  const tablet = breakpoint === 'tablet'
  const [collapsed, setCollapsed] = useState(() => readLocal('stocks.watchlist.collapsed', '0') === '1')
  const [drawer, setDrawer] = useState(false)
  const showPanel = !tablet && meta.chrome === 'watchlist'

  const toggleCollapsed = () => {
    const next = !collapsed
    setCollapsed(next)
    writeLocal('stocks.watchlist.collapsed', next ? '1' : '0')
  }

  return (
    <div className="flex h-dvh flex-col overflow-hidden pl-safe pr-safe">
      <TopBar showWatchlistToggle={tablet} onToggleWatchlist={() => setDrawer(true)} />
      <div className="flex min-h-0 flex-1">
        <LeftRail />
        {showPanel ? (
          collapsed ? (
            <div className="flex w-9 shrink-0 flex-col items-center border-r border-divider bg-bg-1 pt-1.5">
              <IconButton label="Show watchlist" size="sm" onClick={toggleCollapsed}>
                <IconChevronRight size={16} />
              </IconButton>
            </div>
          ) : (
            <aside className="relative w-[260px] shrink-0 border-r border-divider wide:w-[300px]">
              <WatchlistPanel activeSymbol={activeSymbol} />
              <div className="absolute top-1.5 right-1.5">
                <IconButton label="Collapse watchlist" size="sm" onClick={toggleCollapsed}>
                  <IconChevronLeft size={16} />
                </IconButton>
              </div>
            </aside>
          )
        ) : null}
        <main className="min-w-0 flex-1 overflow-hidden bg-bg-0">
          <Outlet />
        </main>
      </div>
      <DataSourceFooter />
      {tablet ? (
        <Sheet open={drawer} onClose={() => setDrawer(false)} title="Watchlist" side="left">
          <div className="h-full" onClickCapture={(e) => (e.target as HTMLElement).closest('a') && setDrawer(false)}>
            <WatchlistPanel activeSymbol={activeSymbol} variant="screen" />
          </div>
        </Sheet>
      ) : null}
    </div>
  )
}
