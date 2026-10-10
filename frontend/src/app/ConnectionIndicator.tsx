import { useRealtimeStatus } from '@/lib/realtime/status'
import { IconWifiOff } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'

/**
 * Live-data status (§19): nothing while the STOMP socket is connected; "Reconnecting" with the polling note while it
 * is down, so a frozen price is never mistaken for a quiet market.
 */
export function ConnectionIndicator({ className }: { className?: string }) {
  const status = useRealtimeStatus()
  if (status === 'connected' || status === 'idle') return null
  const first = status === 'connecting'
  return (
    <span
      role="status"
      title={first ? 'Connecting to live updates…' : 'Live updates are reconnecting; prices refresh every few seconds meanwhile.'}
      className={cx('inline-flex h-7 shrink-0 items-center gap-1.5 rounded-full bg-warn/15 px-2.5 text-micro font-semibold text-warn', className)}
    >
      <IconWifiOff size={14} />
      {first ? 'Connecting' : 'Reconnecting'}
    </span>
  )
}
