import { useState } from 'react'
import { useQueries } from '@tanstack/react-query'
import { describeError, errorBody } from '@/lib/api/errors'
import { runRequestsQuery, useRunAll } from '@/lib/api/hooks/agents'
import type { RunAllResult } from '@/lib/api/types'
import { Button } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { Pill } from '@/components/ui/Pill'
import { MenuItem, Popover } from '@/components/ui/Popover'
import { IconBolt, IconChevronDown } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'
import { toast } from '@/features/notifications/toastStore'
import { type RunAllBot, botCount, runAllHeadline, runAllItemView, runAllPlan, runAllToast } from './runNowView'

/**
 * "Run all" (§20.9) next to a list of bots (Admin → Bots, Admin → Players): a split button (Run all, or Dry run all
 * that blocks the orders), a confirmation naming the bots it would run and the ones it would not (and why, by the
 * per-bot button's rules), then `POST /me/bots/run-requests` and a per-bot summary. The bots' Run now lines on the page
 * follow their new requests. The server judges every bot again, so the summary is the truth.
 */
export function RunAll({ bots, className }: { bots: RunAllBot[]; className?: string }) {
  const [mode, setMode] = useState<'run' | 'dry' | null>(null)
  const [result, setResult] = useState<RunAllResult | null>(null)
  const runAll = useRunAll()
  const open = mode !== null
  const dry = mode === 'dry'
  // the bots' Run now states, shared with their buttons (cached on Admin → Bots; fetched when the dialog opens)
  const states = useQueries({
    queries: bots.map((b) => ({ ...runRequestsQuery(b.handle), enabled: open && (!b.status || b.status === 'ACTIVE') })),
  })
  const plan = runAllPlan(bots, states)
  const close = () => {
    setMode(null)
    runAll.reset()
  }
  const confirm = () =>
    runAll.mutate(
      { dryRun: dry },
      {
        onSuccess: (r) => {
          setMode(null)
          setResult(r)
          toast({ tone: r.requested ? 'neutral' : 'warn', ...runAllToast(r) })
        },
      },
    )
  const error = runAll.isError
    ? (() => {
        const d = describeError(runAll.error)
        return errorBody(d) ?? d.title
      })()
    : null
  const what = dry ? 'Dry run' : 'Run'
  return (
    <div className={cx('space-y-2', className)}>
      <div role="group" aria-label="Run all bots" className="inline-flex">
        <Button
          variant="secondary"
          size="sm"
          className="rounded-r-none"
          title="Runs every bot a claude-trader service serves, each with its normal check-in, within about a minute."
          disabled={runAll.isPending}
          onClick={() => setMode('run')}
        >
          <IconBolt size={16} /> Run all
        </Button>
        <Popover
          label="Run all options"
          className="w-64"
          renderTrigger={(props) => (
            <Button variant="secondary" size="sm" className="rounded-l-none border-l-0 px-2" aria-label="More Run all options" title="More Run all options" disabled={runAll.isPending} {...props}>
              <IconChevronDown size={16} />
            </Button>
          )}
        >
          {(closeMenu) => (
            <>
              <MenuItem onClick={() => (closeMenu(), setMode('run'))}>Run all</MenuItem>
              <MenuItem onClick={() => (closeMenu(), setMode('dry'))}>Dry run all (no orders)</MenuItem>
              <p className="border-t border-divider px-3 pt-2 pb-1 text-caption text-text-3">A dry run blocks the order tools: each bot only says what it would trade.</p>
            </>
          )}
        </Popover>
      </div>

      <ConfirmDialog
        open={open}
        onClose={close}
        onConfirm={confirm}
        title={dry ? 'Dry run all bots?' : 'Run all bots now?'}
        confirmLabel={plan.run.length ? `${what} ${botCount(plan.run.length)}` : `${what} all`}
        confirmDisabled={plan.checking || plan.run.length + plan.unknown.length === 0}
        pending={runAll.isPending}
        error={error}
      >
        {plan.checking ? (
          <p>Checking which bots a claude-trader service serves…</p>
        ) : (
          <>
            {plan.run.length ? (
              <>
                <p>
                  Asks the claude-trader service for one check-in of each of these bots, within about a minute.{' '}
                  {dry ? 'A dry run blocks the order tools: each bot only says what it would trade.' : 'They trade for real.'}
                </p>
                <ul aria-label="Bots to run" className="flex flex-wrap gap-1.5">
                  {plan.run.map((h) => (
                    <li key={h} className="rounded-sm bg-bg-2 px-2 py-0.5 font-semibold text-text-1">
                      @{h}
                    </li>
                  ))}
                </ul>
              </>
            ) : (
              <p>No bot can be run right now.</p>
            )}
            {plan.unknown.length ? <p className="text-caption">Not checked (the app decides when it asks): {plan.unknown.map((h) => `@${h}`).join(', ')}</p> : null}
            {plan.skipped.length ? (
              <div>
                <p className="text-caption font-semibold text-text-2">Not run</p>
                <ul aria-label="Bots not run" className="mt-1 space-y-0.5 text-caption">
                  {plan.skipped.map((s) => (
                    <li key={s.handle}>
                      @{s.handle}: {s.why}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </>
        )}
      </ConfirmDialog>

      {result ? (
        <section role="status" aria-label="Run all result" className="rounded-md border border-divider bg-bg-1 p-3 text-caption">
          <div className="flex items-start gap-2">
            <p className="flex-1 font-semibold text-text-1">{runAllHeadline(result)}</p>
            <button type="button" onClick={() => setResult(null)} className="-my-1 min-h-11 shrink-0 px-1 font-semibold text-accent hover:text-accent-hover tablet:min-h-0">
              Dismiss
            </button>
          </div>
          <ul className="mt-1 divide-y divide-divider">
            {(result.results ?? []).map((i) => {
              const v = runAllItemView(i)
              return (
                <li key={i.handle} className="py-1.5">
                  <p className="flex flex-wrap items-center gap-2">
                    <span className="font-semibold">@{i.handle}</span>
                    <Pill tone={v.tone} className="tracking-normal normal-case!">
                      {v.label}
                    </Pill>
                  </p>
                  {v.note ? <p className="mt-0.5 break-words text-text-2">{v.note}</p> : null}
                </li>
              )
            })}
          </ul>
        </section>
      ) : null}
    </div>
  )
}
