import { Link } from '@tanstack/react-router'
import type { Leaderboard, LeaderboardRow } from '@/lib/api/types'
import { type Dec, abs, compare, isDecimal, shift, sub } from '@/lib/format/decimal'
import { formatDecimal, formatPct, pctDirection } from '@/lib/format/number'
import { cx } from '@/components/ui/cx'
import { IconRobot, IconUsers } from '@/components/ui/icons'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { FormulaPopover } from './MetricInfoPopover'

/** The best row of one side (by return), ranked or not: the face of the team. */
function best(rows: LeaderboardRow[]): LeaderboardRow | null {
  return rows.reduce<LeaderboardRow | null>((acc, r) => (r.return && isDecimal(r.return) && (!acc || compare(r.return, acc.return ?? '0') > 0) ? r : acc), null)
}

/** "0.26 pts": a gap between two returns (fractions) in percentage points. */
export function pointsGap(a: Dec, b: Dec): string {
  return `${formatDecimal(shift(abs(sub(a, b)), 2), { scale: 2 })} pts`
}

/** Who leads the arena, in words. */
export function arenaHeadline(humans: Dec | null | undefined, ai: Dec | null | undefined): string {
  if (!humans || !ai || !isDecimal(humans) || !isDecimal(ai)) return humans || ai ? 'Waiting for both teams' : 'No players yet'
  const c = compare(sub(ai, humans), '0')
  // a gap under 0.005 pts shows as 0.00: call it even
  if (formatDecimal(shift(abs(sub(ai, humans)), 2), { scale: 2 }) === '0.00' || c === 0) return 'Dead even'
  return c > 0 ? `AI leads by ${pointsGap(ai, humans)}` : `Humans lead by ${pointsGap(humans, ai)}`
}

/** Bar geometry only (never money): a return's share of the largest absolute return on the card, 0–100. */
function barPct(value: Dec | null | undefined, scale: number): number {
  if (!value || !isDecimal(value) || scale <= 0) return 0
  return Math.min(100, (Math.abs(Number(value)) / scale) * 100)
}

function Side({ label, icon, median, count, top, tone, align }: { label: string; icon: React.ReactNode; median: Dec | null | undefined; count: number; top: LeaderboardRow | null; tone: 'human' | 'ai'; align: 'start' | 'end' }) {
  return (
    <div className={cx('min-w-0', align === 'end' && 'text-right')}>
      <div className={cx('flex items-center gap-1.5 text-micro font-semibold tracking-wide uppercase', tone === 'human' ? 'text-human' : 'text-ai', align === 'end' && 'justify-end')}>
        {icon}
        {label} <span className="font-medium text-text-3 normal-case">· {count}</span>
      </div>
      <div className={cx('mt-0.5 text-[22px] leading-7 font-bold tabular-nums tablet:text-[26px] tablet:leading-8', DIRECTION_TEXT[pctDirection(median)])}>{formatPct(median)}</div>
      <div className="text-micro text-text-3">median return</div>
      {top?.player?.handle ? (
        <div className={cx('mt-1 truncate text-caption text-text-2')}>
          best{' '}
          <Link to="/players/$handle" params={{ handle: top.player.handle }} className="font-semibold text-text-1 hover:text-accent">
            @{top.player.handle}
          </Link>{' '}
          <span className={DIRECTION_TEXT[pctDirection(top.return)]}>{formatPct(top.return)}</span>
        </div>
      ) : null}
    </div>
  )
}

/**
 * Arena (§22.4 League): the median human against the median AI over the period, with SPY as the yardstick. A
 * diverging bar per team (green above zero, red below), the leader in words, each team's best player.
 */
export function ArenaCard({ board }: { board: Leaderboard }) {
  const rows = board.rows ?? []
  const humans = rows.filter((r) => r.player?.kind === 'HUMAN')
  const ais = rows.filter((r) => r.player?.kind === 'AI')
  const h = board.arena?.humansMedianReturn
  const a = board.arena?.aiMedianReturn
  const spy = board.benchmark?.return
  const scale = Math.max(...[h, a, spy].map((v) => (v && isDecimal(v) ? Math.abs(Number(v)) : 0)), 0.0001)
  const bar = (v: Dec | null | undefined, cls: string) => {
    const pct = barPct(v, scale) / 2
    const negative = v != null && isDecimal(v) && v.startsWith('-')
    return (
      <div className="relative h-2 rounded-full bg-bg-3" aria-hidden="true">
        <span className="absolute top-[-3px] left-1/2 h-[14px] w-px bg-text-3" />
        <span className={cx('absolute top-0 h-full rounded-full', cls, negative ? 'right-1/2' : 'left-1/2')} style={{ width: `${pct}%` }} />
      </div>
    )
  }
  return (
    <section aria-label="Arena" className="overflow-hidden rounded-lg border border-divider bg-gradient-to-r from-human/10 via-bg-1 to-ai-bg">
      <div className="flex items-center justify-between gap-2 border-b border-divider/60 px-4 py-1 tablet:py-2">
        <h2 className="flex shrink-0 items-center gap-1 text-caption font-semibold tracking-wide text-text-2 uppercase">
          Arena <span className="hidden font-medium normal-case text-text-3 tablet:inline">· humans vs AI</span>
          <FormulaPopover topic="ARENA" align="start" />
        </h2>
        <span className="min-w-0 truncate text-caption font-semibold text-text-1" aria-live="polite">
          {arenaHeadline(h, a)}
        </span>
      </div>
      <div className="grid grid-cols-[1fr_auto_1fr] items-start gap-3 px-4 pt-3">
        <Side label="Humans" icon={<IconUsers size={14} />} median={h} count={humans.length} top={best(humans)} tone="human" align="start" />
        <div className="self-center pt-2 text-caption font-bold text-text-3">VS</div>
        <Side label="AI" icon={<IconRobot size={14} />} median={a} count={ais.length} top={best(ais)} tone="ai" align="end" />
      </div>
      <div className="space-y-1.5 px-4 pt-3 pb-3">
        <div className="grid grid-cols-[56px_1fr_64px] items-center gap-2 text-micro text-text-3">
          <span>Humans</span>
          {bar(h, 'bg-human')}
          <span className="text-right tabular-nums">{formatPct(h)}</span>
        </div>
        <div className="grid grid-cols-[56px_1fr_64px] items-center gap-2 text-micro text-text-3">
          <span>AI</span>
          {bar(a, 'bg-ai')}
          <span className="text-right tabular-nums">{formatPct(a)}</span>
        </div>
        <div className="grid grid-cols-[56px_1fr_64px] items-center gap-2 text-micro text-text-3">
          <span>{board.benchmark?.symbol ?? 'SPY'}</span>
          {bar(spy, 'bg-text-3')}
          <span className="text-right tabular-nums">{formatPct(spy)}</span>
        </div>
      </div>
    </section>
  )
}
