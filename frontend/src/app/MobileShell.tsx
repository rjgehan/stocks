import { Outlet } from '@tanstack/react-router'
import { BottomTabBar } from './BottomTabBar'
import { PhoneTopBar } from './TopBar'
import { useRouteMeta } from './useRouteMeta'

/**
 * Phone shell (< 600, §22.3): root screens get the top bar and the bottom tab bar (56 px + safe area);
 * pushed screens (quote page, profiles, order detail…) render their own back header and no tab bar.
 */
export function MobileShell() {
  const meta = useRouteMeta()
  const root = meta.phone === 'root'
  return (
    <div className="flex h-dvh flex-col overflow-hidden bg-bg-0">
      {root ? <PhoneTopBar title={meta.title} /> : null}
      <main className="scrollbar-none min-h-0 flex-1 overflow-y-auto overscroll-contain pl-safe pr-safe">
        <Outlet />
      </main>
      {root ? <BottomTabBar /> : null}
    </div>
  )
}
