import { useSessionTopic, useUserEvents } from '@/lib/realtime/useUserEvents'
import { DesktopShell } from './DesktopShell'
import { MobileShell } from './MobileShell'
import { useBreakpoint } from './useBreakpoint'

/** Picks the phone or tablet/desktop shell (§22.3) and starts the per-user realtime streams (§22.6). */
export function AppShell() {
  const bp = useBreakpoint()
  useUserEvents()
  useSessionTopic()
  return bp === 'phone' ? <MobileShell /> : <DesktopShell breakpoint={bp} />
}
