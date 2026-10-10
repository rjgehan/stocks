import { Link, createFileRoute, useNavigate } from '@tanstack/react-router'
import { useLogout, useMe } from '@/lib/api/hooks/identity'
import { IconChevronRight, IconLogout } from '@/components/ui/icons'

export const Route = createFileRoute('/_app/settings/')({
  component: SettingsMenu,
})

const ITEMS = [
  { to: '/settings/appearance', label: 'Appearance', detail: 'Theme and up/down colors' },
  { to: '/settings/trading', label: 'Trading defaults', detail: 'Order confirmation, default type, TIF, quantity mode' },
  { to: '/settings/api-keys', label: 'API keys', detail: 'Personal read-only keys for your own scripts' },
  { to: '/settings/connected-apps', label: 'Connected apps', detail: 'claude.ai and ChatGPT connectors you approved' },
  { to: '/settings/account', label: 'Account', detail: 'Password and self-reset' },
] as const

/** Settings / the phone "Menu" tab (§22.4). */
function SettingsMenu() {
  const { data: me } = useMe()
  const logout = useLogout()
  const navigate = useNavigate()
  return (
    <div className="space-y-4">
      <div className="hidden tablet:block">
        <h1 className="text-[20px] leading-7 font-bold tracking-tight">Settings</h1>
        {me ? <p className="text-caption text-text-2">Signed in as @{me.handle}</p> : null}
      </div>
      <ul className="divide-y divide-divider overflow-hidden rounded-lg border border-divider bg-bg-1">
        {ITEMS.map((i) => (
          <li key={i.to}>
            <Link to={i.to} className="flex min-h-14 items-center gap-3 px-4 py-2.5 hover:bg-bg-2">
              <div className="min-w-0 flex-1">
                <div className="font-semibold">{i.label}</div>
                <div className="truncate text-caption text-text-3">{i.detail}</div>
              </div>
              <IconChevronRight size={18} className="text-text-3" />
            </Link>
          </li>
        ))}
        {me?.role === 'ADMIN' ? (
          <li>
            <Link to="/admin/players" className="flex min-h-14 items-center gap-3 px-4 py-2.5 hover:bg-bg-2">
              <div className="min-w-0 flex-1">
                <div className="font-semibold">Admin console</div>
                <div className="truncate text-caption text-text-3">Players, money, invites, system, audit</div>
              </div>
              <IconChevronRight size={18} className="text-text-3" />
            </Link>
          </li>
        ) : null}
      </ul>
      <button
        type="button"
        onClick={() => logout.mutate(undefined, { onSettled: () => void navigate({ to: '/login' }) })}
        className="flex min-h-12 w-full items-center justify-center gap-2 rounded-lg border border-divider bg-bg-1 font-semibold text-down hover:bg-down-bg"
      >
        <IconLogout size={18} /> Sign out
      </button>
    </div>
  )
}
