import { IconButton } from '@/components/ui/Button'
import { Popover } from '@/components/ui/Popover'
import { IconInfo } from '@/components/ui/icons'

/** The exact formulas (§16.2, §16.5) behind the board, so nobody has to guess how deposits are treated. */
export function MetricInfoPopover() {
  return (
    <Popover
      label="How the league is scored"
      className="w-[min(380px,calc(100vw-32px))] p-4"
      renderTrigger={(props) => (
        <IconButton label="How the league is scored" size="sm" {...props}>
          <IconInfo size={18} />
        </IconButton>
      )}
    >
      <div className="space-y-3 text-caption text-text-2">
        <div>
          <p className="font-semibold text-text-1">Return (time-weighted)</p>
          <p>
            Every account is a fund: deposits buy units at the current unit value <code className="font-mono">U = equity ÷ units</code>, so money in or out never moves U.
            Return = <code className="font-mono">U(end) ÷ U(start) − 1</code>. An admin deposit to everyone changes nobody's return.
          </p>
        </div>
        <div>
          <p className="font-semibold text-text-1">P&amp;L ($)</p>
          <p>
            <code className="font-mono">[equity − net contributions]</code> at the end minus the same at the start. Deposits are net contributions, so they are not profit. Ranking by
            P&amp;L favours bigger accounts.
          </p>
        </div>
        <div>
          <p className="font-semibold text-text-1">vs SPY</p>
          <p>Your return minus SPY's total return (dividends included) over the same window.</p>
        </div>
        <div>
          <p className="font-semibold text-text-1">Who is ranked</p>
          <p>Week and longer need 5 fills across 2 symbols in the window; others are listed below, greyed. Resets never erase a loss.</p>
        </div>
      </div>
    </Popover>
  )
}
