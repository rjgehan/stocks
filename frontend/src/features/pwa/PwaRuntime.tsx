import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useRegisterSW } from 'virtual:pwa-register/react'
import { API_MOCK, api, unwrap } from '@/lib/api/client'
import { useMe } from '@/lib/api/hooks/identity'
import { queryKeys } from '@/lib/api/queryKeys'
import { Button } from '@/components/ui/Button'
import { currentSubscription } from '@/lib/push/pushClient'
import { syncThisDevice } from '@/lib/push/pushDevice'

/** The service worker runs in production builds only: never on the Vite dev server or in mock mode (§22.10). */
export const PWA_ENABLED: boolean = import.meta.env.PROD && !API_MOCK

const UPDATE_EVERY_MS = 60 * 60 * 1000

/**
 * Registers the service worker and offers new versions (§22.10): a deploy installs a waiting worker and this shows
 * "New version available · Reload"; Reload activates it and reloads. Checks hourly and when the tab becomes visible.
 */
function UpdatePrompt() {
  const {
    needRefresh: [needRefresh, setNeedRefresh],
    updateServiceWorker,
  } = useRegisterSW({
    immediate: true,
    onRegisteredSW(_url, registration) {
      if (!registration) return
      const check = () => {
        if (document.visibilityState === 'visible' && navigator.onLine !== false) void registration.update().catch(() => undefined)
      }
      setInterval(check, UPDATE_EVERY_MS)
      document.addEventListener('visibilitychange', check)
    },
  })
  if (!needRefresh) return null
  return (
    <div
      role="status"
      aria-live="polite"
      className="fixed inset-x-3 bottom-[calc(72px+env(safe-area-inset-bottom))] z-[60] mx-auto flex max-w-md items-center gap-3 rounded-lg border border-border bg-bg-1 px-4 py-3 shadow-2xl tablet:bottom-4"
    >
      <span className="min-w-0 flex-1 text-body font-medium">New version available</span>
      <Button size="sm" variant="ghost" onClick={() => setNeedRefresh(false)}>
        Later
      </Button>
      <Button size="sm" variant="primary" onClick={() => void updateServiceWorker(true)}>
        Reload
      </Button>
    </div>
  )
}

/**
 * At app start, reconciles this browser's push subscription with the server for the signed-in player (§19.2): only
 * when the browser has one, so players who never turned push on cost no request.
 */
function PushStartupSync() {
  const qc = useQueryClient()
  const { data: me } = useMe()
  const playerId = me?.playerId ?? null
  useEffect(() => {
    if (!playerId) return
    let cancelled = false
    void (async () => {
      const sub = await currentSubscription().catch(() => null)
      if (!sub || cancelled) return
      const settings = await qc.fetchQuery({ queryKey: queryKeys.push, queryFn: () => unwrap(api.GET('/api/v1/me/push')) })
      if (!cancelled) await syncThisDevice(settings, playerId)
    })().catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [playerId, qc])
  return null
}

/** Mounted once in the app shell. */
export function PwaRuntime() {
  if (!PWA_ENABLED) return null
  return (
    <>
      <UpdatePrompt />
      <PushStartupSync />
    </>
  )
}
