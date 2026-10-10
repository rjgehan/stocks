import { createFileRoute } from '@tanstack/react-router'
import { NotificationSettings } from '@/features/push/NotificationSettings'
import { SettingsHeader } from '@/features/settings/SettingsHeader'

export const Route = createFileRoute('/_app/settings/notifications')({
  staticData: { phone: 'pushed' },
  component: NotificationsPage,
})

/** Settings → Notifications (§22.10, Phase 2): Web Push on this device, categories, devices, test. */
function NotificationsPage() {
  return (
    <div className="space-y-4">
      <SettingsHeader title="Notifications" />
      <NotificationSettings />
    </div>
  )
}
