import { type QueryClient } from '@tanstack/react-query'
import { redirect } from '@tanstack/react-router'
import { api, unwrap } from '@/lib/api/client'
import { isApiError } from '@/lib/api/problem'
import { queryKeys } from '@/lib/api/queryKeys'
import type { Me } from '@/lib/api/types'

async function loadMe(queryClient: QueryClient): Promise<Me | null> {
  return queryClient.ensureQueryData({
    queryKey: queryKeys.me,
    queryFn: async () => {
      try {
        return await unwrap(api.GET('/api/v1/me'))
      } catch (e) {
        if (isApiError(e) && e.status === 401) return null
        throw e
      }
    },
  })
}

/** Route guard (`beforeLoad`): anonymous visitors go to /login?redirect=… (§22.2). */
export async function requireAuth(queryClient: QueryClient, href: string): Promise<Me> {
  const me = await loadMe(queryClient)
  if (!me) throw redirect({ to: '/login', search: { redirect: href } })
  return me
}

/** Route guard for /admin/*: ADMIN only (§21.3); everyone else is sent home. */
export async function requireAdmin(queryClient: QueryClient, href: string): Promise<Me> {
  const me = await requireAuth(queryClient, href)
  if (me.role !== 'ADMIN') throw redirect({ to: '/' })
  return me
}
