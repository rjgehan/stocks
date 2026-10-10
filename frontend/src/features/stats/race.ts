/**
 * Race chart helpers (§16.9, §22.4 Phase 2): humans in blue shades, AI players in violet shades (the `--human` /
 * `--ai` families), the viewer's line thicker, SPY dashed. Colors are resolved hex values mixed in JS (lightweight-charts
 * draws on a canvas and cannot read CSS variables); numbers are for plotting only.
 */
import type { RaceSeries } from '@/lib/api/types'

/** Shade steps around the family's base color: 0 = the base, ± mixes toward white / black. */
const STEPS = [0, 0.35, -0.3, 0.6, -0.5, 0.18, -0.15, 0.45] as const

function parseHex(hex: string): [number, number, number] | null {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim())
  if (!m) return null
  const n = Number.parseInt(m[1]!, 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

/** `base` mixed toward white (t > 0) or black (t < 0) by |t|. */
export function shade(base: string, t: number): string {
  const rgb = parseHex(base)
  if (!rgb) return base
  const target = t >= 0 ? 255 : 0
  const k = Math.abs(t)
  const out = rgb.map((c) => Math.round(c + (target - c) * k))
  return `#${out.map((c) => c.toString(16).padStart(2, '0')).join('')}`
}

export interface RaceLineStyle {
  handle: string
  color: string
  width: number
}

/**
 * One color per series: the n-th human gets the n-th shade of `humanBase`, the n-th AI player the n-th shade of
 * `aiBase` (in the series' rank order, so colors are stable for a given board); the viewer's line is 3 px wide.
 */
export function raceStyles(series: ReadonlyArray<RaceSeries>, humanBase: string, aiBase: string, myHandle?: string | null): RaceLineStyle[] {
  let h = 0
  let a = 0
  return series.map((s) => {
    const ai = s.player?.kind === 'AI'
    const i = ai ? a++ : h++
    const t = STEPS[i % STEPS.length]!
    return { handle: s.player?.handle ?? '', color: shade(ai ? aiBase : humanBase, t), width: s.player?.handle && s.player.handle === myHandle ? 3 : 2 }
  })
}

/** A series' points as plot data: seconds and fraction numbers, strictly increasing time (display only). */
export function plotPoints(points: ReadonlyArray<{ t: string | null; return: string | null }> | null | undefined): Array<{ time: number; value: number }> {
  const out: Array<{ time: number; value: number }> = []
  let last = -1
  for (const p of points ?? []) {
    if (!p.t || p.return == null) continue
    const time = Math.floor(Date.parse(p.t) / 1000)
    if (!Number.isFinite(time) || time <= last) continue
    last = time
    out.push({ time, value: Number(p.return) })
  }
  return out
}
