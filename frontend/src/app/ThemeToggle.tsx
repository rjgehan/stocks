import { effectiveTheme, useThemeStore } from '@/lib/theme/theme'
import { IconButton } from '@/components/ui/Button'
import { IconMoon, IconSun } from '@/components/ui/icons'

export function ThemeToggle({ size = 'md' }: { size?: 'md' | 'touch' }) {
  const theme = useThemeStore((s) => s.theme)
  const toggle = useThemeStore((s) => s.toggle)
  const dark = effectiveTheme(theme) === 'dark'
  return (
    <IconButton label={dark ? 'Switch to light theme' : 'Switch to dark theme'} onClick={toggle} size={size}>
      {dark ? <IconSun /> : <IconMoon />}
    </IconButton>
  )
}
