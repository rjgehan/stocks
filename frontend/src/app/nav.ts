import type { ComponentType } from 'react'
import { IconAccount, IconLeague, IconMarkets, IconMenu, IconSettings, IconShield, IconWatchlist } from '@/components/ui/icons'

export interface NavItem {
  to: '/watchlist' | '/markets' | '/league' | '/account' | '/settings' | '/admin'
  label: string
  icon: ComponentType<{ size?: number }>
  adminOnly?: boolean
}

/** Phone bottom tabs (§22.3): Watchlist · Markets · League · Account · Menu. */
export const TAB_ITEMS: readonly NavItem[] = [
  { to: '/watchlist', label: 'Watchlist', icon: IconWatchlist },
  { to: '/markets', label: 'Markets', icon: IconMarkets },
  { to: '/league', label: 'League', icon: IconLeague },
  { to: '/account', label: 'Account', icon: IconAccount },
  { to: '/settings', label: 'Menu', icon: IconMenu },
]

/** Desktop/tablet icon rail. */
export const RAIL_ITEMS: readonly NavItem[] = [
  { to: '/watchlist', label: 'Trade', icon: IconWatchlist },
  { to: '/markets', label: 'Markets', icon: IconMarkets },
  { to: '/league', label: 'League', icon: IconLeague },
  { to: '/account', label: 'Account', icon: IconAccount },
  { to: '/settings', label: 'Settings', icon: IconSettings },
  { to: '/admin', label: 'Admin', icon: IconShield, adminOnly: true },
]
