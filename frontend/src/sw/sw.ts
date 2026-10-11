/// <reference lib="webworker" />
/**
 * The service worker (§22.10), built by vite-plugin-pwa (`injectManifest`) into `dist/sw.js`:
 *  - precaches the app shell (index.html, the hashed /assets/*, icons) and answers navigations with it, so the app
 *    opens offline and an old tab keeps working after a deploy until it reloads;
 *  - registers NO runtime caching: /api, /mcp and /ws always go to the network untouched, so private data is never
 *    cached (`scripts/check-sw.mjs` fails the build otherwise);
 *  - shows Web Push messages (§19.2) and opens their same-origin URL on click;
 *  - waits for "SKIP_WAITING" from the update prompt before taking over (registerType 'prompt').
 */
import { cleanupOutdatedCaches, createHandlerBoundToURL, precacheAndRoute } from 'workbox-precaching'
import { NavigationRoute, registerRoute } from 'workbox-routing'
import { NAVIGATION_DENYLIST, notificationFromPush, safeTarget } from './routes'

declare const self: ServiceWorkerGlobalScope & {
  __WB_MANIFEST: Array<string | { url: string; revision: string | null }>
}

// eslint-disable-next-line no-underscore-dangle -- the injection point vite-plugin-pwa replaces with the precache list
precacheAndRoute(self.__WB_MANIFEST)
cleanupOutdatedCaches()
registerRoute(new NavigationRoute(createHandlerBoundToURL('/index.html'), { denylist: NAVIGATION_DENYLIST }))

self.addEventListener('message', (event: ExtendableMessageEvent) => {
  if ((event.data as { type?: string } | null)?.type === 'SKIP_WAITING') void self.skipWaiting()
})

self.addEventListener('push', (event: PushEvent) => {
  let text: string | null = null
  try {
    text = event.data?.text() ?? null
  } catch {
    text = null
  }
  const n = notificationFromPush(text)
  event.waitUntil(
    self.registration.showNotification(n.title, {
      body: n.body,
      tag: n.tag,
      icon: '/icon-192.png',
      badge: '/icon-192.png',
      data: { url: n.url },
    }),
  )
})

self.addEventListener('notificationclick', (event: NotificationEvent) => {
  event.notification.close()
  const target = safeTarget((event.notification.data as { url?: unknown } | null)?.url, self.location.origin)
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
      for (const client of windows) {
        if (new URL(client.url).origin !== self.location.origin) continue
        const focused = await client.focus()
        try {
          await focused.navigate(target)
        } catch {
          /* an uncontrolled client cannot be navigated: it is focused at least */
        }
        return
      }
      await self.clients.openWindow(target)
    })(),
  )
})

/**
 * The browser replaced the subscription (expiry, key rotation by the push service): subscribe again with the same
 * key. Registering it needs the CSRF cookie, which a worker can only read through the Cookie Store API; without it
 * the app re-registers the subscription at its next start (`syncThisDevice`).
 */
self.addEventListener('pushsubscriptionchange', (event: Event) => {
  const e = event as Event & { oldSubscription?: PushSubscription | null; waitUntil: (p: Promise<unknown>) => void }
  e.waitUntil(
    (async () => {
      const key = e.oldSubscription?.options?.applicationServerKey
      if (!key) return
      const sub = await self.registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key })
      const store = (self as unknown as { cookieStore?: { get: (name: string) => Promise<{ value: string } | null> } }).cookieStore
      const csrf = store ? await store.get('XSRF-TOKEN') : null
      if (!csrf) return
      const json = sub.toJSON()
      await fetch('/api/v1/me/push/subscriptions', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf.value },
        body: JSON.stringify({ endpoint: sub.endpoint, p256dh: json.keys?.p256dh ?? '', auth: json.keys?.auth ?? '' }),
      })
    })().catch(() => undefined),
  )
})
