import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter } from '@tanstack/react-router'
import { routeTree } from './routeTree.gen'
import { isApiError } from './lib/api/problem'
import { queryKeys } from './lib/api/queryKeys'
import { initTheme } from './lib/theme/theme'
import './styles.css'

initTheme()

const queryClient: QueryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (error) => {
      // Session expired mid-use: forget who we were and send the player to /login (§22.2).
      if (isApiError(error) && error.status === 401 && router.state.location.pathname !== '/login') {
        queryClient.setQueryData(queryKeys.me, null)
        void router.navigate({ to: '/login', search: { redirect: router.state.location.href } })
      }
    },
  }),
  defaultOptions: {
    queries: {
      staleTime: 5_000,
      refetchOnWindowFocus: false,
      retry: (failureCount, error) => {
        if (isApiError(error) && error.status >= 400 && error.status < 500) return false
        return failureCount < 2
      },
    },
  },
})

const router = createRouter({
  routeTree,
  context: { queryClient },
  defaultPreload: 'intent',
  defaultPreloadStaleTime: 0,
  scrollRestoration: true,
})

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
