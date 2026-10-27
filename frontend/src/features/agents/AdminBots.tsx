import { useMemo, useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useAdminAgentRuns, useAdminBots } from '@/lib/api/hooks/agents'
import type { AdminBot, AgentRun, AgentRunStatus } from '@/lib/api/types'
import { formatDateEt, formatDateTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { SelectField } from '@/components/ui/Field'
import { Pill } from '@/components/ui/Pill'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { IconRobot } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'
import { AiChip, Avatar } from '@/features/league/PlayerBadge'
import { RUN_STATUSES, formatTokens, formatUsd, runCounts, spendMeter, statusLabel, statusTone } from './agentView'
import { PlanUsagePanel } from './PlanUsagePanel'
import { RunAll } from './RunAll'
import { RunNow } from './RunNow'

const METER_TONE = { ok: 'bg-accent', warn: 'bg-warn', over: 'bg-down', none: 'bg-bg-3' } as const

/** Today's spend against the runner's daily cap: a meter plus the numbers in text (never color alone). */
function SpendMeterBar({ bot }: { bot: AdminBot }) {
  const m = spendMeter(bot.today?.spentUsd, bot.today?.capUsd)
  return (
    <div>
      <div className="flex items-baseline justify-between gap-2 text-caption">
        <span className="text-text-2">Spent today</span>
        <span className={cx('font-semibold', m.tone === 'over' ? 'text-down' : m.tone === 'warn' ? 'text-warn' : 'text-text-1')}>
          {m.label}
          {m.tone === 'over' ? ' · cap reached' : ''}
        </span>
      </div>
      <div
        role="meter"
        aria-label={`Spend today for ${bot.player?.handle ?? 'bot'}`}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(m.pct)}
        aria-valuetext={m.label}
        className="mt-1 h-2 overflow-hidden rounded-full bg-bg-3"
      >
        <div className={cx('h-full rounded-full', METER_TONE[m.tone])} style={{ width: `${m.pct}%` }} />
      </div>
    </div>
  )
}

/** The last 14 ET days: spend per day as bars against the day's cap (a dashed line), runs in the tooltip. */
function SpendChart({ bot }: { bot: AdminBot }) {
  const days = bot.days ?? []
  const caps = days.map((d) => Number(d.capUsd ?? 0)).filter((c) => c > 0)
  const cap = caps.length ? Math.max(...caps) : 0
  const top = Math.max(cap, ...days.map((d) => Number(d.spentUsd ?? 0)), 0.01)
  return (
    <figure className="mt-3">
      <figcaption className="mb-1 flex justify-between text-micro text-text-3">
        <span>Spend, last 14 days (ET)</span>
        {cap ? <span>cap {formatUsd(String(cap))}</span> : null}
      </figcaption>
      <div className="relative flex h-16 items-end gap-0.5" role="img" aria-label={`Daily spend of ${bot.player?.handle ?? 'the bot'} over the last 14 days`}>
        {cap ? <div className="pointer-events-none absolute inset-x-0 border-t border-dashed border-warn/70" style={{ bottom: `${(cap / top) * 100}%` }} aria-hidden="true" /> : null}
        {days.map((d) => {
          const v = Number(d.spentUsd ?? 0)
          const over = d.capUsd != null && v >= Number(d.capUsd) && v > 0
          return (
            <div
              key={d.date}
              title={`${formatDateEt(d.date)}: ${formatUsd(d.spentUsd)} · ${d.runs} run${d.runs === 1 ? '' : 's'}`}
              className={cx('flex-1 rounded-t-sm', over ? 'bg-down' : v > 0 ? 'bg-accent' : 'bg-bg-3')}
              style={{ height: `${Math.max(v > 0 ? 4 : 2, (v / top) * 100)}%` }}
            />
          )
        })}
      </div>
    </figure>
  )
}

