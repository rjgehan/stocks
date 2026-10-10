import { useMemo, useState } from 'react'
import { createFileRoute } from '@tanstack/react-router'
import { describeError, errorBody } from '@/lib/api/errors'
import { useAdminPlayers, useApplyTransfer, useTransferPreview, useTransfers } from '@/lib/api/hooks/admin'
import type { CashTransferBody, PlayerKind, TransferBatchResult } from '@/lib/api/types'
import { isDecimal, isZero, parseUserDecimal, setScale, sign } from '@/lib/format/decimal'
import { formatMoney } from '@/lib/format/money'
import { useDebounced } from '@/lib/useDebounced'
import { Button } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { Checkbox, SelectField, TextField } from '@/components/ui/Field'
import { Pill } from '@/components/ui/Pill'
import { Segmented } from '@/components/ui/Segmented'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'
import { TransferRows } from '@/features/admin/TransferRows'
import { QualityBadge } from '@/features/market/QualityBadge'
import { toast } from '@/features/notifications/toastStore'

export const Route = createFileRoute('/_app/admin/money')({
  validateSearch: (s: Record<string, unknown>): { handle?: string } => ({ handle: typeof s.handle === 'string' && s.handle ? s.handle : undefined }),
  component: MoneyPage,
})

type Scope = 'ALL' | 'SINGLE'
type Kind = 'DEPOSIT' | 'WITHDRAWAL' | 'ADJUSTMENT'

/**
 * Admin · Money (§21.2, §21.3): deposit, withdraw or adjust cash for one player or everyone (by kind), with a memo the
 * players see. A live preview lists every affected account (cash, withdrawable, E⁻, U, worst mark quality, warnings);
 * degraded marks need an explicit override; confirmation requires typing the amount. Deposits mint units at the current
 * unit value, so nobody's return moves.
 */
