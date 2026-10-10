import { Link, useRouterState } from '@tanstack/react-router'
import { cx } from '@/components/ui/cx'
import { TAB_ITEMS } from './nav'

/** Phone tab bar (§22.3): 56 px plus the safe-area inset, root screens only, ≥ 44 px targets. */
export function BottomTabBar() {
  const path = useRouterState({ select: (s) => s.location.pathname })
  return (
    <nav aria-label="Main" className="h-tabbar shrink-0 border-t border-divider bg-bg-1/95 pb-safe pl-safe pr-safe backdrop-blur">
      <ul className="flex h-14">
        {TAB_ITEMS.map((item) => {
          const active = path.startsWith(item.to)
          const Icon = item.icon
          return (
            <li key={item.to} className="flex-1">
              <Link
                to={item.to}
                aria-current={active ? 'page' : undefined}
                className={cx('flex h-full flex-col items-center justify-center gap-0.5 text-[10px] font-semibold', active ? 'text-accent' : 'text-text-3')}
              >
                <Icon size={22} />
                {item.label}
              </Link>
            </li>
          )
        })}
      </ul>
    </nav>
  )
}
