import { Link } from '@tanstack/react-router'
import type { TransferRow } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'
import { moneyDirection } from '@/lib/format/number'
import { formatDateTimeEt } from '@/lib/format/time'
import { Pill, type PillTone } from '@/components/ui/Pill'
import { cx } from '@/components/ui/cx'
import { DIRECTION_TEXT } from '@/features/market/Change'

const TYPE_TONE: Record<string, PillTone> = { INITIAL: 'neutral', DEPOSIT: 'up', WITHDRAWAL: 'down', ADJUSTMENT: 'warn' }

/** Transfer rows (§21.1 TransferRow): when, who, type, signed amount, memo, the unit value it was minted at, status. */
export function TransferRows({ rows, hideHandle }: { rows: TransferRow[]; hideHandle?: boolean }) {
  if (!rows.length) return <p className="px-4 pb-3 text-caption text-text-3">No transfers yet.</p>
  return (
    <ul className="divide-y divide-divider" aria-label="Transfers">
      {rows.map((t) => (
        <li key={t.transferId} className={cx('flex flex-wrap items-center gap-x-3 gap-y-0.5 px-4 py-2.5 text-table', t.status === 'SKIPPED' && 'opacity-60')}>
          <Pill tone={t.type ? TYPE_TONE[t.type] : 'neutral'}>{t.type?.toLowerCase()}</Pill>
          {!hideHandle && t.handle ? (
            <Link to="/admin/players/$handle" params={{ handle: t.handle }} className="font-semibold hover:text-accent">
              @{t.handle}
            </Link>
          ) : null}
          <span className="min-w-0 flex-1 truncate text-text-2">{t.memo ? `“${t.memo}”` : ''}</span>
          <span className={cx('font-semibold', DIRECTION_TEXT[moneyDirection(t.amount)])}>{formatMoney(t.amount, { signed: true })}</span>
          <span className="w-full text-micro text-text-3">
            {formatDateTimeEt(t.effectiveAt)}
            {t.by ? ` · by @${t.by}` : ''}
            {t.equityBefore ? ` · equity before ${formatMoney(t.equityBefore)}` : ''}
            {t.unitValue ? ` · U ${t.unitValue.slice(0, 8)}` : ''}
            {!t.isContribution ? ' · P&L correction' : ''}
            {t.status === 'SKIPPED' ? ` · skipped: ${t.skipReason?.toLowerCase().replaceAll('_', ' ') ?? 'n/a'}` : ''}
          </span>
        </li>
      ))}
    </ul>
  )
}