function MoneyPage() {
  const { handle: preselected } = Route.useSearch()
  const players = useAdminPlayers()
  const [scope, setScope] = useState<Scope>(preselected ? 'SINGLE' : 'ALL')
  const [handle, setHandle] = useState(preselected ?? '')
  const [kinds, setKinds] = useState<PlayerKind[]>(['HUMAN', 'AI'])
  const [type, setType] = useState<Kind>('DEPOSIT')
  const [negative, setNegative] = useState(false)
  const [amountText, setAmountText] = useState('')
  const [treatAs, setTreatAs] = useState<'CONTRIBUTION' | 'PNL_CORRECTION'>('CONTRIBUTION')
  const [memo, setMemo] = useState('')
  const [allOrNothing, setAllOrNothing] = useState(false)
  const [acceptDegraded, setAcceptDegraded] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID())
  const [result, setResult] = useState<TransferBatchResult | null>(null)
  const apply = useApplyTransfer()
  const transfers = useTransfers()

  const amount = useMemo(() => {
    const d = parseUserDecimal(amountText)
    if (!d || !isDecimal(d) || sign(d) <= 0) return null
    return setScale(d, 2, 'DOWN')
  }, [amountText])
  const signedAmount = amount && type === 'ADJUSTMENT' && negative ? `-${amount}` : amount
  const memoOk = memo.trim().length >= 1 && memo.trim().length <= 200
  const targetOk = scope === 'ALL' ? kinds.length > 0 : Boolean(handle)

  const body = useMemo<CashTransferBody | null>(() => {
    if (!signedAmount || isZero(signedAmount) || !memoOk || !targetOk) return null
    return {
      target: scope === 'ALL' ? { scope: 'ALL', kinds } : { scope: 'SINGLE', handle },
      type,
      amount: signedAmount,
      ...(type === 'ADJUSTMENT' ? { treatAs } : {}),
      memo: memo.trim(),
      allOrNothing,
      acceptDegradedMarks: acceptDegraded,
    }
  }, [signedAmount, memoOk, targetOk, scope, kinds, handle, type, treatAs, memo, allOrNothing, acceptDegraded])
  const debounced = useDebounced(body, 350)
  // the preview ignores the confirmation-only flags
  const previewBody = useMemo(() => (debounced ? { ...debounced, acceptDegradedMarks: false } : null), [debounced])
  const preview = useTransferPreview(previewBody)
  const accounts = preview.data?.accounts ?? []
  const degraded = accounts.some((a) => (a.warnings ?? []).some((w) => w.code === 'MARKS_DEGRADED'))
  const applying = accounts.filter((a) => a.wouldApply).length
  const ready = Boolean(body && preview.data && debounced === body && (!degraded || acceptDegraded) && applying > 0)

  const submit = () => {
    if (!body) return
    apply.mutate(
      { body, idempotencyKey },
      {
        onSuccess: (r) => {
          setConfirming(false)
          setResult(r)
          setIdempotencyKey(crypto.randomUUID())
          setAmountText('')
          setMemo('')
          setAcceptDegraded(false)
          toast({ tone: 'up', title: `${r.type === 'WITHDRAWAL' ? 'Withdrew' : r.type === 'DEPOSIT' ? 'Deposited' : 'Adjusted'} ${formatMoney(r.amount)} · ${r.applied} applied${r.skipped ? `, ${r.skipped} skipped` : ''}` })
        },
        onError: (e) => {
          setConfirming(false)
          const d = describeError(e)
          toast({ tone: 'error', title: d.title, body: errorBody(d) })
        },
      },
    )
  }

  const verb = type === 'DEPOSIT' ? 'Deposit' : type === 'WITHDRAWAL' ? 'Withdraw' : 'Adjust'
  const playerOptions = (players.data ?? []).filter((p) => p.handle && p.status !== 'DISABLED').map((p) => ({ value: p.handle!, label: `${p.displayName} (@${p.handle})${p.kind === 'AI' ? ' · AI' : ''}` }))
  return (
    <div className="grid gap-4 desktop:grid-cols-[minmax(0,440px)_1fr]">
      <section className="space-y-4 rounded-lg border border-divider bg-bg-1 p-4" aria-label="Transfer">
        <h2 className="text-section font-semibold">Move money</h2>
        <Segmented
          label="Target"
          block
          value={scope}
          onChange={setScope}
          options={[
            { value: 'ALL', label: 'All players' },
            { value: 'SINGLE', label: 'One player' },
          ]}
        />
        {scope === 'SINGLE' ? (
          <SelectField label="Player" value={handle} onChange={(e) => setHandle(e.target.value)} options={[{ value: '', label: 'Choose a player…' }, ...playerOptions]} />
        ) : (
          <fieldset className="flex gap-4">
            <legend className="sr-only">Who</legend>
            <Checkbox checked={kinds.includes('HUMAN')} onChange={(on) => setKinds((k) => (on ? [...new Set([...k, 'HUMAN' as const])] : k.filter((x) => x !== 'HUMAN')))}>
              Humans
            </Checkbox>
            <Checkbox checked={kinds.includes('AI')} onChange={(on) => setKinds((k) => (on ? [...new Set([...k, 'AI' as const])] : k.filter((x) => x !== 'AI')))}>
              AI bots
            </Checkbox>
          </fieldset>
        )}
        <Segmented
          label="Type"
          block
          value={type}
          onChange={setType}
          options={[
            { value: 'DEPOSIT', label: 'Deposit' },
            { value: 'WITHDRAWAL', label: 'Withdraw' },
            { value: 'ADJUSTMENT', label: 'Adjust' },
          ]}
        />
        <div className="flex items-end gap-2">
          {type === 'ADJUSTMENT' ? (
            <Segmented
              label="Adjustment sign"
              value={negative ? 'MINUS' : 'PLUS'}
              onChange={(v) => setNegative(v === 'MINUS')}
              options={[
                { value: 'PLUS', label: '+' },
                { value: 'MINUS', label: '−' },
              ]}
            />
          ) : null}
          <TextField
            className="flex-1"
            label="Amount"
            prefix="$"
            inputMode="decimal"
            value={amountText}
            onChange={(e) => setAmountText(e.target.value.replace(/[^\d.,]/g, ''))}
            placeholder="100.00"
            hint={scope === 'ALL' ? 'Applied to each player.' : undefined}
          />
        </div>
        {type === 'ADJUSTMENT' ? (
          <SelectField
            label="Treat as"
            value={treatAs}
            onChange={(e) => setTreatAs(e.target.value as 'CONTRIBUTION' | 'PNL_CORRECTION')}
            options={[
              { value: 'CONTRIBUTION', label: 'Contribution (like a deposit; return unchanged)' },
              { value: 'PNL_CORRECTION', label: 'P&L correction (fixes a game bug; moves the return)' },
            ]}
          />
        ) : null}
        <TextField label="Memo (players see it)" value={memo} onChange={(e) => setMemo(e.target.value)} maxLength={200} placeholder="Halloween bonus" hint={`${memo.trim().length}/200`} />
        <Checkbox checked={allOrNothing} onChange={setAllOrNothing}>
          All or nothing (if any account would be skipped, apply none)
        </Checkbox>
        {degraded ? (
          <div className="rounded-md border border-warn/40 bg-warn/10 p-2">
            <p className="mb-1 text-caption text-warn">
              Some positions are valued with stale or previous-close prices. Units minted now could permanently skew those players' returns; the safe windows are regular hours or after the
              20:20 ET day-end marks.
            </p>
            <Checkbox checked={acceptDegraded} onChange={setAcceptDegraded}>
              Value with degraded marks anyway
            </Checkbox>
          </div>
        ) : null}
        <Button variant="primary" size="lg" block disabled={!ready || apply.isPending} onClick={() => setConfirming(true)}>
          {body ? `${verb} ${formatMoney(amount)} ${scope === 'ALL' ? `× ${applying} players` : `· @${handle}`}` : `${verb}…`}
        </Button>
        <p className="text-micro text-text-3">Deposits buy units at each account's current unit value, so they never change anyone's return or rank.</p>
      </section>

      <div className="space-y-4">
        <section className="rounded-lg border border-divider bg-bg-1" aria-label="Preview">
          <div className="flex items-center justify-between px-4 pt-3 pb-1">
            <h2 className="text-section font-semibold">Preview</h2>
            {preview.isFetching ? <span className="text-micro text-text-3">Updating…</span> : null}
          </div>
          {!body ? (
            <p className="px-4 pb-4 text-caption text-text-3">Fill in the amount and memo to preview every affected account.</p>
          ) : preview.isError ? (
            <p className="px-4 pb-4 text-caption text-down">{describeError(preview.error).title}</p>
          ) : !preview.data ? (
            <div className="px-4">
              <SkeletonRows rows={3} cols={5} />
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-table">
                <thead className="text-left text-micro tracking-wide text-text-3 uppercase">
                  <tr className="h-8 border-b border-divider">
                    <th className="pl-4 font-medium">Player</th>
                    <th className="px-2 text-right font-medium">Cash</th>
                    <th className="px-2 text-right font-medium">Withdrawable</th>
                    <th className="px-2 text-right font-medium">Equity before</th>
                    <th className="px-2 text-right font-medium">U</th>
                    <th className="px-2 font-medium">Marks</th>
                    <th className="pr-4 pl-2 font-medium">Result</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-divider">
                  {accounts.map((a) => (
                    <tr key={a.accountId ?? a.handle} className={cx('h-10', !a.wouldApply && 'opacity-60')}>
                      <td className="pl-4 font-semibold">@{a.handle}</td>
                      <td className="px-2 text-right">{formatMoney(a.cash)}</td>
                      <td className="px-2 text-right">{formatMoney(a.withdrawable)}</td>
                      <td className="px-2 text-right">{formatMoney(a.equityBefore)}</td>
                      <td className="px-2 text-right font-mono text-caption">{a.unitValue ? a.unitValue.slice(0, 8) : '--'}</td>
                      <td className="px-2">{a.marksQuality ? <QualityBadge quality={a.marksQuality} /> : <span className="text-caption text-text-3">no positions</span>}</td>
                      <td className="pr-4 pl-2">
                        {a.wouldApply ? <Pill tone="up">Applies</Pill> : <Pill tone="down">{a.skipReason?.replaceAll('_', ' ').toLowerCase() ?? 'skipped'}</Pill>}
                        {(a.warnings ?? []).map((w) => (
                          <span key={w.code} className="ml-1 text-micro text-warn" title={w.message ?? undefined}>
                            {w.code === 'ACCOUNT_EQUITY_TOO_LOW' ? 'equity < $1' : w.code === 'MARKS_DEGRADED' ? 'degraded marks' : w.code}
                          </span>
                        ))}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        {result ? (
          <section className="rounded-lg border border-up/30 bg-up-bg p-4" aria-label="Result">
            <h2 className="text-section font-semibold">
              Batch applied: {result.applied} applied{result.skipped ? `, ${result.skipped} skipped` : ''}
            </h2>
            <p className="text-caption text-text-2">
              {result.type?.toLowerCase()} {formatMoney(result.amount)} · “{result.memo}” · batch <span className="font-mono">{result.batchId}</span>
            </p>
            <ul className="mt-2 space-y-0.5 text-caption">
              {(result.results ?? []).map((r) => (
                <li key={r.transferId ?? r.handle}>
                  @{r.handle}: {r.status === 'APPLIED' ? `cash now ${formatMoney(r.cashAfter)}` : `skipped (${r.skipReason?.toLowerCase().replaceAll('_', ' ')})`}
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        <section className="rounded-lg border border-divider bg-bg-1" aria-label="Recent transfers">
          <h2 className="px-4 pt-3 pb-1 text-section font-semibold">Recent transfers</h2>
          {transfers.isPending ? (
            <div className="px-4">
              <SkeletonRows rows={3} cols={4} />
            </div>
          ) : (
            <TransferRows rows={(transfers.data?.pages ?? []).flatMap((p) => p.items ?? [])} />
          )}
          {transfers.hasNextPage ? (
            <div className="flex justify-center p-3">
              <Button size="sm" onClick={() => void transfers.fetchNextPage()} disabled={transfers.isFetchingNextPage}>
                Load more
              </Button>
            </div>
          ) : null}
        </section>
      </div>

      <ConfirmDialog
        open={confirming}
        onClose={() => setConfirming(false)}
        onConfirm={submit}
        pending={apply.isPending}
        title={`${verb} ${formatMoney(amount)}?`}
        confirmLabel={`${verb} now`}
        typeToConfirm={amount ?? ''}
      >
        <p>
          {scope === 'ALL' ? `Each of ${applying} players` : `@${handle}`} {type === 'WITHDRAWAL' ? 'loses' : 'gets'} {formatMoney(signedAmount)} with the memo “{memo.trim()}”.
          {type !== 'ADJUSTMENT' || treatAs === 'CONTRIBUTION' ? ' Returns and ranks do not change.' : ' This is a P&L correction: it changes the return.'}
        </p>
      </ConfirmDialog>
    </div>
  )
}
