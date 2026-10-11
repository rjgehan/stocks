/**
 * A series primitive (lightweight-charts 5) that shades the extended-hours spans of an intraday chart behind the bars
 * (§22.10): one translucent band per run of pre-/post-market bars, half a bar wider on each side so the bands meet
 * the regular-session bars exactly.
 */
import type { IChartApiBase, IPrimitivePaneRenderer, IPrimitivePaneView, ISeriesPrimitive, SeriesAttachedParameter, Time } from 'lightweight-charts'
import type { TimeSpan } from './indicators'

type Target = Parameters<IPrimitivePaneRenderer['draw']>[0]

export class ExtendedHoursShading implements ISeriesPrimitive<Time> {
  private chart: IChartApiBase<Time> | null = null
  private requestUpdate: (() => void) | null = null
  private times: readonly number[] = []
  private spans: readonly TimeSpan[] = []
  private color = 'rgba(245,165,36,0.08)'
  private readonly view: IPrimitivePaneView

  constructor() {
    const renderer: IPrimitivePaneRenderer = { draw: (target) => this.draw(target) }
    this.view = { zOrder: () => 'bottom', renderer: () => renderer }
  }

  attached(param: SeriesAttachedParameter<Time>): void {
    this.chart = param.chart
    this.requestUpdate = param.requestUpdate
  }

  detached(): void {
    this.chart = null
    this.requestUpdate = null
  }

  paneViews(): readonly IPrimitivePaneView[] {
    return [this.view]
  }

  /** The bars' times (ascending) and the extended spans among them; an empty span list draws nothing. */
  setData(times: readonly number[], spans: readonly TimeSpan[], color: string): void {
    this.times = times
    this.spans = spans
    this.color = color
    this.requestUpdate?.()
  }

  private x(time: number): number | null {
    return this.chart?.timeScale().timeToCoordinate(time as Time) ?? null
  }

  /** Half the distance to the neighbouring bar (3 px when there is none on screen). */
  private half(index: number): number {
    const here = this.x(this.times[index]!)
    const next = index + 1 < this.times.length ? this.x(this.times[index + 1]!) : null
    const prev = index > 0 ? this.x(this.times[index - 1]!) : null
    if (here != null && next != null) return Math.abs(next - here) / 2
    if (here != null && prev != null) return Math.abs(here - prev) / 2
    return 3
  }

  private draw(target: Target): void {
    if (!this.chart || this.spans.length === 0) return
    const index = new Map<number, number>()
    this.times.forEach((t, i) => index.set(t, i))
    target.useMediaCoordinateSpace(({ context, mediaSize }) => {
      context.fillStyle = this.color
      for (const s of this.spans) {
        const a = this.x(s.from)
        const b = this.x(s.to)
        if (a == null || b == null) continue
        const left = a - this.half(index.get(s.from) ?? 0)
        const right = b + this.half(index.get(s.to) ?? 0)
        context.fillRect(Math.max(0, left), 0, Math.min(mediaSize.width, right) - Math.max(0, left), mediaSize.height)
      }
    })
  }
}
