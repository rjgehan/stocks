import { Link } from '@tanstack/react-router'
import { useMarkNotificationsRead, useNotifications } from '@/lib/api/hooks/notifications'
import type { Notification } from '@/lib/api/types'
import { formatDateTimeEt } from '@/lib/format/time'
import { IconButton } from '@/components/ui/Button'
import { Popover } from '@/components/ui/Popover'
import { IconBell } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'

const KIND_DOT: Partial<Record<NonNullable<Notification['kind']>, string>> = {
  FILL: 'bg-up',
  PARTIAL_FILL: 'bg-up',
  DEPOSIT: 'bg-accent',
  WITHDRAWAL: 'bg-text-3',
  ADJUSTMENT: 'bg-warn',
  GFV_ISSUED: 'bg-down',
  RESTRICTION_CHANGED: 'bg-down',
  ORDER_CANCELLED: 'bg-text-3',
  ORDER_EXPIRED: 'bg-text-3',
}

function target(n: Notification): { to: '/account/orders/$orderId'; orderId: string } | { to: '/account/activity' } | null {
  const orderId = n.payload && typeof n.payload.orderId === 'string' ? n.payload.orderId : null
  if (orderId) return { to: '/account/orders/$orderId', orderId }
  if (n.kind === 'DEPOSIT' || n.kind === 'WITHDRAWAL' || n.kind === 'ADJUSTMENT') return { to: '/account/activity' }
  return null
}

/** Notification bell (§22.3): unread count, the latest notifications, "Mark all read". */
export function NotificationBell({ size = 'md' }: { size?: 'md' | 'touch' }) {
  const { data } = useNotifications()
  const markRead = useMarkNotificationsRead()
  const items = data?.items ?? []
  const unread = items.filter((n) => !n.readAt).length
  return (
    <Popover
      label="Notifications"
      className="w-[min(360px,calc(100vw-24px))] py-0"
      renderTrigger={(props) => (
        <span className="relative inline-flex">
          <IconButton label={unread ? `Notifications, ${unread} unread` : 'Notifications'} size={size} {...props}>
            <IconBell />
          </IconButton>
          {unread ? (
            <span aria-hidden="true" className="pointer-events-none absolute top-1 right-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-down px-1 text-[10px] font-bold text-on-updown">
              {unread > 9 ? '9+' : unread}
            </span>
          ) : null}
        </span>
      )}
    >
      {(close) => (
        <div>
          <div className="flex items-center justify-between border-b border-divider px-3 py-2">
            <span className="text-section font-semibold">Notifications</span>
            <button
              type="button"
              disabled={!unread}
              onClick={() => markRead.mutate({ all: true })}
              className="text-caption font-semibold text-accent hover:text-accent-hover disabled:text-text-3"
            >
              Mark all read
            </button>
          </div>
          {items.length === 0 ? (
            <p className="px-3 py-6 text-center text-caption text-text-3">Fills, deposits and account alerts show up here.</p>
          ) : (
            <ul className="scrollbar-thin max-h-[60vh] divide-y divide-divider overflow-y-auto">
              {items.slice(0, 20).map((n) => {
                const t = target(n)
                const body = (
                  <div className="flex gap-2.5 px-3 py-2.5">
                    <span className={cx('mt-1.5 size-2 shrink-0 rounded-full', n.readAt ? 'bg-transparent' : (n.kind && KIND_DOT[n.kind]) || 'bg-accent')} aria-hidden="true" />
                    <span className="min-w-0 flex-1">
                      <span className={cx('block text-body', n.readAt ? 'text-text-2' : 'font-semibold text-text-1')}>{n.title}</span>
                      {n.body ? <span className="block text-caption text-text-2">{n.body}</span> : null}
                      <span className="block text-micro text-text-3">{formatDateTimeEt(n.createdAt)}</span>
                    </span>
                  </div>
                )
                const onOpen = () => {
                  if (!n.readAt && n.id) markRead.mutate({ ids: [n.id] })
                  close()
                }
                return (
                  <li key={n.id ?? n.createdAt}>
                    {t?.to === '/account/orders/$orderId' ? (
                      <Link to={t.to} params={{ orderId: t.orderId }} onClick={onOpen} className="block hover:bg-bg-2">
                        {body}
                      </Link>
                    ) : t ? (
                      <Link to={t.to} onClick={onOpen} className="block hover:bg-bg-2">
                        {body}
                      </Link>
                    ) : (
                      <button type="button" onClick={onOpen} className="block w-full text-left hover:bg-bg-2">
                        {body}
                      </button>
                    )}
                  </li>
                )
              })}
            </ul>
          )}
        </div>
      )}
    </Popover>
  )
}
