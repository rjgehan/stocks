import { useId } from 'react'
import { describeError, errorBody } from '@/lib/api/errors'
import { useRequestRun, useRunRequests } from '@/lib/api/hooks/agents'
import { useMe } from '@/lib/api/hooks/identity'
import { Button } from '@/components/ui/Button'
import { Pill } from '@/components/ui/Pill'
import { MenuItem, Popover } from '@/components/ui/Popover'
import { IconBolt, IconChevronDown } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'
import { toast } from '@/features/notifications/toastStore'
import { isOpenRequest, runNowBlocked, runNowTip, runRequestView } from './runNowView'

/** `POST /me/bots/{handle}/run-requests` with the error as a toast (409 names the open request, 429 the wait). */
export function useStartRun() {
  const request = useRequestRun()
  const start = (handle: string, dryRun: boolean, onDone?: () => void) =>
    request.mutate(
      { handle, dryRun },
      {
        onSuccess: onDone,
        onError: (e) => {
          const d = describeError(e)
          toast({ tone: 'error', title: dryRun ? 'Dry run not requested' : 'Run not requested', body: errorBody(d) ?? d.title })
        },
      },
    )
  return { start, pending: request.isPending }
}

/**
 * "Run now" (§20.9) for one AI bot, shown to its owner and the admin (the caller decides; the server checks): a
 * split button (Run now, or a Dry run that blocks the orders) and the latest request's state, polled while it waits
 * for the claude-trader service or runs: "Requested 10:31 AM ET · picked up 10:31 AM · done 10:35 AM". The button is
 * disabled, with the reason, for a bot that is not ACTIVE or that no claude-trader service serves.
 */
export function RunNow({ handle, className }: { handle: string; className?: string }) {
  const { data: me } = useMe()
  const requests = useRunRequests(handle)
  const { start, pending } = useStartRun()
  const tipId = useId()
  const latest = requests.data?.requests?.[0] ?? null
  const open = isOpenRequest(latest)
  const busy = pending || open
  // "today", the trader's last visit and a long wait are judged against when the list was fetched (no clock read
  // while rendering)
  const blocked = runNowBlocked(handle, requests.data, requests.dataUpdatedAt)
  const v = latest ? runRequestView(latest, requests.dataUpdatedAt, me?.handle) : null
  const tip = runNowTip(handle)
  return (
    <div role="group" aria-label={`Run @${handle} now`} className={cx('space-y-1.5', className)}>
      <div className="flex flex-wrap items-center gap-2">
        <div className="inline-flex">
          <Button
            variant="primary"
            size="sm"
            className="rounded-r-none"
            title={busy ? 'A run is on its way; wait until the trader reports back.' : (blocked ?? tip)}
            aria-describedby={tipId}
            disabled={busy || blocked !== null}
            onClick={() => start(handle, false)}
          >
            <IconBolt size={16} /> Run now
          </Button>
          <Popover
            label={`Run options for @${handle}`}
            align="start"
            className="w-64"
            renderTrigger={(props) => (
              <Button
                variant="primary"
                size="sm"
                className="rounded-l-none border-l border-white/30 px-2"
                aria-label="More run options"
                title="More run options"
                disabled={busy || blocked !== null}
                {...props}
              >
                <IconChevronDown size={16} />
              </Button>
            )}
          >
            {(close) => (
              <>
                <MenuItem onClick={() => (close(), start(handle, false))}>Run now</MenuItem>
                <MenuItem onClick={() => (close(), start(handle, true))}>Dry run (no orders)</MenuItem>
                <p className="border-t border-divider px-3 pt-2 pb-1 text-caption text-text-3">
                  {tip} A dry run blocks the order tools: the bot only says what it would trade.
                </p>
              </>
            )}
          </Popover>
        </div>
        {v ? (
          <Pill tone={v.tone} className="tracking-normal normal-case!">
            {v.label}
          </Pill>
        ) : null}
      </div>
      <p id={tipId} className="sr-only">
        {tip}
      </p>
      <div role="status" aria-live="polite" className="text-caption text-text-2">
        {v ? (
          <>
            <p>{v.line}</p>
            {v.note ? <p className="mt-0.5 rounded-sm bg-bg-2 px-2 py-1 break-words text-text-2">{v.note}</p> : null}
          </>
        ) : requests.isError ? (
          <p className="text-text-3">Run requests are unavailable right now.</p>
        ) : null}
        {blocked && !open ? <p className="mt-0.5 text-text-3">{blocked}</p> : null}
      </div>
    </div>
  )
}

/**
 * Run now and Dry run as items of a bot's ⋯ menu (Admin → Players), disabled with the reason while the bot cannot be
 * run (see {@link RunNow}) or a run is on its way. Rendered only while the menu is open, so the list is fetched then.
 */
export function RunNowMenuItems({ handle, busy, close, onStart }: { handle: string; busy: boolean; close: () => void; onStart: (dryRun: boolean) => void }) {
  const requests = useRunRequests(handle)
  const blocked = runNowBlocked(handle, requests.data, requests.dataUpdatedAt)
  const open = isOpenRequest(requests.data?.requests?.[0])
  const disabled = busy || requests.isPending || open || blocked !== null
  const why = blocked ?? (open ? 'A run is on its way; follow it under Admin → Bots.' : null)
  return (
    <>
      <MenuItem disabled={disabled} onClick={() => (close(), onStart(false))}>
        Run now
      </MenuItem>
      <MenuItem disabled={disabled} onClick={() => (close(), onStart(true))}>
        Dry run (no orders)
      </MenuItem>
      {why ? <p className="max-w-64 px-3 pb-1 text-caption text-text-3">{why}</p> : null}
    </>
  )
}
