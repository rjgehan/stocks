import { Link, useRouterState } from '@tanstack/react-router'
import { useMe } from '@/lib/api/hooks/identity'
import { cx } from '@/components/ui/cx'
import { RAIL_ITEMS } from './nav'

/** 56 px icon rail (§22.3, desktop and tablet). "Trade" is active on /watchlist and /stock/*. */
export function LeftRail() {
  const { data: me } = useMe()
  const path = useRouterState({ select: (s) => s.location.pathname })
  return (
    <nav aria-label="Main" className="flex w-14 shrink-0 flex-col items-center gap-1 border-r border-divider bg-bg-1 py-2">
      {RAIL_ITEMS.filter((i) => !i.adminOnly || me?.role === 'ADMIN').map((item) => {
        const active = item.to === '/watchlist' ? path === '/watchlist' || path.startsWith('/stock/') : path.startsWith(item.to)
        const Icon = item.icon
        return (
          <Link
            key={item.to}
            to={item.to}
            aria-current={active ? 'page' : undefined}
            className={cx(
              'flex w-12 flex-col items-center gap-0.5 rounded-md py-1.5 text-[10px] font-semibold transition-colors',
              active ? 'bg-accent-bg text-accent' : 'text-text-3 hover:bg-bg-2 hover:text-text-1',
            )}
          >
            <Icon size={20} />
            {item.label}
          </Link>
        )
      })}
    </nav>
  )
}
