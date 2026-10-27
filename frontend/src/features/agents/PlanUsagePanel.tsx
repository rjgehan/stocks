import { usePlanUsage } from '@/lib/api/hooks/agents'
import { formatDateEt, formatDateTimeEt } from '@/lib/format/time'
import { Skeleton } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { formatTokens } from './agentView'
import { type Change, type Meter, type Round, changeText, days, latest, pct, rounds } from './planUsageView'

const ROUNDS_SHOWN = 8

const usd = (v: number): string => `~$${v.toFixed(2)}`

function tone(percent: number | null): string {
  if (percent == null) return 'bg-bg-3'
  return percent >= 90 ? 'bg-down' : percent >= 75 ? 'bg-warn' : 'bg-accent'
}

/** One plan meter: the percent in text and as a bar (never colour alone), and when it resets. */
function MeterBar({ label, meter }: { label: string; meter: Meter }) {
  const p = meter.percent
  return (
    <div className="min-w-0 flex-1 basis-48">
      <div className="flex items-baseline justify-between gap-2">
        <span className="text-caption text-text-2">{label}</span>
        <span className="text-section font-semibold tabular-nums">{pct(p)}</span>
      </div>
      <div className="mt-1 h-2 rounded-full bg-bg-3" role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={p ?? undefined}>
        <div className={cx('h-full rounded-full', tone(p))} style={{ width: `${Math.min(100, p ?? 0)}%` }} />
      </div>
      <span className="mt-0.5 block text-micro text-text-3">{meter.resetsAt ? `resets ${formatDateTimeEt(meter.resetsAt)}` : ' '}</span>
    </div>
  )
}

function ChangeCell({ label, c, running }: { label: string; c: Change; running?: boolean }) {
  return (
    <span className="whitespace-nowrap tabular-nums">
      <span className="text-text-3">{label} </span>
      <span className={cx(c.delta != null && c.delta > 0 && 'text-text-1')}>{changeText(c, running)}</span>
    </span>
  )
}

function RoundItem({ r }: { r: Round }) {
  const running = r.endedAt == null
  return (
    <li>
      <details className="group">
        <summary className="flex min-h-11 cursor-pointer flex-wrap items-center gap-x-4 gap-y-1 py-2 text-caption tablet:min-h-0">
          <span className="w-36 shrink-0 font-semibold text-text-1">{formatDateTimeEt(r.startedAt)}</span>
          <span className="w-20 shrink-0 text-text-2">
            {r.checkIns.length} check-in{r.checkIns.length === 1 ? '' : 's'}
          </span>
          <ChangeCell label="5h" c={r.session} running={running} />
          <ChangeCell label="week" c={r.weekly} running={running} />
          {r.costUsd ? <span className="ml-auto text-text-3 tabular-nums">{usd(r.costUsd)}</span> : null}
        </summary>
        {r.checkIns.length ? (
          <ul className="mb-2 space-y-1 rounded-md bg-bg-2 px-3 py-2 text-micro">
            {r.checkIns.map((c) => (
              <li key={c.runRef} className="flex flex-wrap items-center gap-x-4 gap-y-0.5">
                <span className="w-36 shrink-0 font-semibold text-text-1">@{c.bot}</span>
                <ChangeCell label="5h" c={c.session} running={running} />
                <ChangeCell label="week" c={c.weekly} running={running} />
                {c.tokens ? (
                  <span className="text-text-3 tabular-nums">
                    tokens in {formatTokens(c.tokens.input + c.tokens.cacheRead + c.tokens.cacheWrite)} / out {formatTokens(c.tokens.output)}
                  </span>
                ) : null}
                {c.costUsd != null ? <span className="ml-auto text-text-3 tabular-nums">{usd(c.costUsd)}</span> : null}
              </li>
            ))}
          </ul>
        ) : null}
      </details>
    </li>
  )
}

/**
 * Claude plan (Admin → Bots): the owner's plan meters as the claude-trader reads them (Claude Code's /usage) before
 * and after every check-in and round: the latest reading, today's use of the weekly limit, each round with its bots'
 * check-ins, and the last days. Check-ins that run together share the meters, so their own before/after overlap; the
 * round's numbers are the whole.
 */
export function PlanUsagePanel() {
  const q = usePlanUsage(7)
  const rows = q.data ?? []
  const now = latest(rows)
  const allRounds = rounds(rows)
  const allDays = days(rows)
  const today = allDays[0]
  return (
    <section aria-label="Claude plan" className="space-y-3 rounded-lg border border-divider bg-bg-1 p-4">
      <div className="flex flex-wrap items-baseline gap-2">
        <h2 className="text-section font-semibold">Claude plan</h2>
        {now ? <span className="text-micro text-text-3">as of {formatDateTimeEt(now.at)}</span> : null}
      </div>
      {q.isPending ? (
        <Skeleton className="h-16 w-full" />
      ) : q.isError ? (
        <p className="text-caption text-text-3">Plan usage is unavailable right now.</p>
      ) : !now ? (
        <p className="text-caption text-text-3">No readings yet: the Claude trader sends them around each check-in.</p>
      ) : (
        <>
          <div className="flex flex-wrap gap-4">
            <MeterBar label="5-hour session" meter={now.session} />
            <MeterBar label="Weekly (all models)" meter={now.weekly} />
          </div>
          {today ? (
            <p className="text-caption text-text-2">
              {formatDateEt(today.date)}: weekly {changeText(today.weekly)} · {today.checkIns} check-in{today.checkIns === 1 ? '' : 's'}
              {today.costUsd ? ` · ${usd(today.costUsd)} at API prices (not billed)` : ''}
            </p>
          ) : null}
          {allRounds.length ? (
            <div>
              <h3 className="text-caption font-semibold text-text-2">Rounds</h3>
              <ul aria-label="Rounds" className="divide-y divide-divider">
                {allRounds.slice(0, ROUNDS_SHOWN).map((r) => (
                  <RoundItem key={r.ref} r={r} />
                ))}
              </ul>
            </div>
          ) : null}
          {allDays.length > 1 ? (
            <div>
              <h3 className="text-caption font-semibold text-text-2">Days</h3>
              <ul aria-label="Days" className="divide-y divide-divider text-caption">
                {allDays.map((d) => (
                  <li key={d.date} className="flex flex-wrap items-center gap-x-4 py-1.5">
                    <span className="w-36 shrink-0 font-semibold text-text-1">{formatDateEt(d.date)}</span>
                    <ChangeCell label="week" c={d.weekly} />
                    <span className="text-text-2">
                      {d.checkIns} check-in{d.checkIns === 1 ? '' : 's'}
                    </span>
                    {d.costUsd ? <span className="ml-auto text-text-3 tabular-nums">{usd(d.costUsd)}</span> : null}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </>
      )}
    </section>
  )
}
