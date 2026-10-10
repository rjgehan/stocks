import { useSyncExternalStore } from 'react'
import type { ConnectionStatus } from './hub'
import { realtime } from './stomp'

const subscribe = (onChange: () => void): (() => void) => {
  const off = realtime().onStatus(onChange)
  return () => {
    off()
  }
}
const snapshot = (): ConnectionStatus => realtime().getStatus()

/** The STOMP connection status (§19): idle · connecting · connected · reconnecting. */
export function useRealtimeStatus(): ConnectionStatus {
  return useSyncExternalStore(subscribe, snapshot, () => 'idle')
}

/**
 * Polling fallback (§19, §22.6): while the socket is not connected, REST queries that normally ride on STOMP
 * pushes refetch on an interval instead. Returns the interval to pass as `refetchInterval` (false when live).
 * A slow safety refetch keeps data honest even when connected (a quiet topic is indistinguishable from a dead one).
 */
export function usePollWhenOffline(offlineMs: number, liveMs: number | false = false): number | false {
  const status = useRealtimeStatus()
  return status === 'connected' ? liveMs : offlineMs
}
