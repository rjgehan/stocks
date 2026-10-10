import { useEffect, useMemo, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { describeError, errorBody } from '@/lib/api/errors'
import { useMe } from '@/lib/api/hooks/identity'
import { useDeletePushDevice, usePushSettings, useTestPush, useUpdatePushPreferences } from '@/lib/api/hooks/push'
import { queryKeys } from '@/lib/api/queryKeys'
import type { PushPreferences, PushSettings } from '@/lib/api/types'
import { formatDateTimeEt } from '@/lib/format/time'
import { Button, IconButton } from '@/components/ui/Button'
import { Switch } from '@/components/ui/Field'
import { Pill } from '@/components/ui/Pill'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { IconBell, IconTrash } from '@/components/ui/icons'
import { toast } from '@/features/notifications/toastStore'
import { PWA_ENABLED } from '@/features/pwa/pwaEnabled'
import { type PushSupport, detectSupport, deviceEnv } from '@/lib/push/pushClient'
import { type DeviceStatus, disableThisDevice, enableThisDevice, syncThisDevice } from '@/lib/push/pushDevice'

const CATEGORIES: ReadonlyArray<{ key: keyof PushPreferences; label: string; description: string }> = [
  { key: 'fills', label: 'Order fills', description: 'Fills, partial fills, cancellations and expiries' },
  { key: 'alerts', label: 'Price alerts', description: 'When one of your price alerts fires' },
  { key: 'money', label: 'Deposits and admin cash', description: 'Deposits, withdrawals and adjustments by the admin' },
  { key: 'account', label: 'Account', description: 'Good-faith violations, restrictions, splits and resets' },
  { key: 'league', label: 'League events', description: 'A new weekly leader' },
]

function notify(title: string, e: unknown) {
  const d = describeError(e)
  toast({ tone: 'error', title, body: errorBody(d) ?? (e instanceof Error ? e.message : d.title) })
}

/** Keeps this device's status in step with the server once the settings are loaded. */
function useDeviceStatus(settings: PushSettings | undefined, playerId: string | null | undefined, support: PushSupport) {
  const [synced, setSynced] = useState<DeviceStatus | null>(null)
  const qc = useQueryClient()
  // only the server key and its availability decide the sync (a device list refresh must not re-run it)
  const loaded = settings !== undefined
  const available = settings?.available ?? false
  const vapidPublicKey = settings?.vapidPublicKey ?? null
  const eligible = loaded && Boolean(playerId) && support === 'supported' && available
  useEffect(() => {
    if (!eligible || !playerId) return
    let cancelled = false
    syncThisDevice({ available, vapidPublicKey }, playerId)
      .then((s) => {
        if (!cancelled) setSynced(s)
        if (s === 'on') void qc.invalidateQueries({ queryKey: queryKeys.push })
      })
      .catch(() => {
        if (!cancelled) setSynced('off')
      })
    return () => {
      cancelled = true
    }
  }, [eligible, available, vapidPublicKey, playerId, qc])
  const status: DeviceStatus | 'checking' = !loaded ? 'checking' : !eligible ? 'off' : (synced ?? 'checking')
  return [status, setSynced] as const
}

/**
 * Settings → Notifications (§22.10): Web Push on this device (with the iOS Home Screen steps where needed), the five
 * categories, the player's devices and a test notification.
 */
export function NotificationSettings() {
  const { data: me } = useMe()
  const settings = usePushSettings()
  const prefs = useUpdatePushPreferences()
  const remove = useDeletePushDevice()
  const test = useTestPush()
  const qc = useQueryClient()
  const support = useMemo<PushSupport>(() => {
    const detected = detectSupport(deviceEnv())
    // the dev server and mock builds register no service worker: nothing could subscribe
    return detected === 'supported' && !PWA_ENABLED ? 'unsupported' : detected
  }, [])
  const permission = typeof Notification === 'undefined' ? 'default' : Notification.permission
  const [status, setStatus] = useDeviceStatus(settings.data, me?.playerId, support)
  const [busy, setBusy] = useState(false)
  const s = settings.data
  const available = s?.available ?? false
  const devices = s?.devices ?? []

  const enable = async () => {
    if (!s || !me?.playerId) return
    setBusy(true)
    try {
      await enableThisDevice(s, me.playerId)
      setStatus('on')
      toast({ tone: 'up', title: 'Push notifications are on for this device' })
    } catch (e) {
      notify('Push notifications not turned on', e)
    } finally {
      setBusy(false)
      void qc.invalidateQueries({ queryKey: queryKeys.push })
    }
  }

  const disable = async () => {
    if (!me?.playerId) return
    setBusy(true)
    try {
      await disableThisDevice(me.playerId)
      setStatus('off')
      toast({ tone: 'neutral', title: 'Push notifications are off for this device' })
    } catch (e) {
      notify('Push notifications not turned off', e)
    } finally {
      setBusy(false)
      void qc.invalidateQueries({ queryKey: queryKeys.push })
    }
  }

  if (settings.isPending) return <SkeletonRows rows={4} cols={1} />
  if (settings.isError) {
    return <p className="text-body text-text-2">Notification settings are unavailable right now. {errorBody(describeError(settings.error))}</p>
  }

  return (
    <div className="space-y-4">
      <section className="space-y-3 rounded-lg border border-divider bg-bg-1 p-4" aria-labelledby="push-device">
        <div className="flex items-center gap-2">
          <IconBell size={18} />
          <h2 id="push-device" className="text-section font-semibold">
            This device
          </h2>
          {status === 'on' ? <Pill tone="up">On</Pill> : status === 'off' ? <Pill tone="neutral">Off</Pill> : null}
        </div>
        <DeviceMessage support={support} available={available} permission={permission} status={status} />
        {support === 'supported' && available && permission !== 'denied' ? (
          status === 'on' ? (
            <Button variant="secondary" onClick={() => void disable()} disabled={busy}>
              Turn off on this device
            </Button>
          ) : (
            <Button variant="primary" onClick={() => void enable()} disabled={busy || status === 'checking'}>
              {busy ? 'Turning on…' : 'Enable on this device'}
            </Button>
          )
        ) : null}
      </section>

      <section className="rounded-lg border border-divider bg-bg-1 px-4 py-2" aria-labelledby="push-categories">
        <div className="flex items-baseline justify-between gap-2 pt-2 pb-1">
          <h2 id="push-categories" className="text-section font-semibold">
            What to send
          </h2>
          <span role="status" className="text-caption text-text-3">
            {prefs.isPending ? 'Saving…' : prefs.isSuccess ? 'Saved' : ''}
          </span>
        </div>
        <p className="pb-1 text-caption text-text-3">For every device where push is on. The bell in the app always shows everything.</p>
        <div className="divide-y divide-divider">
          {CATEGORIES.map((c) => (
            <Switch
              key={c.key}
              className="py-2"
              label={c.label}
              description={c.description}
              checked={s?.preferences?.[c.key] ?? true}
              disabled={!available}
              onChange={(v) => prefs.mutate({ [c.key]: v }, { onError: (e) => notify('Preference not saved', e) })}
            />
          ))}
        </div>
      </section>

      <section className="rounded-lg border border-divider bg-bg-1" aria-labelledby="push-devices">
        <div className="flex items-center justify-between gap-2 px-4 pt-3 pb-1">
          <h2 id="push-devices" className="text-section font-semibold">
            Your devices
          </h2>
          <Button
            size="sm"
            variant="secondary"
            disabled={!available || devices.length === 0 || test.isPending}
            onClick={() =>
              test.mutate(undefined, {
                onSuccess: (r) =>
                  toast({
                    tone: r.delivered > 0 ? 'up' : 'warn',
                    title: `Test sent to ${r.devices} device${r.devices === 1 ? '' : 's'}`,
                    body: `${r.delivered} delivered${r.failed ? `, ${r.failed} failed` : ''}${r.removed ? `, ${r.removed} removed (no longer subscribed)` : ''}`,
                  }),
                onError: (e) => notify('Test notification not sent', e),
              })
            }
          >
            {test.isPending ? 'Sending…' : 'Send a test notification'}
          </Button>
        </div>
        {devices.length === 0 ? (
          <p className="px-4 pb-4 text-caption text-text-3">No device gets push notifications yet.</p>
        ) : (
          <ul className="divide-y divide-divider" aria-label="Push devices">
            {devices.map((d) => (
              <li key={d.id} className="flex items-center gap-3 px-4 py-3" data-testid="push-device">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="truncate font-medium">{d.label}</span>
                    {d.current ? <Pill tone="accent">This device</Pill> : null}
                  </div>
                  <div className="truncate text-caption text-text-3">
                    {d.pushService} · added {formatDateTimeEt(d.createdAt)}
                    {d.lastSuccessAt ? ` · last delivered ${formatDateTimeEt(d.lastSuccessAt)}` : ''}
                    {d.lastFailureAt && (!d.lastSuccessAt || d.lastFailureAt > d.lastSuccessAt) ? ' · last delivery failed' : ''}
                  </div>
                </div>
                <IconButton
                  label={`Remove ${d.label}`}
                  onClick={() =>
                    d.id &&
                    remove.mutate(d.id, {
                      onSuccess: () => {
                        if (d.current) setStatus('off')
                        toast({ tone: 'neutral', title: `${d.label} removed` })
                      },
                      onError: (e) => notify('Device not removed', e),
                    })
                  }
                >
                  <IconTrash size={18} />
                </IconButton>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

/** What this device can do, in words (§22.10), including the iOS Home Screen steps. */
export function DeviceMessage({
  support,
  available,
  permission,
  status,
}: {
  support: PushSupport
  available: boolean
  permission: NotificationPermission
  status: DeviceStatus | 'checking'
}) {
  if (!available) return <p className="text-body text-text-2">Push notifications are not available on this server right now. Notifications still show in the app.</p>
  if (support === 'ios-too-old') {
    return <p className="text-body text-text-2">Push notifications on iPhone and iPad need iOS 16.4 or later. Update in Settings → General → Software Update.</p>
  }
  if (support === 'ios-install') {
    return (
      <div className="space-y-2 text-body text-text-2" data-testid="ios-install">
        <p>On iPhone and iPad, notifications work only in the app installed to your Home Screen (iOS 16.4 or later):</p>
        <ol className="list-decimal space-y-1 pl-5">
          <li>
            Tap <strong className="text-text-1">Share</strong> in Safari.
          </li>
          <li>
            Tap <strong className="text-text-1">Add to Home Screen</strong>, then <strong className="text-text-1">Add</strong>.
          </li>
          <li>Open stocks from your Home Screen, sign in, and come back here to turn notifications on.</li>
        </ol>
      </div>
    )
  }
  if (support === 'unsupported') {
    return (
      <p className="text-body text-text-2">
        {PWA_ENABLED
          ? 'This browser cannot receive push notifications. Try Chrome, Edge, Firefox or Safari on a computer or Android phone, or the installed app on iPhone.'
          : 'Push notifications need the production app: this development build has no service worker.'}
      </p>
    )
  }
  if (permission === 'denied') {
    return <p className="text-body text-text-2">Notifications are blocked for this site. Allow them in your browser&apos;s site settings, then come back here.</p>
  }
  if (status === 'on') return <p className="text-body text-text-2">This device gets push notifications, even while the app is closed.</p>
  return <p className="text-body text-text-2">Get fills, price alerts and deposits on this device, even while the app is closed.</p>
}
