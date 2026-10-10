import { Link, useNavigate } from '@tanstack/react-router'
import { useLogout, useMe } from '@/lib/api/hooks/identity'
import { IconButton } from '@/components/ui/Button'
import { IconLogout, IconPanelLeft, IconSearch, LogoMark } from '@/components/ui/icons'
import { NotificationBell } from '@/features/notifications/NotificationBell'
import { SymbolSearch } from '@/features/market/SymbolSearch'
import { PaperBadge, SimBadge } from './Badges'
import { ConnectionIndicator } from './ConnectionIndicator'
import { NavHero } from './NavHero'
import { SessionChip } from './SessionChip'
import { ThemeToggle } from './ThemeToggle'

function Brand() {
  return (
    <Link to="/" className="flex shrink-0 items-center gap-2 rounded-md pr-1" aria-label="stocks home">
      <LogoMark size={26} />
      <span className="text-[17px] font-bold tracking-tight text-text-1">stocks</span>
    </Link>
  )
}

/** Desktop and tablet top bar (§22.3): search, session chip, PAPER and SIM badges, NAV hero, bell. */
export function TopBar({ onToggleWatchlist, showWatchlistToggle }: { onToggleWatchlist?: () => void; showWatchlistToggle?: boolean }) {
  const { data: me } = useMe()
  const logout = useLogout()
  const navigate = useNavigate()
  return (
    <header className="flex h-14 shrink-0 items-center gap-3 border-b border-divider bg-bg-1 pr-3 pl-2 pt-safe">
      {showWatchlistToggle ? (
        <IconButton label="Watchlist" onClick={onToggleWatchlist}>
          <IconPanelLeft />
        </IconButton>
      ) : null}
      <Brand />
      <div className="ml-2 hidden min-w-0 flex-1 desktop:block">
        <SymbolSearch />
      </div>
      <div className="flex-1 desktop:hidden" />
      <IconButton label="Search" className="desktop:hidden" onClick={() => void navigate({ to: '/search' })}>
        <IconSearch />
      </IconButton>
      <ConnectionIndicator />
      <SessionChip detailClassName="hidden desktop:inline" />
      <div className="flex items-center gap-1">
        <PaperBadge />
        <SimBadge />
      </div>
      <NavHero />
      <div className="flex items-center">
        <ThemeToggle />
        <NotificationBell />
        {me ? (
          <IconButton
            label={`Sign out ${me.handle ?? ''}`.trim()}
            onClick={() => logout.mutate(undefined, { onSettled: () => void navigate({ to: '/login' }) })}
          >
            <IconLogout />
          </IconButton>
        ) : null}
      </div>
    </header>
  )
}

/** Phone top bar for root screens: brand or title, session chip, badges, bell. */
export function PhoneTopBar({ title }: { title?: string }) {
  const navigate = useNavigate()
  return (
    <header className="shrink-0 border-b border-divider bg-bg-1 pt-safe pl-safe pr-safe">
      <div className="flex h-12 items-center gap-1.5 px-3">
        {title ? <h1 className="text-[20px] leading-7 font-bold tracking-tight">{title}</h1> : <Brand />}
        <div className="flex-1" />
        <PaperBadge />
        <SimBadge />
        <IconButton label="Search" size="touch" onClick={() => void navigate({ to: '/search' })}>
          <IconSearch />
        </IconButton>
        <NotificationBell size="touch" />
      </div>
      <div className="flex items-center justify-between gap-2 px-3 pb-2">
        <div className="flex min-w-0 items-center gap-1.5">
          <SessionChip />
          <ConnectionIndicator />
        </div>
        <NavHero />
      </div>
    </header>
  )
}
