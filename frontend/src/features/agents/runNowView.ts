/**
 * "Run now" (§20.9) as the button shows it: who may run a bot, whether anything would run it, and one timeline line
 * per request state; "Run all" as its confirmation and its per-bot summary show it.
 */
import type { PillTone } from '@/components/ui/Pill'
import type { Me, RunAllItem, RunAllOutcome, RunAllResult, RunRequest, RunRequestList, RunRequestStatus } from '@/lib/api/types'
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

// ---------------------------------------------------------------------------------------------------- Run all

/** A bot Run all may ask for: its handle, and its status when the page knows it (the admin lists do). */
export interface RunAllBot {
  handle: string
  status?: string | null
}

/** What Run all's confirmation shows: which bots it would run, which not and why, judged like the per-bot button. */
export interface RunAllPlan {
  run: string[]
  skipped: { handle: string; why: string }[]
  /** Bots whose Run now state could not be read: the server decides when it asks. */
  unknown: string[]
  /** Some bot's Run now state is still loading. */
  checking: boolean
}

/** One bot's Run now state as Run all's confirmation reads it (a `useQueries` result of `runRequestsQuery`). */
export interface RunAllState {
  data?: RunRequestList
  dataUpdatedAt: number
  isPending: boolean
  isError: boolean
}

/**
 * Run all's plan, by the per-bot button's rules ({@link runNowBlocked}, {@link isOpenRequest}): a bot that is not
 * ACTIVE, already has a run on its way, or that no claude-trader service serves is not run. The server judges again
 * when it asks (the same rules), so this only names the bots for the confirmation.
 */
export function runAllPlan(bots: RunAllBot[], states: RunAllState[]): RunAllPlan {
  const plan: RunAllPlan = { run: [], skipped: [], unknown: [], checking: false }
  bots.forEach((b, i) => {
    if (b.status && b.status !== 'ACTIVE') {
      plan.skipped.push({ handle: b.handle, why: `${b.status}: it cannot trade` })
      return
    }
    const q = states[i]
    const list = q?.data
    if (!list) {
      if (q?.isError) plan.unknown.push(b.handle)
      else plan.checking = true
      return
    }
    if (list.botStatus && list.botStatus !== 'ACTIVE') plan.skipped.push({ handle: b.handle, why: `${list.botStatus}: it cannot trade` })
    else if (isOpenRequest(list.requests?.[0])) plan.skipped.push({ handle: b.handle, why: 'a run is already on its way' })
    else if (runNowBlocked(b.handle, list, serverNow(list, q.dataUpdatedAt))) plan.skipped.push({ handle: b.handle, why: 'no claude-trader service serves it' })
    else plan.run.push(b.handle)
  })
  return plan
}

/** "1 bot", "3 bots" */
export const botCount = (n: number) => `${n} bot${n === 1 ? '' : 's'}`

export const RUN_ALL_LABEL: Record<RunAllOutcome, string> = { REQUESTED: 'Requested', ALREADY_OPEN: 'Already on its way', SKIPPED: 'Skipped' }
export const RUN_ALL_TONE: Record<RunAllOutcome, PillTone> = { REQUESTED: 'accent', ALREADY_OPEN: 'neutral', SKIPPED: 'warn' }

/** "Dry run all at 10:31 AM ET: 2 requested · 1 already on its way · 2 skipped" */
export function runAllHeadline(r: RunAllResult): string {
  const items = r.results ?? []
  const count = (o: RunAllOutcome) => items.filter((i) => i.outcome === o).length
  const parts = [`${r.requested} requested`]
  if (count('ALREADY_OPEN')) parts.push(`${count('ALREADY_OPEN')} already on its way`)
  if (count('SKIPPED')) parts.push(`${count('SKIPPED')} skipped`)
  return `${r.dryRun ? 'Dry run all' : 'Run all'} at ${formatTimeEt(r.asOf)}: ${items.length ? parts.join(' · ') : 'you have no bots to run'}`
}

/** One bot of the summary: a pill and the server's sentence (why it was skipped, or which request is open). */
export function runAllItemView(i: RunAllItem): { label: string; tone: PillTone; note: string | null } {
  const outcome: RunAllOutcome = i.outcome ?? 'SKIPPED'
  const label = outcome === 'REQUESTED' && i.request?.dryRun ? 'Dry run requested' : RUN_ALL_LABEL[outcome]
  return { label, tone: RUN_ALL_TONE[outcome], note: outcome === 'REQUESTED' ? null : i.message }
}

/** The toast after Run all. */
export function runAllToast(r: RunAllResult): { title: string; body: string } {
  if (!r.requested) return { title: r.dryRun ? 'No dry run requested' : 'No run requested', body: 'No bot could be run right now; the summary says why.' }
  return {
    title: `${r.dryRun ? 'Dry run' : 'Run'} requested for ${botCount(r.requested)}`,
    body: 'The trader picks them up within about a minute; each bot\'s line follows its run.',
  }
}
