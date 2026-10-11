import { describe, expect, it, vi } from 'vitest'
import type { PushDevice } from '@/lib/api/types'
import {
  type DeviceEnv,
  base64UrlToBytes,
  bytesToBase64Url,
  detectSupport,
  iosVersion,
  optedIn,
  sameKey,
  setOptedIn,
  subscriptionBody,
} from './pushClient'
import { type PushDeps, disableThisDevice, enableThisDevice, signOutThisDevice, syncThisDevice } from './pushDevice'

const env = (over: Partial<DeviceEnv>): DeviceEnv => ({
  userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/129.0 Mobile Safari/537.36',
  platform: 'Linux armv8l',
  maxTouchPoints: 5,
  standalone: false,
  secure: true,
  hasServiceWorker: true,
  hasPushManager: true,
  hasNotification: true,
  ...over,
})

const IPHONE_17 = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 Version/17.5 Mobile/15E148 Safari/604.1'
const IPHONE_16_3 = 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_3 like Mac OS X) AppleWebKit/605.1.15 Version/16.3 Mobile/15E148 Safari/604.1'
const IPAD_AS_MAC = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15'

describe('Web Push support on this device (§22.10)', () => {
  it('needs the Home Screen app on iOS 16.4+, and the APIs elsewhere', () => {
    expect(detectSupport(env({}))).toBe('supported')
    expect(detectSupport(env({ userAgent: IPHONE_17, platform: 'iPhone' }))).toBe('ios-install')
    expect(detectSupport(env({ userAgent: IPHONE_17, platform: 'iPhone', standalone: true }))).toBe('supported')
    expect(detectSupport(env({ userAgent: IPHONE_16_3, platform: 'iPhone', standalone: true }))).toBe('ios-too-old')
    // iPadOS Safari says "Macintosh": the touch points give it away
    expect(detectSupport(env({ userAgent: IPAD_AS_MAC, platform: 'MacIntel', maxTouchPoints: 5 }))).toBe('ios-install')
    expect(detectSupport(env({ userAgent: IPAD_AS_MAC, platform: 'MacIntel', maxTouchPoints: 0 }))).toBe('supported')
    expect(detectSupport(env({ hasPushManager: false }))).toBe('unsupported')
    expect(detectSupport(env({ secure: false }))).toBe('unsupported')
    expect(iosVersion(IPHONE_16_3)).toBeCloseTo(16.03)
    expect(iosVersion('Mozilla/5.0 (iPhone; CPU iPhone OS 16_4 like Mac OS X)')).toBeCloseTo(16.04)
  })

  it('round-trips base64url keys and reads a subscription', () => {
    const key = 'BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM'
    const bytes = base64UrlToBytes(key)
    expect(bytes).toHaveLength(65)
    expect(bytes[0]).toBe(4)
    expect(bytesToBase64Url(bytes)).toBe(key)
    const auth = new Uint8Array([251, 255, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14])
    const sub = { endpoint: 'https://fcm.googleapis.com/fcm/send/x', getKey: (n: string) => (n === 'p256dh' ? bytes.buffer : auth.buffer) }
    expect(subscriptionBody(sub as unknown as PushSubscription)).toEqual({ endpoint: sub.endpoint, p256dh: key, auth: '-_8BAgMEBQYHCAkKCwwNDg' })
    expect(sameKey({ options: { applicationServerKey: bytes.buffer, userVisibleOnly: true } } as PushSubscription, key)).toBe(true)
    expect(sameKey({ options: { applicationServerKey: bytes.buffer, userVisibleOnly: true } } as PushSubscription, 'other')).toBe(false)
  })

  it('remembers the opt-in per player', () => {
    setOptedIn('ply_a', true)
    expect(optedIn('ply_a')).toBe(true)
    expect(optedIn('ply_b')).toBe(false)
    setOptedIn('ply_a', false)
    expect(optedIn('ply_a')).toBe(false)
  })
})

const KEY = 'BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM'
const DEVICE: PushDevice = { id: 'psh_1', label: 'Chrome on Android', pushService: 'fcm.googleapis.com', current: true, createdAt: null, lastSuccessAt: null, lastFailureAt: null }

function fakeSub(endpoint = 'https://fcm.googleapis.com/fcm/send/1', key = KEY) {
  const bytes = base64UrlToBytes(key)
  return {
    endpoint,
    options: { applicationServerKey: bytes.buffer, userVisibleOnly: true },
    getKey: () => new Uint8Array(16).buffer,
    unsubscribe: vi.fn<() => Promise<boolean>>(async () => true),
  } as unknown as PushSubscription & { unsubscribe: ReturnType<typeof vi.fn> }
}

