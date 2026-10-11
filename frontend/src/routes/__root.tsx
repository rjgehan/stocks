import { Suspense, lazy } from 'react'
import { Link, Outlet, createRootRouteWithContext } from '@tanstack/react-router'
import type { QueryClient } from '@tanstack/react-query'
import { isApiError } from '@/lib/api/problem'
import { Button } from '@/components/ui/Button'
import { ToastHost } from '@/features/notifications/ToastHost'
import { OfflineNotice, isOfflineError } from '@/features/pwa/OfflineNotice'

export interface RouterContext {
  queryClient: QueryClient
}

// Dev-only and opt-in (`VITE_QUERY_DEVTOOLS=1`): never part of the production bundle, and its floating button never
// covers the phone tab bar or the Buy button during normal development and e2e runs.
const QueryDevtools = import.meta.env.DEV && import.meta.env.VITE_QUERY_DEVTOOLS === '1'
  ? lazy(() => import('@tanstack/react-query-devtools').then((m) => ({ default: m.ReactQueryDevtools })))
  : null

export const Route = createRootRouteWithContext<RouterContext>()({
  component: RootLayout,
  notFoundComponent: NotFound,
  errorComponent: RootError,
})

function RootLayout() {
  return (
    <>
      <Outlet />
      <ToastHost />
      {QueryDevtools ? (
        <Suspense fallback={null}>
          <QueryDevtools buttonPosition="bottom-left" />
        </Suspense>
      ) : null}
    </>
  )
}

function NotFound() {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-3 p-6 text-center">
      <p className="text-[48px] leading-none font-bold text-text-3">404</p>
      <p className="text-section font-semibold">This page does not exist.</p>
      <Link to="/" className="text-accent hover:text-accent-hover">
        Back to stocks
      </Link>
    </div>
  )
}

function RootError({ error, reset }: { error: unknown; reset: () => void }) {
  // Phase 2 (§22.10): the offline shell opened but the server is unreachable
  if (isOfflineError(error)) return <OfflineNotice reset={reset} />
  const message = isApiError(error) ? `${error.problem.title} (${error.code})` : error instanceof Error ? error.message : 'Something went wrong.'
  return (
    <div role="alert" className="flex min-h-dvh flex-col items-center justify-center gap-3 p-6 text-center">
      <p className="text-section font-semibold">Something went wrong</p>
      <p className="max-w-md text-caption text-text-2">{message}</p>
      <Button variant="primary" onClick={reset}>
        Try again
      </Button>
    </div>
  )
}
