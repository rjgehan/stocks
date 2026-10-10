/**
 * Theme and up/down colors (§22.4 Settings, §22.8 tokens). Drives `<html data-theme>` and
 * `<html data-updown>`; dark is the default. The choice is kept per device in localStorage (read before
 * first paint by public/theme-init.js) and mirrored into `Me.preferences` once the backend has PATCH /me.
 */
import { create } from 'zustand'

export type ThemePref = 'DARK' | 'LIGHT' | 'SYSTEM'
export type UpDownPref = 'GREEN_UP' | 'RED_UP'

export const THEME_KEY = 'stocks.theme'
export const UPDOWN_KEY = 'stocks.updown'
const THEME_COLOR = { light: '#F5F6F8', dark: '#0D0F13' } as const

function read<T extends string>(key: string, allowed: readonly T[], fallback: T): T {
  try {
    const v = localStorage.getItem(key)
    return v !== null && (allowed as readonly string[]).includes(v) ? (v as T) : fallback
  } catch {
    return fallback
  }
}

function write(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    /* storage unavailable (private mode): the choice lasts for this page only */
  }
}

const prefersDark = (): boolean =>
  typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia('(prefers-color-scheme: dark)').matches

export function effectiveTheme(pref: ThemePref): 'dark' | 'light' {
  if (pref === 'SYSTEM') return prefersDark() ? 'dark' : 'light'
  return pref === 'DARK' ? 'dark' : 'light'
}

export function applyTheme(theme: ThemePref, upDown: UpDownPref, root: HTMLElement = document.documentElement): void {
  if (theme === 'SYSTEM') root.removeAttribute('data-theme')
  else root.setAttribute('data-theme', theme === 'DARK' ? 'dark' : 'light')
  if (upDown === 'RED_UP') root.setAttribute('data-updown', 'red-up')
  else root.removeAttribute('data-updown')
  // Keep the browser chrome (and the iOS status bar) in step with an explicit choice.
  const metas = document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]')
  metas.forEach((m) => {
    const media = m.getAttribute('media') ?? ''
    const forMedia: 'dark' | 'light' = media.includes('dark') ? 'dark' : 'light'
    m.setAttribute('content', THEME_COLOR[theme === 'SYSTEM' ? forMedia : effectiveTheme(theme)])
  })
}

interface ThemeState {
  theme: ThemePref
  upDown: UpDownPref
  setTheme: (t: ThemePref) => void
  setUpDown: (u: UpDownPref) => void
  toggle: () => void
}

export const useThemeStore = create<ThemeState>()((set, get) => ({
  theme: typeof window === 'undefined' ? 'DARK' : read<ThemePref>(THEME_KEY, ['DARK', 'LIGHT', 'SYSTEM'], 'DARK'),
  upDown: typeof window === 'undefined' ? 'GREEN_UP' : read<UpDownPref>(UPDOWN_KEY, ['GREEN_UP', 'RED_UP'], 'GREEN_UP'),
  setTheme: (theme) => {
    write(THEME_KEY, theme)
    set({ theme })
    applyTheme(theme, get().upDown)
  },
  setUpDown: (upDown) => {
    write(UPDOWN_KEY, upDown)
    set({ upDown })
    applyTheme(get().theme, upDown)
  },
  toggle: () => get().setTheme(effectiveTheme(get().theme) === 'dark' ? 'LIGHT' : 'DARK'),
}))

/** Call once at boot. */
export function initTheme(): void {
  const { theme, upDown } = useThemeStore.getState()
  applyTheme(theme, upDown)
  if (typeof window.matchMedia === 'function') {
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
      const s = useThemeStore.getState()
      if (s.theme === 'SYSTEM') applyTheme('SYSTEM', s.upDown)
    })
  }
}
