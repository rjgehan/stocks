import type { OrderGroupRef } from '@/lib/api/groupTypes'
import { Pill } from '@/components/ui/Pill'
import { cx } from '@/components/ui/cx'
import { groupChipLabel } from './groupLabels'

/** "Bracket · TP": a group leg's chip in the orders lists and the bottom dock (§22.5 Orders, §11.9). */
export function GroupChip({ group, className }: { group: OrderGroupRef | null | undefined; className?: string }) {
  const label = groupChipLabel(group)
  if (!label) return null
  return (
    <Pill tone="neutral" className={cx('ml-1.5 align-middle', className)} title={group?.held ? 'Waits for its entry to fill' : 'Part of a group order'}>
      {label}
    </Pill>
  )
}
