import { IconButton } from '@/components/ui/Button'
import { IconBell } from '@/components/ui/icons'

/** Notification bell (§22.3). Phase 1 wires GET /me/notifications and /user/queue/notifications. */
export function NotificationBell({ size = 'md' }: { size?: 'md' | 'touch' }) {
  return (
    <IconButton label="Notifications" size={size}>
      <IconBell />
    </IconButton>
  )
}
