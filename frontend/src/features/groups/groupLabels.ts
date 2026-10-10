/** Display labels for group orders (Phase 2, §11.9; §22.5 "Orders"): chips, roles and the group status. */
import type { OrderGroupRef, OrderGroupRole, OrderGroupStatus, OrderGroupType } from '@/lib/api/groupTypes'
import type { PillTone } from '@/components/ui/Pill'

export const GROUP_TYPE_LABEL: Record<OrderGroupType, string> = {
  BRACKET: 'Bracket',
  OCO: 'OCO',
  OTO: 'OTO',
  OTOCO: 'OTOCO',
}

/** One line on what each type does (the ticket's type picker). */
export const GROUP_TYPE_HELP: Record<OrderGroupType, string> = {
  BRACKET: 'Buy, then protect it: a take profit above and a stop loss below, one cancels the other.',
  OCO: 'Two orders at once: when one executes, the other is cancelled.',
  OTO: 'Buy, then place a second order once the buy fills.',
  OTOCO: 'Buy (also on a stop), then a take profit and a stop loss that cancel each other.',
}

export const ROLE_LABEL: Record<OrderGroupRole, string> = {
  ENTRY: 'Entry',
  TAKE_PROFIT: 'Take profit',
  STOP_LOSS: 'Stop loss',
  SECONDARY: 'Then',
}

const ROLE_SHORT: Record<OrderGroupRole, string> = {
  ENTRY: 'Entry',
  TAKE_PROFIT: 'TP',
  STOP_LOSS: 'SL',
  SECONDARY: 'Then',
}

export const GROUP_STATUS_LABEL: Record<OrderGroupStatus, string> = {
  ACTIVE: 'Active',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
}

export const GROUP_STATUS_TONE: Record<OrderGroupStatus, PillTone> = {
  ACTIVE: 'accent',
  COMPLETED: 'up',
  CANCELLED: 'flat',
  EXPIRED: 'flat',
}

/** "Bracket · TP" for a leg's chip in the orders lists. */
export function groupChipLabel(ref: Pick<OrderGroupRef, 'type' | 'role'> | null | undefined): string | null {
  if (!ref?.type || !ref.role) return null
  return `${GROUP_TYPE_LABEL[ref.type]} · ${ROLE_SHORT[ref.role]}`
}

/** A held child (its entry has not filled yet) reads "Waiting for entry" instead of its pending status. */
export function heldLabel(ref: Pick<OrderGroupRef, 'held'> | null | undefined): string | null {
  return ref?.held ? 'Waiting for entry' : null
}

/** The order of legs in the linked-orders card and the review sheet. */
export const ROLE_ORDER: readonly OrderGroupRole[] = ['ENTRY', 'TAKE_PROFIT', 'STOP_LOSS', 'SECONDARY']