function deps(over: Partial<PushDeps> & { sub?: PushSubscription | null; opted?: boolean; registered?: boolean } = {}): PushDeps & { calls: string[] } {
  const calls: string[] = []
  let opted = over.opted ?? false
  return {
    calls,
    current: async () => over.sub ?? null,
    subscribe: async () => {
      calls.push('subscribe')
      return fakeSub('https://fcm.googleapis.com/fcm/send/new')
    },
    register: async (b) => {
      calls.push(`register ${b.endpoint}`)
      return DEVICE
    },
    sync: async (endpoint) => {
      calls.push(`sync ${endpoint}`)
      return { registered: over.registered ?? false, device: over.registered ? DEVICE : null }
    },
    unregister: async (endpoint) => {
      calls.push(`unregister ${endpoint}`)
      return undefined as never
    },
    permission: () => 'granted',
    optedIn: () => opted,
    setOptedIn: (_p, on) => {
      opted = on
      calls.push(`optIn ${on}`)
    },
    ...over,
  }
}

const SETTINGS = { available: true, vapidPublicKey: KEY }

describe('keeping this device in step with the server (§19.2)', () => {
  it('enables: subscribe with the server key, register, remember the opt-in', async () => {
    const d = deps()
    expect(await enableThisDevice(SETTINGS, 'ply_a', d)).toEqual(DEVICE)
    expect(d.calls).toEqual(['subscribe', 'register https://fcm.googleapis.com/fcm/send/new', 'optIn true'])
    await expect(enableThisDevice({ available: false, vapidPublicKey: null }, 'ply_a', d)).rejects.toThrow(/not available/)
  })

  it('a registered subscription stays on', async () => {
    const sub = fakeSub()
    const d = deps({ sub, registered: true })
    expect(await syncThisDevice(SETTINGS, 'ply_a', d)).toBe('on')
    expect(sub.unsubscribe).not.toHaveBeenCalled()
  })

  it('an unknown subscription is re-registered only for the player who opted in on this device', async () => {
    const sub = fakeSub()
    const mine = deps({ sub, opted: true })
    expect(await syncThisDevice(SETTINGS, 'ply_a', mine)).toBe('on')
    expect(mine.calls).toContain(`register ${sub.endpoint}`)
    // another player signed in on this browser (or the player signed out before): the browser is unsubscribed
    const sub2 = fakeSub()
    const other = deps({ sub: sub2, opted: false })
    expect(await syncThisDevice(SETTINGS, 'ply_b', other)).toBe('off')
    expect(sub2.unsubscribe).toHaveBeenCalled()
    expect(other.calls.some((c) => c.startsWith('register'))).toBe(false)
    // permission revoked in the browser: never re-registered
    const sub3 = fakeSub()
    expect(await syncThisDevice(SETTINGS, 'ply_a', deps({ sub: sub3, opted: true, permission: () => 'denied' }))).toBe('off')
  })

  it('a subscription made with an older server key is replaced', async () => {
    const old = fakeSub('https://fcm.googleapis.com/fcm/send/old', 'BOld' + KEY.slice(4))
    const d = deps({ sub: old, opted: true })
    expect(await syncThisDevice(SETTINGS, 'ply_a', d)).toBe('on')
    expect(old.unsubscribe).toHaveBeenCalled()
    expect(d.calls).toEqual(['subscribe', 'register https://fcm.googleapis.com/fcm/send/new', 'optIn true'])
  })

  it('nothing to do without a subscription or without server support', async () => {
    expect(await syncThisDevice(SETTINGS, 'ply_a', deps())).toBe('off')
    expect(await syncThisDevice({ available: false, vapidPublicKey: null }, 'ply_a', deps({ sub: fakeSub() }))).toBe('off')
  })

  it('disable and sign-out unsubscribe the browser, tell the server and forget the opt-in', async () => {
    const sub = fakeSub()
    const d = deps({ sub, opted: true })
    await disableThisDevice('ply_a', d)
    expect(sub.unsubscribe).toHaveBeenCalled()
    expect(d.calls).toEqual(['optIn false', `unregister ${sub.endpoint}`])
    const sub2 = fakeSub()
    const s = deps({ sub: sub2, opted: true })
    await signOutThisDevice('ply_a', s)
    expect(s.calls).toEqual(['optIn false', `unregister ${sub2.endpoint}`])
    expect(sub2.unsubscribe).toHaveBeenCalled()
    // never fails a sign-out
    await expect(signOutThisDevice('ply_a', deps({ current: async () => Promise.reject(new Error('no sw')) }))).resolves.toBeUndefined()
  })
})
