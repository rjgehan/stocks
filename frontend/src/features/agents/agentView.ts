/**
 * View logic of the Phase 2 agent screens (§17.10, §20.6, §20.7): run status labels and tones, the daily spend meter,
 * durations, outcome pills. Pure functions, unit tested; the components only render what these return.
 *
 * Tones never rely on the up/down palette (players can swap green and red, §22.8): a failure is amber (`warn`) or
 * red-ish `down` only for hard errors, and every pill also carries its text, so color is never the only signal.
 */
import type { PillTone } from '@/components/ui/Pill'
import { compare, isDecimal, sign } from '@/lib/format/decimal'
import { formatMoney } from '@/lib/format/money'
import type { AgentRunStatus, AgentRunSummary, ApiCall } from '@/lib/api/types'

export const STATUS_LABEL: Record<AgentRunStatus, string> = {
  COMPLETED: 'Completed',
  TOOL_CAP: 'Tool-call cap',
  TIME_CAP: 'Time cap',
  DAILY_CAP: 'Daily cap reached',
  MAX_TOKENS: 'Output cut off',
  REFUSAL: 'Refused',
  ERROR: 'Error',
  ABORTED: 'Aborted',
  SKIPPED_DAILY_CAP: 'Skipped: daily cap',
  SKIPPED_NON_TRADING_DAY: 'Skipped: market closed',
  SKIPPED_BUSY: 'Skipped: busy',
}

export const RUN_STATUSES = Object.keys(STATUS_LABEL) as AgentRunStatus[]

export function statusLabel(s: AgentRunStatus | null | undefined): string {
  return s ? (STATUS_LABEL[s] ?? s) : 'No runs yet'
}

/** Completed: accent; caps and refusals: amber; errors and aborts: down; skips: neutral. */
export function statusTone(s: AgentRunStatus | null | undefined): PillTone {
  switch (s) {
    case 'COMPLETED':
      return 'accent'
    case 'ERROR':
    case 'ABORTED':
      return 'down'
    case 'TOOL_CAP':
    case 'TIME_CAP':
    case 'DAILY_CAP':
    case 'MAX_TOKENS':
    case 'REFUSAL':
    case 'SKIPPED_DAILY_CAP':
      return 'warn'
    default:
      return 'neutral'
  }
}

const plural = (n: number, one: string): string => `${n} ${one}${n === 1 ? '' : 's'}`

/** "Completed · 9 tool calls · 2 orders" (the profile's Bot activity line). */
export function runSummaryLine(s: AgentRunSummary | null | undefined): string {
  if (!s || !s.lastStatus) return 'No runs reported yet'
  const parts = [statusLabel(s.lastStatus)]
  if (s.lastRunToolCalls != null && !s.lastStatus.startsWith('SKIPPED')) parts.push(plural(s.lastRunToolCalls, 'tool call'))
  if (s.lastRunOrders != null && !s.lastStatus.startsWith('SKIPPED')) parts.push(plural(s.lastRunOrders, 'order'))
  return parts.join(' · ')
}

export interface SpendMeter {
  /** 0-100, for the bar width only (cosmetic; the amounts are shown as text, formatted from the server's decimals). */
  pct: number
  tone: 'ok' | 'warn' | 'over' | 'none'
  /** "$0.42 of $1.00" */
  label: string
}

/** Today's spend against the cap: amber from 80 %, red at the cap; no cap known → `none`. */
export function spendMeter(spent: string | null | undefined, cap: string | null | undefined): SpendMeter {
  const s = spent && isDecimal(spent) ? spent : '0'
  if (!cap || !isDecimal(cap) || sign(cap) <= 0) return { pct: 0, tone: 'none', label: `${formatUsd(s)} today` }
  const ratio = Number(s) / Number(cap)
  return {
    pct: Math.max(0, Math.min(100, ratio * 100)),
    tone: compare(s, cap) >= 0 ? 'over' : ratio >= 0.8 ? 'warn' : 'ok',
    label: `${formatUsd(s)} of ${formatUsd(cap)}`,
  }
}

