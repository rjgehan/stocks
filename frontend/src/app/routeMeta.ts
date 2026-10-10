/**
 * Per-route layout metadata (`staticData` on each route, read by the shells):
 *  - chrome: 'watchlist' keeps the watchlist panel (desktop) / drawer (tablet); 'full' is full width.
 *  - phone: 'root' screens show the bottom tab bar; 'pushed' screens show a back header instead (§22.3).
 */
export interface RouteMeta {
  chrome?: 'watchlist' | 'full'
  phone?: 'root' | 'pushed'
  title?: string
}

declare module '@tanstack/react-router' {
  interface StaticDataRouteOption extends RouteMeta {}
}
