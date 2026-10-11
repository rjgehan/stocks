import { useCallback, useEffect } from 'react'
import { useRouter } from '@tanstack/react-router'
import { isApiError } from '@/lib/api/problem'
import { Button } from '@/components/ui/Button'

/** Whether an error means the server could not be reached (offline, or the connection dropped). */
export function isOfflineError(error: unknown): boolean {
  return isApiError(error) && error.code === 'NETWORK_ERROR'
}

/**
 * The offline app shell's page (§22.10): the service worker served the app, but the first API call (who is signed
 * in) could not reach the server. Retries by itself when the browser comes back online: `router.invalidate()` re-runs
 * the route guards and resets the error boundary (`reset` alone would not re-run a failed `beforeLoad`).
 */
export function OfflineNotice({ reset }: { reset: () => void }) {
  const router = useRouter()
  const onRetry = useCallback(() => {
    reset()
    void router.invalidate()
  }, [reset, router])
  useEffect(() => {
    window.addEventListener('online', onRetry)
    return () => window.removeEventListener('online', onRetry)
  }, [onRetry])
  return (
    <div role="alert" className="flex min-h-dvh flex-col items-center justify-center gap-3 p-6 text-center">
      <p className="text-section font-semibold">You appear to be offline</p>
      <p className="max-w-md text-caption text-text-2">
        stocks needs a connection for live prices and your account. It picks up again as soon as you are back online.
      </p>
      <Button variant="primary" onClick={onRetry}>
        Try again
      </Button>
    </div>
  )
}
