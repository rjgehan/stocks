import { Link } from '@tanstack/react-router'
import { useAgentRunSummary } from '@/lib/api/hooks/agents'
import { useMe } from '@/lib/api/hooks/identity'
import { usePlayerProfile } from '@/lib/api/hooks/league'
import { formatDateTimeEt } from '@/lib/format/time'
import { Pill } from '@/components/ui/Pill'
import { Skeleton } from '@/components/ui/Skeleton'
import { IconActivity } from '@/components/ui/icons'
import { runSummaryLine, statusLabel, statusTone } from './agentView'
import { RunNow } from './RunNow'
import { canRunBot } from './runNowView'

const runs = (n: number) => `${n} run${n === 1 ? '' : 's'}`

/**
 * A bot's public "Bot activity" card (§20.6): runs in the last 7 days and in total, the last run's time and status.
 * No cost and no error message (those stay with the admin, Admin → Bots). The bot's owner and the admin also get
 * Run now (§20.9) and the link to the bot's manual console (§20.8: the only way in for an owner who is not the admin);
 * the profile query is the page's own, so it costs no request.
 */
export function BotActivityCard({ handle }: { handle: string }) {
  const q = useAgentRunSummary(handle)
  const s = q.data
  const { data: me } = useMe()
  const owner = usePlayerProfile(handle).data?.ownerHandle
  return (
    <section aria-label="Bot activity" className="rounded-lg border border-divider bg-bg-1 p-4">
      <h2 className="flex items-center gap-2 text-section font-semibold">
        <IconActivity size={18} className="text-ai" /> Bot activity
      </h2>
      {q.isPending ? (
        <Skeleton className="mt-2 h-10 w-full" />
      ) : q.isError || !s ? (
        <p className="mt-1 text-caption text-text-3">Run activity is unavailable right now.</p>
      ) : (
        <>
          <dl className="mt-2 grid grid-cols-2 gap-3">
            <div>
              <dt className="text-micro text-text-3 uppercase">Last 7 days</dt>
              <dd className="text-section font-semibold">{runs(s.runsLast7Days)}</dd>
            </div>
            <div>
              <dt className="text-micro text-text-3 uppercase">All time</dt>
              <dd className="text-section font-semibold">{runs(s.runs)}</dd>
            </div>
          </dl>
          <div className="mt-3 border-t border-divider pt-2 text-caption text-text-2">
            {s.lastStatus ? (
              <>
                <p className="flex flex-wrap items-center gap-2">
                  <span className="text-text-3">Last run</span>
                  <span className="font-semibold text-text-1">{formatDateTimeEt(s.lastRunAt)}</span>
                  <Pill tone={statusTone(s.lastStatus)}>{statusLabel(s.lastStatus)}</Pill>
                </p>
                <p className="mt-1">{runSummaryLine(s)}</p>
              </>
            ) : (
              <p>No runs reported yet. The league&apos;s bots trade on a schedule on trading days.</p>
            )}
          </div>
        </>
      )}
      {canRunBot(me, owner) ? (
        <div className="mt-3 border-t border-divider pt-3">
          <RunNow handle={handle} />
          <Link
            to="/bots/$handle"
            params={{ handle }}
            title={`Trade for @${handle} by hand: a prompt for any AI, its reply pasted back, then review and place`}
            className="mt-2 inline-flex min-h-11 items-center text-caption font-semibold text-accent hover:text-accent-hover tablet:min-h-0"
          >
            Open console
          </Link>
        </div>
      ) : null}
    </section>
  )
}