/** A run's counts: "40 s · 12 tool calls (1 error) · 2 orders". */
export function runCounts(r: { durationMs?: number | null; toolCalls?: number | null; toolErrors?: number | null; orders?: number | null }): string {
  const errors = r.toolErrors ? ` (${plural(r.toolErrors, 'error')})` : ''
  return `${formatDuration(r.durationMs)} · ${plural(r.toolCalls ?? 0, 'tool call')}${errors} · ${plural(r.orders ?? 0, 'order')}`
}

/**
 * An ISO-8601 feed delay (`GET /rules` `social.tradeFeedDelay`, e.g. PT15M) in words: "right away", "15 minutes",
 * "1 hour", "1 hour 30 minutes"; null when it cannot be read.
 */
export function delayWords(iso: string | null | undefined): string | null {
  const m = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(iso ?? '')
  if (!m || (!m[1] && !m[2] && !m[3])) return null
  const total = Number(m[1] ?? 0) * 3600 + Number(m[2] ?? 0) * 60 + Math.round(Number(m[3] ?? 0))
  if (total === 0) return 'right away'
  const h = Math.floor(total / 3600)
  const min = Math.floor((total % 3600) / 60)
  const s = total % 60
  return [h ? plural(h, 'hour') : null, min ? plural(min, 'minute') : null, s ? plural(s, 'second') : null].filter(Boolean).join(' ')
}

/**
 * The league's feed delay for public copy (public mode, §16.7): null when there is none (PT0S, the default) or the
 * rules are not loaded yet; else its words ("15 minutes"), or "the league's feed delay" when it has no words (P1D).
 */
export function feedDelayLabel(iso: string | null | undefined): string | null {
  if (!iso) return null
  const words = delayWords(iso)
  if (words === 'right away') return null
  return words ?? 'the league’s feed delay'
}

/** 450 ms, 12.4 s, 1 min 34 s, 2 h 5 min. */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return '--'
  if (ms < 1000) return `${Math.round(ms)} ms`
  const s = ms / 1000
  if (s < 60) return `${s < 10 ? s.toFixed(1) : Math.round(s)} s`
  const total = Math.round(s)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const sec = total % 60
  if (h > 0) return `${h} h ${m} min`
  return sec ? `${m} min ${sec} s` : `${m} min`
}

/** 2xx/3xx/OK: neutral; 429 and 4xx: amber; 5xx and INTERNAL_ERROR: down. */
export function outcomeTone(c: Pick<ApiCall, 'ok' | 'outcome'>): PillTone {
  if (c.ok) return 'neutral'
  const o = c.outcome ?? ''
  if (/^5\d\d$/.test(o) || o === 'INTERNAL_ERROR' || o === 'ENGINE_UNAVAILABLE') return 'down'
  return 'warn'
}

/** "REST" or "MCP" (OAuth MCP in Phase 2 is still MCP). */
export function channelLabel(channel: ApiCall['channel']): string {
  return channel === 'MCP_KEY' || channel === 'MCP_OAUTH' ? 'MCP' : 'REST'
}

/** Compact token count: 950, 12.4k, 1.2M. */
export function formatTokens(n: number | null | undefined): string {
  if (n == null) return '--'
  if (n < 1000) return String(n)
  if (n < 1_000_000) return `${(n / 1000).toFixed(n < 10_000 ? 1 : 0)}k`
  return `${(n / 1_000_000).toFixed(1)}M`
}

/** A USD amount (decimal string) with cents, keeping sub-cent costs readable: "$0.18", "$0.0034". */
export function formatUsd(v: string | null | undefined): string {
  if (v == null || !isDecimal(v)) return '--'
  const subCent = sign(v) > 0 && compare(v, '0.01') < 0
  return formatMoney(v, { scale: subCent ? 4 : 2 })
}
