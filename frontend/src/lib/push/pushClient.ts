/**
 * The browser side of Web Push (§19.2, §22.10): what this device supports, and subscribing with the server's VAPID
 * key. Pure helpers take their inputs as arguments so they are unit-tested without a browser.
 *
 * iOS and iPadOS deliver Web Push only to a web app installed to the Home Screen, on iOS 16.4 or later; in Safari the
 * page explains how to install instead of showing a button that cannot work.
 */

/** What the Settings page can offer on this device. */
export type PushSupport =
  | 'supported'
  /** iPhone/iPad in Safari (not installed): show the Add to Home Screen steps. */
  | 'ios-install'
  /** iOS older than 16.4: no Web Push at all. */
  | 'ios-too-old'
  /** No service worker, Push API or Notification API (or an insecure origin, or the dev/mock build). */
  | 'unsupported'

export interface DeviceEnv {
  userAgent: string
  /** `navigator.platform` (iPadOS reports "MacIntel" with touch points). */
  platform: string
  maxTouchPoints: number
  /** Running as an installed app (`display-mode: standalone` or `navigator.standalone`). */
  standalone: boolean
  secure: boolean
  hasServiceWorker: boolean
  hasPushManager: boolean
  hasNotification: boolean
}

/** "17.5" from an iOS user agent ("OS 17_5 like Mac OS X", or Safari's "Version/17.5" on iPadOS). */
export function iosVersion(ua: string): number | null {
  const m = /OS (\d+)[_.](\d+)/.exec(ua) ?? /Version\/(\d+)\.(\d+)/.exec(ua)
  return m ? Number(m[1]) + Number(m[2]) / 100 : null
}

export function isIos(env: Pick<DeviceEnv, 'userAgent' | 'platform' | 'maxTouchPoints'>): boolean {
  if (/iPhone|iPad|iPod/.test(env.userAgent)) return true
  // iPadOS 13+ Safari presents itself as a Mac; touch points give it away
  return env.platform === 'MacIntel' && env.maxTouchPoints > 1
}

export function detectSupport(env: DeviceEnv): PushSupport {
  if (isIos(env)) {
    const v = iosVersion(env.userAgent)
    if (v !== null && v < 16.04) return 'ios-too-old'
    if (!env.standalone) return 'ios-install'
  }
  if (!env.secure || !env.hasServiceWorker || !env.hasPushManager || !env.hasNotification) return 'unsupported'
  return 'supported'
}

/** The live browser's environment. */
export function deviceEnv(): DeviceEnv {
  const nav = typeof navigator === 'undefined' ? undefined : navigator
  const standalone =
    (typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia('(display-mode: standalone)').matches) ||
    Boolean((nav as (Navigator & { standalone?: boolean }) | undefined)?.standalone)
  return {
    userAgent: nav?.userAgent ?? '',
    platform: nav?.platform ?? '',
    maxTouchPoints: nav?.maxTouchPoints ?? 0,
    standalone,
    secure: typeof window !== 'undefined' && window.isSecureContext,
    hasServiceWorker: Boolean(nav && 'serviceWorker' in nav),
    hasPushManager: typeof window !== 'undefined' && 'PushManager' in window,
    hasNotification: typeof window !== 'undefined' && 'Notification' in window,
  }
}

/** base64url (VAPID public key) → bytes, for `applicationServerKey`. */
export function base64UrlToBytes(value: string): Uint8Array<ArrayBuffer> {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (value.length % 4)) % 4)
  const raw = atob(padded)
  const out = new Uint8Array(new ArrayBuffer(raw.length))
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i)
  return out
}

/** bytes → base64url without padding. */
export function bytesToBase64Url(bytes: ArrayBuffer | Uint8Array | null | undefined): string {
  if (!bytes) return ''
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes)
  let s = ''
  for (const b of view) s += String.fromCharCode(b)
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** What `POST /me/push/subscriptions` needs from a `PushSubscription`. */
export function subscriptionBody(sub: Pick<PushSubscription, 'endpoint' | 'getKey'>): { endpoint: string; p256dh: string; auth: string } {
  return { endpoint: sub.endpoint, p256dh: bytesToBase64Url(sub.getKey('p256dh')), auth: bytesToBase64Url(sub.getKey('auth')) }
}

/** Whether a browser subscription was made with this VAPID key (a rotated key needs a new subscription). */
export function sameKey(sub: Pick<PushSubscription, 'options'>, vapidPublicKey: string): boolean {
  const key = sub.options?.applicationServerKey
  return !key || bytesToBase64Url(key) === vapidPublicKey
}

// ------------------------------------------------------------------------------------------- per-player opt-in

const OPT_IN = 'stocks.push.optIn.'

/** The player turned push on on this device (remembered so a re-registration after sign-in needs no new tap). */
export function optedIn(playerId: string): boolean {
  try {
    return localStorage.getItem(OPT_IN + playerId) === '1'
  } catch {
    return false
  }
}

export function setOptedIn(playerId: string, on: boolean): void {
  try {
    if (on) localStorage.setItem(OPT_IN + playerId, '1')
    else localStorage.removeItem(OPT_IN + playerId)
  } catch {
    /* private mode: no memory, the player taps Enable again */
  }
}

// ------------------------------------------------------------------------------------------- browser calls

/** The service worker registration, or null when none controls this app (dev server, mock build, unsupported). */
export async function registration(timeoutMs = 3_000): Promise<ServiceWorkerRegistration | null> {
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return null
  const existing = await navigator.serviceWorker.getRegistration()
  if (!existing) return null
  return Promise.race([navigator.serviceWorker.ready, new Promise<null>((resolve) => setTimeout(() => resolve(null), timeoutMs))])
}

/** This browser's current push subscription, if any. */
export async function currentSubscription(): Promise<PushSubscription | null> {
  const reg = await registration()
  return reg ? reg.pushManager.getSubscription() : null
}

/**
 * Asks for permission (must run from a tap) and subscribes with the server's key. Throws an Error with a readable
 * message when the player declines or the push service refuses.
 */
export async function subscribe(vapidPublicKey: string): Promise<PushSubscription> {
  const permission = await Notification.requestPermission()
  if (permission !== 'granted') {
    throw new Error(permission === 'denied' ? 'Notifications are blocked for this site. Allow them in your browser settings, then try again.' : 'Notifications were not allowed.')
  }
  const reg = await registration(10_000)
  if (!reg) throw new Error('The app is still installing its offline support. Reload the page and try again.')
  const existing = await reg.pushManager.getSubscription()
  if (existing && !sameKey(existing, vapidPublicKey)) await existing.unsubscribe()
  else if (existing) return existing
  return reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: base64UrlToBytes(vapidPublicKey) })
}

/** Unsubscribes this browser; returns the endpoint it had (to tell the server), or null. */
export async function unsubscribe(): Promise<string | null> {
  const sub = await currentSubscription()
  if (!sub) return null
  const endpoint = sub.endpoint
  try {
    await sub.unsubscribe()
  } catch {
    /* already gone */
  }
  return endpoint
}
