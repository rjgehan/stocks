import { useMe } from '@/lib/api/hooks/identity'
import { useSessionTopic, useUserEvents } from '@/lib/realtime/useUserEvents'
import { DesktopShell } from './DesktopShell'
import { MobileShell } from './MobileShell'
import { ReauthDialog } from './ReauthDialog'
import { useBreakpoint } from './useBreakpoint'

/** Picks the phone or tablet/desktop shell (§22.3) and starts the per-user realtime streams (§22.6). */
export function AppShell() {
  const bp = useBreakpoint()
  const { data: me } = useMe()
  useUserEvents(me?.role === 'ADMIN')
  useSessionTopic()
  return (
    <>
      {bp === 'phone' ? <MobileShell /> : <DesktopShell breakpoint={bp} />}
      <ReauthDialog />
    </>
  )
}