function ToolBreakdown({ bot }: { bot: AdminBot }) {
  const tools = bot.tools ?? []
  if (!tools.length) return <p className="mt-3 text-caption text-text-3">No tool calls in the last 7 days.</p>
  return (
    <details className="mt-3 rounded-md bg-bg-2 px-3 py-2">
      <summary className="flex min-h-11 cursor-pointer items-center text-caption font-semibold text-text-1 tablet:min-h-0">
        Tool calls, last 7 days ({tools.reduce((n, t) => n + t.calls, 0)})
      </summary>
      <table className="mt-2 w-full text-caption">
        <thead>
          <tr className="text-left text-micro text-text-3 uppercase">
            <th className="py-1 font-semibold">Tool</th>
            <th className="py-1 text-right font-semibold">Calls</th>
            <th className="py-1 text-right font-semibold">Errors</th>
          </tr>
        </thead>
        <tbody>
          {tools.map((t) => (
            <tr key={t.tool} className="border-t border-divider align-top">
              <td className="py-1">
                <span className="font-mono">{t.tool}</span>
                {t.errors ? (
                  <span className="block text-micro text-text-3">
                    {(t.codes ?? [])
                      .filter((c) => c.code !== 'OK')
                      .map((c) => `${c.code} × ${c.calls}`)
                      .join(', ')}
                  </span>
                ) : null}
              </td>
              <td className="py-1 text-right">{t.calls}</td>
              <td className={cx('py-1 text-right', t.errors ? 'font-semibold text-warn' : 'text-text-3')}>{t.errors}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </details>
  )
}

function RunLine({ label, run }: { label: string; run: AgentRun | null | undefined }) {
  if (!run) return null
  return (
    <div className="text-caption">
      <p className="flex flex-wrap items-center gap-2">
        <span className="text-text-3">{label}</span>
        <span className="font-semibold">{formatDateTimeEt(run.startedAt)}</span>
        <Pill tone={statusTone(run.status)} className="tracking-normal normal-case!">
          {statusLabel(run.status)}
        </Pill>
      </p>
      <p className="mt-0.5 text-text-2">
        {runCounts(run)} · {formatUsd(run.costUsd)}
      </p>
      {run.message && run.status !== 'COMPLETED' ? <p className="mt-0.5 rounded-sm bg-bg-2 px-2 py-1 break-words text-text-2">{run.message}</p> : null}
    </div>
  )
}

function BotCard({ bot }: { bot: AdminBot }) {
  const p = bot.player
  const lastProblem = bot.lastProblem && bot.lastProblem.runId !== bot.lastRun?.runId ? bot.lastProblem : null
  return (
    <li aria-label={`Bot ${p?.handle ?? ''}`} className="rounded-lg border border-divider bg-bg-1 p-4">
      <div className="flex items-center gap-3">
        <Avatar player={p} size={40} />
        <div className="min-w-0 flex-1">
          <p className="flex flex-wrap items-center gap-2">
            <Link to="/players/$handle" params={{ handle: p?.handle ?? '' }} className="font-semibold hover:text-accent">
              {p?.displayName ?? p?.handle}
            </Link>
            <AiChip model={p?.aiModel} />
            {bot.status !== 'ACTIVE' ? <Pill tone="warn">{bot.status}</Pill> : null}
          </p>
          <p className="text-caption text-text-3">
            @{p?.handle}
            {bot.lastRun?.model ? ` · ${bot.lastRun.model}` : ''}
            {bot.today ? ` · ${bot.today.runs} run${bot.today.runs === 1 ? '' : 's'} today` : ''}
          </p>
        </div>
      </div>
      {p?.handle && bot.status === 'ACTIVE' ? <RunNow handle={p.handle} className="mt-3" /> : null}
      <div className="mt-3 space-y-3">
        <SpendMeterBar bot={bot} />
        {bot.lastRun ? <RunLine label="Last run" run={bot.lastRun} /> : <p className="text-caption text-text-3">No runs reported yet.</p>}
        <RunLine label="Last problem" run={lastProblem} />
      </div>
      <SpendChart bot={bot} />
      <ToolBreakdown bot={bot} />
      {p?.handle ? (
        <Link to="/bots/$handle" params={{ handle: p.handle }} className="mt-3 inline-flex min-h-11 items-center text-caption font-semibold text-accent hover:text-accent-hover tablet:min-h-0">
          Open console
        </Link>
      ) : null}
    </li>
  )
}

function RunRow({ r }: { r: AgentRun }) {
  return (
    <li className="px-4 py-2.5 text-table">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="font-semibold">@{r.player?.handle}</span>
        <Pill tone={statusTone(r.status)} className="tracking-normal normal-case!">
          {statusLabel(r.status)}
        </Pill>
        <span className="text-caption text-text-3">{r.trigger === 'SCHEDULE' ? `schedule ${r.slot ?? ''}` : 'manual'}</span>
        <span className="ml-auto text-caption text-text-3">{formatDateTimeEt(r.startedAt)}</span>
      </div>
      <p className="mt-0.5 text-caption text-text-2">
        {runCounts(r)} · tokens in{' '}
        {formatTokens((r.tokens?.input ?? 0) + (r.tokens?.cacheRead ?? 0) + (r.tokens?.cacheWrite ?? 0))} / out {formatTokens(r.tokens?.output)} · {formatUsd(r.costUsd)}
      </p>
      {r.message ? <p className="mt-0.5 text-caption break-words text-text-3">{r.message}</p> : null}
    </li>
  )
}

/**
 * Admin → Bots (§20.6, §21.3): Run all (§20.9), one card per AI player (Run now, §20.9; last run, last problem, today's
 * spend against the cap, a 14-day spend chart, the 7-day tool-call breakdown), then the reported runs with filters.
 */
export function AdminBots() {
  const bots = useAdminBots()
  const [handle, setHandle] = useState('')
  const [status, setStatus] = useState('')
  const filter = useMemo(() => ({ ...(handle ? { handle } : {}), ...(status ? { status } : {}) }), [handle, status])
  const runs = useAdminAgentRuns(filter)
  const rows = (runs.data?.pages ?? []).flatMap((p) => p.items ?? [])
  const list = bots.data ?? []
  // Run all (§20.9): the listed bots, as the server sees them (a DISABLED bot is not even listed)
  const runnable = useMemo(() => (bots.data ?? []).flatMap((b) => (b.player?.handle && b.status !== 'DISABLED' ? [{ handle: b.player.handle, status: b.status }] : [])), [bots.data])
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start gap-3">
        <p className="min-w-0 flex-1 basis-64 text-caption text-text-2">
          The agent runner reports every run with the bot&apos;s own key. Spend is the runner&apos;s own count against its daily cap (AGENT_DAILY_USD_CAP).
        </p>
        {runnable.length ? <RunAll bots={runnable} className="w-full tablet:w-auto tablet:max-w-md" /> : null}
      </div>
      <PlanUsagePanel />
      {bots.isPending ? (
        <div className="rounded-lg border border-divider bg-bg-1 px-4">
          <SkeletonRows rows={3} cols={3} />
        </div>
      ) : bots.isError ? (
        <EmptyState title="The bots are unavailable right now" />
      ) : list.length === 0 ? (
        <EmptyState icon={<IconRobot size={28} />} title="No AI players yet" body="Create one under Players → Add player → AI bot." />
      ) : (
        <ul aria-label="Bots" className="grid gap-4 tablet:grid-cols-2">
          {list.map((b) => (
            <BotCard key={b.player?.playerId ?? b.player?.handle} bot={b} />
          ))}
        </ul>
      )}

      <section aria-label="Runs" className="rounded-lg border border-divider bg-bg-1">
        <div className="flex flex-wrap items-end gap-3 px-4 pt-3">
          <h2 className="mr-auto text-section font-semibold">Runs</h2>
          <SelectField
            label="Bot"
            value={handle}
            onChange={(e) => setHandle(e.target.value)}
            options={[{ value: '', label: 'All bots' }, ...list.map((b) => ({ value: b.player?.handle ?? '', label: `@${b.player?.handle ?? ''}` }))]}
          />
          <SelectField
            label="Status"
            value={status}
            onChange={(e) => setStatus(e.target.value)}
            options={[{ value: '', label: 'All statuses' }, ...RUN_STATUSES.map((s: AgentRunStatus) => ({ value: s, label: statusLabel(s) }))]}
          />
        </div>
        {runs.isPending ? (
          <div className="px-4">
            <SkeletonRows rows={4} cols={4} />
          </div>
        ) : runs.isError ? (
          <EmptyState title="Runs are unavailable right now" />
        ) : rows.length === 0 ? (
          <EmptyState title="No runs match" body="Runs appear after the agent runner's first scheduled slot." />
        ) : (
          <ul aria-label="Agent runs" className="mt-2 divide-y divide-divider">
            {rows.map((r) => (
              <RunRow key={r.runId} r={r} />
            ))}
          </ul>
        )}
        {runs.hasNextPage ? (
          <div className="flex justify-center border-t border-divider p-3">
            <Button size="sm" onClick={() => void runs.fetchNextPage()} disabled={runs.isFetchingNextPage}>
              Load more
            </Button>
          </div>
        ) : null}
      </section>
    </div>
  )
}
