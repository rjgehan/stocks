import { createFileRoute } from '@tanstack/react-router'
import { useUpdatePreferences } from '@/lib/api/hooks/identity'
import { type ThemePref, type UpDownPref, useThemeStore } from '@/lib/theme/theme'
import { Segmented } from '@/components/ui/Segmented'
import { SettingsHeader } from '@/features/settings/SettingsHeader'

export const Route = createFileRoute('/_app/settings/appearance')({
  staticData: { phone: 'pushed' },
  component: AppearanceSettings,
})

/** Theme (Dark default / Light / System) and up/down colors (green-up / red-up), §22.4 and §22.8; saved per device and to your profile. */
function AppearanceSettings() {
  const { theme, upDown, setTheme, setUpDown } = useThemeStore()
  const update = useUpdatePreferences()
  return (
    <div className="space-y-4">
      <SettingsHeader title="Appearance" />
      <section className="space-y-4 rounded-lg border border-divider bg-bg-1 p-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="font-semibold">Theme</h2>
            <p className="text-caption text-text-3">Dark is the default.</p>
          </div>
          <Segmented<ThemePref>
            label="Theme"
            value={theme}
            onChange={(t) => {
              setTheme(t)
              update.mutate({ theme: t })
            }}
            options={[
              { value: 'DARK', label: 'Dark' },
              { value: 'LIGHT', label: 'Light' },
              { value: 'SYSTEM', label: 'System' },
            ]}
          />
        </div>
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-divider pt-4">
          <div>
            <h2 className="font-semibold">Up / down colors</h2>
            <p className="text-caption text-text-3">
              <span className="text-up">▲ up</span> · <span className="text-down">▼ down</span>
            </p>
          </div>
          <Segmented<UpDownPref>
            label="Up and down colors"
            value={upDown}
            onChange={(u) => {
              setUpDown(u)
              update.mutate({ upDown: u })
            }}
            options={[
              { value: 'GREEN_UP', label: 'Green up' },
              { value: 'RED_UP', label: 'Red up' },
            ]}
          />
        </div>
      </section>
    </div>
  )
}
