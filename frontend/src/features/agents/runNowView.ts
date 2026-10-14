/**
 * "Run now" (§20.9) as the button shows it: who may run a bot, whether anything would run it, and one timeline line
 * per request state.
 */
import type { PillTone } from '@/components/ui/Pill'
import type { Me, RunRequest, RunRequestList, RunRequestStatus } from '@/lib/api/types'
import { formatDateTimeEt, formatTimeEt } from '@/lib/format/time'

/** The tooltip of the button. */
export const runNowTip = (handle: string) => `Runs @${handle}'s normal check-in on the server (the claude-trader service) within about a minute.`

export const RUN_REQUEST_LABEL: Record<RunRequestStatus, string> = {
  PENDING: 'Waiting',
  CLAIMED: 'Running',
  DONE: 'Done',
  FAILED: 'Failed',
  EXPIRED: 'Expired',
}

export const RUN_REQUEST_TONE: Record<RunRequestStatus, PillTone> = {
  PENDING: 'neutral',
  CLAIMED: 'accent',
  DONE: 'up',
  FAILED: 'down',
  EXPIRED: 'warn',
}

export const EXPIRED_NOTE = 'Expired: the trader did not pick it up — is the claude-trader service running?'

/** The claude-trader service asks every minute for each bot it serves; not within this long, nothing serves the bot. */
export const TRADER_SEEN_MS = 5 * 60_000
/** A request still waiting this long gets a hint about the trader's agents file. */
export const PENDING_HINT_MS = 2 * 60_000

/** The server's clock when it answered the list (`asOf`), else `fallback`: ages are measured against it. */
export function serverNow(list: RunRequestList | null | undefined, fallback: number): number {
  const t = Date.parse(list?.asOf ?? '')
  return Number.isFinite(t) ? t : fallback
}

/**
 * Why the bot cannot be run from here (the button is disabled and says so), or null: it is not ACTIVE, or no
 * claude-trader service has asked for its requests lately (it is not in the trader's agents file, e.g. a bot of the
 * agent runner or a manual bot, or the service is down). Judged at `now`, the server's clock when the list was fetched
 * ({@link serverNow}).
 */
export function runNowBlocked(handle: string, list: RunRequestList | null | undefined, now: number): string | null {
  if (!list) return null
  if (list.botStatus && list.botStatus !== 'ACTIVE') return `@${handle} is ${list.botStatus}: it cannot trade, so it cannot be run.`
  const seen = Date.parse(list.traderSeenAt ?? '')
  if (Number.isFinite(seen) && now - seen <= TRADER_SEEN_MS) return null
  return `No claude-trader service has asked for @${handle}'s runs in the last few minutes (it asks every minute for each bot in its agents file), so nothing would pick a run up.`
}

/** The bot's owner or an ACTIVE admin sees the button, as the server decides (it checks again on every request). */
export function canRunBot(me: Me | null | undefined, ownerHandle: string | null | undefined): boolean {
  if (!me?.handle) return false
  if (ownerHandle && ownerHandle === me.handle) return true
  return me.role === 'ADMIN' && me.status === 'ACTIVE'
}

/** PENDING or CLAIMED: a run is on its way, so the button waits (one open request per bot). */
export const isOpenRequest = (r: RunRequest | null | undefined): boolean => r?.status === 'PENDING' || r?.status === 'CLAIMED'

const etDay = (ms: number) => new Date(ms).toLocaleDateString('en-CA', { timeZone: 'America/New_York' })
/** "10:31 AM": the zone is written once, after the request time. */
const clock = (ts: string | null | undefined) => formatTimeEt(ts).replace(/ ET$/, '')

export interface RunRequestView {
  label: string
  tone: PillTone
  /** "Requested 10:31 AM ET · picked up 10:31 AM · done 10:35 AM" */
  line: string
  /** The trader's report, why it expired, or what happens next. */
  note: string | null
}

/** One request as a pill, its timeline and a note. `myHandle` drops "by @me" from the line. */
export function runRequestView(r: RunRequest, now: number, myHandle?: string | null): RunRequestView {
  const status: RunRequestStatus = r.status ?? 'PENDING'
  const requestedMs = Date.parse(r.requestedAt ?? '')
  const requested = Number.isFinite(requestedMs) && etDay(requestedMs) !== etDay(now) ? formatDateTimeEt(r.requestedAt) : formatTimeEt(r.requestedAt)
  const by = r.requestedBy && r.requestedBy !== myHandle ? ` by @${r.requestedBy}` : ''
  const parts = [`${r.dryRun ? 'Dry run requested' : 'Requested'} ${requested}${by}`]
  if (r.claimedAt) parts.push(`picked up ${clock(r.claimedAt)}`)
  let note: string | null = null
  switch (status) {
    case 'PENDING':
      parts.push('waiting for the trader')
      note =
        now - requestedMs > PENDING_HINT_MS
          ? `Not picked up yet: is @${r.handle} in the trader's agents file, and is the claude-trader service running?`
          : 'The trader picks it up within about a minute.'
      break
    case 'CLAIMED':
      parts.push('running')
      break
    case 'DONE':
      parts.push(`done ${clock(r.finishedAt)}`)
      note = r.message
      break
    case 'FAILED':
      parts.push(`failed ${clock(r.finishedAt)}`)
      note = r.message ?? 'The trader reported a failure.'
      break
    case 'EXPIRED':
      parts.push(`expired ${clock(r.finishedAt)}`)
      // the server says why: nobody picked it up in 30 minutes, or the bot was frozen meanwhile
      note = r.message ?? EXPIRED_NOTE
      break
  }
  return { label: RUN_REQUEST_LABEL[status], tone: RUN_REQUEST_TONE[status], line: parts.join(' · '), note }
}
