import { useSyncExternalStore } from 'react'

/** §22.3: phone < 600 · tablet 600–1023 · desktop 1024–1439 · wide ≥ 1440 (matches the Tailwind screens). */
export type Breakpoint = 'phone' | 'tablet' | 'desktop' | 'wide'

const QUERIES = ['(min-width: 600px)', '(min-width: 1024px)', '(min-width: 1440px)'] as const

function hasMatchMedia(): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
}

export function currentBreakpoint(): Breakpoint {
  if (!hasMatchMedia()) return 'desktop'
  const [tablet, desktop, wide] = QUERIES.map((q) => window.matchMedia(q).matches)
  if (wide) return 'wide'
  if (desktop) return 'desktop'
  if (tablet) return 'tablet'
  return 'phone'
}

function subscribe(onChange: () => void): () => void {
  if (!hasMatchMedia()) return () => {}
  const lists = QUERIES.map((q) => window.matchMedia(q))
  lists.forEach((l) => l.addEventListener('change', onChange))
  return () => lists.forEach((l) => l.removeEventListener('change', onChange))
}

export function useBreakpoint(): Breakpoint {
  return useSyncExternalStore(subscribe, currentBreakpoint, () => 'desktop')
}

export const isPhone = (bp: Breakpoint): boolean => bp === 'phone'
export const hasDesktopChrome = (bp: Breakpoint): boolean => bp === 'desktop' || bp === 'wide'
