import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../client'
import { queryKeys } from '../queryKeys'
import type { Notification } from '../types'

type NotificationPage = { items: Notification[] | null; nextCursor: string | null }

/** `GET /me/notifications` (§18.1): the bell's list. New items arrive on `/user/queue/notifications`. */
export function useNotifications() {
  return useQuery({
    queryKey: queryKeys.notifications,
    queryFn: () => unwrap(api.GET('/api/v1/me/notifications', { params: { query: { limit: 30 } } })),
    staleTime: 30_000,
    refetchInterval: 120_000,
    retry: false,
  })
}

export function useMarkNotificationsRead() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { ids?: string[]; all?: boolean }) => unwrap(api.POST('/api/v1/me/notifications/read', { body })),
    onMutate: async ({ ids, all }) => {
      const now = new Date().toISOString()
      qc.setQueryData<NotificationPage>(queryKeys.notifications, (page) =>
        page?.items
          ? { ...page, items: page.items.map((n) => (all || (n.id && ids?.includes(n.id)) ? { ...n, readAt: n.readAt ?? now } : n)) }
          : page,
      )
    },
    onSettled: () => void qc.invalidateQueries({ queryKey: queryKeys.notifications }),
  })
}

/** Prepends a pushed notification to the cached list (deduplicated by id). */
export function pushNotification(qc: ReturnType<typeof useQueryClient>, n: Notification) {
  qc.setQueryData<NotificationPage>(queryKeys.notifications, (page) => {
    if (!page) return page
    const items = page.items ?? []
    if (n.id && items.some((x) => x.id === n.id)) return page
    return { ...page, items: [n, ...items] }
  })
}
