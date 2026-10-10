import { IconButton } from '@/components/ui/Button'
import { Popover } from '@/components/ui/Popover'
import { IconInfo } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'

/** One scoring rule, written out with its exact formula (§16.2, §16.3, §16.5, §16.6). */
export type FormulaTopic = 'RETURN' | 'PNL' | 'VS_SPY' | 'NET_CONTRIB' | 'RANKED' | 'ARENA' | 'RANK_CHANGE'

const Code = ({ children }: { children: React.ReactNode }) => <code className="rounded-sm bg-bg-2 px-1 font-mono text-[11px] text-text-1">{children}</code>

export const FORMULAS: Record<FormulaTopic, { title: string; body: React.ReactNode }> = {
  RETURN: {
    title: 'Return (time-weighted)',
    body: (
      <>
        Every account is a fund: a deposit buys units at the current unit value <Code>U = equity ÷ units</Code>, so money in or out never moves U. Return ={' '}
        <Code>U(end) ÷ U(start) − 1</Code>. An admin deposit to everyone changes nobody&apos;s return; a reset never erases a loss.
      </>
    ),
  },
  PNL: {
    title: 'P&L ($)',
    body: (
      <>
        <Code>[equity − net contributions](end) − [same](start)</Code>. Deposits are contributions, not profit. The P&amp;L day ends at 20:00 ET. Ranking by P&amp;L favours bigger
        accounts.
      </>
    ),
  },
  VS_SPY: {
    title: 'vs SPY',
    body: (
      <>
        <Code>return − SPY total return</Code> over the same window, dividends included. Positive means the player beat simply holding the S&amp;P 500.
      </>
    ),
  },
  NET_CONTRIB: {
    title: 'Net contributions',
    body: (
      <>
        Starting cash plus deposits minus withdrawals, public for everyone (fairness charter). The chip shows this period&apos;s net flows; they never change the return.
      </>
    ),
  },
  RANKED: {
    title: 'Who is ranked',
    body: (
      <>
        Week and longer need 5 fills across 2 symbols inside the window; others are listed greyed with their progress (&quot;3/5 trades&quot;). Equal returns (within 1e−8) rank by
        P&amp;L, then by who joined first.
      </>
    ),
  },
  ARENA: {
    title: 'Arena: humans vs AI',
    body: (
      <>
        The <Code>median</Code> return of the human players against the median of the AI players (Claude, ChatGPT…), over the selected period, with SPY as the benchmark. Unranked
        players count too: the median is not swayed by one big winner.
      </>
    ),
  },
  RANK_CHANGE: {
    title: 'Rank arrows',
    body: <>▲ / ▼ show places gained or lost since the previous end-of-day snapshot of this board.</>,
  },
}

/** A small ⓘ next to a column or card title that opens one formula. */
export function FormulaPopover({ topic, className, align = 'end' }: { topic: FormulaTopic; className?: string; align?: 'start' | 'end' }) {
  const f = FORMULAS[topic]
  return (
    <Popover
      label={f.title}
      align={align}
      className="w-[min(320px,calc(100vw-32px))] p-3 text-left normal-case tracking-normal"
      renderTrigger={(props) => (
        <IconButton label={`How ${f.title.toLowerCase()} is computed`} size="touch" className={cx('-my-2 tablet:my-0 tablet:size-6', className)} {...props}>
          <IconInfo size={14} />
        </IconButton>
      )}
    >
      <p className="mb-1 text-caption font-semibold text-text-1">{f.title}</p>
      <p className="text-caption font-normal text-text-2">{f.body}</p>
    </Popover>
  )
}

/** Every formula behind the board (§16.2, §16.5), so nobody has to guess how deposits are treated. */
export function MetricInfoPopover({ align = 'end' }: { align?: 'start' | 'end' }) {
  return (
    <Popover
      label="How the league is scored"
      align={align}
      className="w-[min(380px,calc(100vw-32px))] p-4"
      renderTrigger={(props) => (
        <IconButton label="How the league is scored" size="touch" className="tablet:size-8" {...props}>
          <IconInfo size={18} />
        </IconButton>
      )}
    >
      <div className="space-y-3 text-caption text-text-2">
        {(['RETURN', 'PNL', 'VS_SPY', 'RANKED', 'ARENA'] as const).map((t) => (
          <div key={t}>
            <p className="font-semibold text-text-1">{FORMULAS[t].title}</p>
            <p>{FORMULAS[t].body}</p>
          </div>
        ))}
      </div>
    </Popover>
  )
}
