import type { Dec } from '@/lib/format/decimal'
import { arrowFor, changeDirection, formatChange, formatPct, pctDirection } from '@/lib/format/number'
import { cx } from '@/components/ui/cx'

export const DIRECTION_TEXT = { up: 'text-up', down: 'text-down', flat: 'text-flat' } as const

/** "▲ +3.12 (+1.39%)": colour plus arrow plus sign, never colour alone (§22.7). */
export function Change({
  change,
  pct,
  reference,
  className,
  showArrow = true,
  pctOnly = false,
}: {
  change: Dec | null | undefined
  pct?: Dec | null | undefined
  reference?: Dec | null | undefined
  className?: string
  showArrow?: boolean
  /** Show only the percentage (narrow cells such as the index strip). */
  pctOnly?: boolean
}) {
  // the tone of the value as shown: a change that rounds to 0.00 is flat (§22.7)
  const dir = change != null ? changeDirection(change, reference) : pctDirection(pct)
  const a = arrowFor(dir)
  return (
    <span className={cx('inline-flex items-center gap-1 whitespace-nowrap font-medium', DIRECTION_TEXT[dir], className)}>
      {showArrow && a ? <span aria-hidden="true" className="text-[0.75em]">{a}</span> : null}
      {pctOnly ? null : <span>{formatChange(change, reference)}</span>}
      {pct !== undefined ? <span>{pctOnly ? formatPct(pct) : `(${formatPct(pct)})`}</span> : null}
    </span>
  )
}
