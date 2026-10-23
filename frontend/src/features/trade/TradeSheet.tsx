import { Sheet } from '@/components/ui/Sheet'
import type { Side } from '@/lib/api/types'
import { TradeTicket } from './TradeTicket'
import { optionLabel } from '@/lib/options'

/** The ticket as a 92%-height bottom sheet (phone) or a 400 px right side sheet (tablet), §22.3. */
export function TradeSheet({ symbol, side, open, onClose, variant }: { symbol: string; side: Side; open: boolean; onClose: () => void; variant: 'bottom' | 'right' }) {
  return (
    <Sheet open={open} onClose={onClose} title={`Trade ${optionLabel(symbol)}`} side={variant}>
      <TradeTicket symbol={symbol} initialSide={side} onDone={onClose} />
    </Sheet>
  )
}
