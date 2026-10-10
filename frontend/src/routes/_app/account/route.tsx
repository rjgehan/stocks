import { Outlet, createFileRoute } from '@tanstack/react-router'
import { NavHero } from '@/app/NavHero'
import { useBreakpoint } from '@/app/useBreakpoint'
import { useRouteMeta } from '@/app/useRouteMeta'
import { LinkTabs } from '@/components/ui/Tabs'

export const Route = createFileRoute('/_app/account')({
  staticData: { chrome: 'watchlist', phone: 'root', title: 'Account' },
  component: AccountLayout,
})

/** Account (§22.4): NAV hero with day and total P&L, then Overview · Positions · Orders · Activity · History · Analysis (Phase 2). */
function AccountLayout() {
  const bp = useBreakpoint()
  const meta = useRouteMeta()
  // Phone pushed screens (order detail) bring their own back header instead of the account chrome.
  if (bp === 'phone' && meta.phone === 'pushed') {
    return <Outlet />

  }
  return (
    <div className="scrollbar-thin h-full overflow-y-auto">
      <div className="mx-auto w-full max-w-6xl space-y-4 px-4 py-4 desktop:px-6">
        <NavHero size="hero" />
        <LinkTabs
          label="Account sections"
          items={[
            { to: '/account', label: 'Overview', exact: true },
            { to: '/account/positions', label: 'Positions' },
            { to: '/account/orders', label: 'Orders' },
            { to: '/account/activity', label: 'Activity' },
            { to: '/account/history', label: 'History' },
            { to: '/account/analysis', label: 'Analysis' },
          ]}
        />
        <Outlet />
      </div>
    </div>
  )
}
