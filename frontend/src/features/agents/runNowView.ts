/**
 * "Run now" (§20.8) as the button shows it: who may run a bot, and one timeline line per request state.
 */
import type { PillTone } from '@/components/ui/Pill'
import type { Me, RunRequest, RunRequestStatus } from '@/lib/api/types'
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
      note = 'The trader picks it up within about a minute.'
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
      note = EXPIRED_NOTE
      break
  }
  return { label: RUN_REQUEST_LABEL[status], tone: RUN_REQUEST_TONE[status], line: parts.join(' · '), note }
}
