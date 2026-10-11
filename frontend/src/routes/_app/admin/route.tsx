import { Outlet, createFileRoute } from '@tanstack/react-router'
import { requireAdmin } from '@/app/RequireAuth'
import { PushedHeader } from '@/app/PushedHeader'
import { useBreakpoint } from '@/app/useBreakpoint'
import { Pill } from '@/components/ui/Pill'
import { LinkTabs } from '@/components/ui/Tabs'

export const Route = createFileRoute('/_app/admin')({
  // ADMIN only (§21.3). The server enforces it too: session-only, ADMIN role, CSRF (§17.1).
  beforeLoad: ({ context, location }) => requireAdmin(context.queryClient, location.href),
  staticData: { chrome: 'full', phone: 'pushed' },
  component: AdminLayout,
})

function AdminLayout() {
  const bp = useBreakpoint()
  return (
    <div className="scrollbar-thin h-full overflow-y-auto">
      {bp === 'phone' ? <PushedHeader title="Admin" /> : null}
      <div className="mx-auto w-full max-w-7xl space-y-4 px-4 py-4 desktop:px-6">
        <div className="hidden items-center gap-2 tablet:flex">
          <h1 className="text-[20px] leading-7 font-bold tracking-tight">Admin console</h1>
          <Pill tone="warn">Admin</Pill>
        </div>
        <LinkTabs
          label="Admin sections"
          items={[
            { to: '/admin/players', label: 'Players' },
            { to: '/admin/money', label: 'Money' },
            { to: '/admin/invites', label: 'Invites' },
            { to: '/admin/settings', label: 'Settings' },
            { to: '/admin/system', label: 'System' },
            { to: '/admin/corporate-actions', label: 'Corp. actions' },
            { to: '/admin/bots', label: 'Bots' },
            { to: '/admin/audit', label: 'Audit' },
          ]}
        />
        <Outlet />
      </div>
    </div>
  )
}
