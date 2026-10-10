import { useMatches } from '@tanstack/react-router'
import type { RouteMeta } from './routeMeta'

/** The deepest route's layout metadata, with inherited defaults. */
export function useRouteMeta(): Required<Pick<RouteMeta, 'chrome' | 'phone'>> & Pick<RouteMeta, 'title'> {
  const matches = useMatches()
  let chrome: RouteMeta['chrome'] = 'watchlist'
  let phone: RouteMeta['phone'] = 'root'
  let title: string | undefined
  for (const m of matches) {
    const d = m.staticData as RouteMeta | undefined
    if (d?.chrome) chrome = d.chrome
    if (d?.phone) phone = d.phone
    if (d?.title) title = d.title
  }
  return { chrome, phone, title }
}
