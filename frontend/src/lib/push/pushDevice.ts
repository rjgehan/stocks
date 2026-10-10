/**
 * This device's Web Push registration, kept in step with the server (§19.2, §22.10):
 *  - enable: permission (from a tap) → subscribe with the server's VAPID key → `POST /me/push/subscriptions`;
 *  - disable and sign-out: unsubscribe the browser and tell the server (it also forgets the device at logout);
 *  - sync at app start: a subscription the server does not know for this player is re-registered when this player
 *    turned push on on this device (after a password change, or a session that expired), otherwise unsubscribed (it
 *    was removed at sign-out, or another player signed in on this browser); a subscription made with an older VAPID
 *    key is replaced.
 */
import { registerPushSubscription, syncPushSubscription, unregisterPushSubscription } from '@/lib/api/hooks/push'
import type { PushDevice, PushSettings } from '@/lib/api/types'

/** What enabling and syncing need from `GET /me/push`. */
export type PushServerKey = Pick<PushSettings, 'available' | 'vapidPublicKey'>
import { currentSubscription, optedIn, sameKey, setOptedIn, subscribe, subscriptionBody } from './pushClient'

export type DeviceStatus = 'on' | 'off'

export interface PushDeps {
  current: () => Promise<PushSubscription | null>
  subscribe: (vapidPublicKey: string) => Promise<PushSubscription>
  register: typeof registerPushSubscription
  sync: typeof syncPushSubscription
  unregister: typeof unregisterPushSubscription
  permission: () => NotificationPermission
  optedIn: (playerId: string) => boolean
  setOptedIn: (playerId: string, on: boolean) => void
}

const browser: PushDeps = {
  current: currentSubscription,
  subscribe,
  register: registerPushSubscription,
  sync: syncPushSubscription,
  unregister: unregisterPushSubscription,
  permission: () => (typeof Notification === 'undefined' ? 'default' : Notification.permission),
  optedIn,
  setOptedIn,
}

/** Turns push on for this device and player; returns the registered device. */
export async function enableThisDevice(settings: PushServerKey, playerId: string, deps: PushDeps = browser): Promise<PushDevice> {
  if (!settings.available || !settings.vapidPublicKey) throw new Error('Push notifications are not available on this server.')
  const sub = await deps.subscribe(settings.vapidPublicKey)
  const device = await deps.register(subscriptionBody(sub))
  deps.setOptedIn(playerId, true)
  return device
}

/** Turns push off for this device (browser and server). */
export async function disableThisDevice(playerId: string, deps: PushDeps = browser): Promise<void> {
  deps.setOptedIn(playerId, false)
  const sub = await deps.current()
  if (!sub) return
  const endpoint = sub.endpoint
  try {
    await sub.unsubscribe()
  } finally {
    await deps.unregister(endpoint).catch(() => undefined)
  }
}

/** Sign-out: like {@link disableThisDevice}, but never fails the sign-out. */
export async function signOutThisDevice(playerId: string | null | undefined, deps: PushDeps = browser): Promise<void> {
  try {
    if (playerId) deps.setOptedIn(playerId, false)
    const sub = await deps.current()
    if (!sub) return
    await deps.unregister(sub.endpoint).catch(() => undefined)
    await sub.unsubscribe().catch(() => false)
  } catch {
    /* no service worker, no subscription: nothing to do */
  }
}

/** Reconciles this browser's subscription with the server (see the module comment); returns the device status. */
export async function syncThisDevice(settings: PushServerKey, playerId: string, deps: PushDeps = browser): Promise<DeviceStatus> {
  if (!settings.available || !settings.vapidPublicKey) return 'off'
  const sub = await deps.current()
  if (!sub) return 'off'
  const wanted = deps.optedIn(playerId) && deps.permission() === 'granted'
  if (!sameKey(sub, settings.vapidPublicKey)) {
    await sub.unsubscribe().catch(() => false)
    if (!wanted) return 'off'
    await enableThisDevice(settings, playerId, deps)
    return 'on'
  }
  const r = await deps.sync(sub.endpoint)
  if (r.registered) return 'on'
  if (wanted) {
    await deps.register(subscriptionBody(sub))
    return 'on'
  }
  await sub.unsubscribe().catch(() => false)
  return 'off'
}
