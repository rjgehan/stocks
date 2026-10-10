import type { PillTone } from '@/components/ui/Pill'

/** A budget with no upper bound: the backend sends a negative max (SIM: -1) or Integer.MAX_VALUE. */
export function unlimited(max: number): boolean {
  return max < 0 || max >= 2_147_483_647
}

/** "12/30", or "12/∞" for an unlimited budget. */
export function usageLabel(used: number, max: number): string {
  return `${used}/${unlimited(max) ? '∞' : max}`
}

/** Percent of the budget in use (0 for an unlimited or empty budget). */
export function usagePct(used: number, max: number): number {
  return unlimited(max) || max <= 0 ? 0 : Math.min(100, Math.round((used / max) * 100))
}

/** Job status pill: the scheduler emits RUNNING, DONE and FAILED. */
export function jobTone(status: string | null | undefined): PillTone {
  switch (status) {
    case 'DONE':
    case 'SUCCEEDED':
      return 'up'
    case 'FAILED':
      return 'down'
    case 'RUNNING':
      return 'warn'
    default:
      return 'neutral'
  }
}

/** One probe or capability entry, flattened for display. */
export type KeyValue = { key: string; value: string; failed: boolean }

/**
 * A probe value that reports a problem: an HTTP error status ("HTTP 403", "HTTP 406"), "unreachable", "error: …",
 * an auth or feed refusal in the text, or a `false` flag.
 */
export function isProbeFailure(key: string, value: unknown): boolean {
  if (value === false) return true
  if (typeof value !== 'string') return false
  const v = value.trim().toLowerCase()
  if (/^http\s+([0-9]{3})/.test(v)) {
    const status = Number(/^http\s+([0-9]{3})/.exec(v)![1])
    return status >= 400
  }
  return v === 'unreachable' || v.startsWith('error') || v.includes('forbidden') || v.includes('unauthorized') ||
    v.includes('not permitted') || v.includes('denied') || (key.toLowerCase().includes('error') && v !== '' && v !== 'none')
}

function render(value: unknown): string {
  if (value === null || value === undefined) return '--'
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return JSON.stringify(value)
}

/** Sorted key/value rows of a probe or capability map, with failures flagged. */
export function entries(map: Record<string, unknown> | null | undefined, flagFailures: boolean): KeyValue[] {
  if (!map) return []
  return Object.keys(map)
    .sort()
    .map((key) => ({ key, value: render(map[key]), failed: flagFailures && isProbeFailure(key, map[key]) }))
}
