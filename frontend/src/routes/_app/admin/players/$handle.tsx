import { useState } from 'react'
import { Link, createFileRoute } from '@tanstack/react-router'
import { describeError, errorBody } from '@/lib/api/errors'
import {
  useAdminCancelOrder,
  useAdminPlayer,
  useLiveView,
  useResetPlayer,
  useRevokeKey,
  useSetPlayerPassword,
  useUpdatePlayer,
} from '@/lib/api/hooks/admin'
import { useMe } from '@/lib/api/hooks/identity'
import { isApiError } from '@/lib/api/problem'
import type { AdminPlayerLiveView } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'
import { direction, formatPct, formatPrice, formatQty } from '@/lib/format/number'
import { formatDateEt, formatDateTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { Dialog } from '@/components/ui/Dialog'
import { EmptyState } from '@/components/ui/EmptyState'
import { Switch, TextAreaField, TextField } from '@/components/ui/Field'
import { Pill } from '@/components/ui/Pill'
import { Skeleton } from '@/components/ui/Skeleton'
import { IconChevronLeft, IconShield } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'
import { IssueKeyDialog } from '@/features/admin/IssueKeyDialog'
import { PlayerActions } from '@/features/admin/PlayerActions'
import { TransferRows } from '@/features/admin/TransferRows'
import { ORDER_TYPE_LABEL, orderSizeLabel } from '@/features/account/orderLabels'
import { ApiKeyList } from '@/features/keys/ApiKeys'
import { AiChip, Avatar } from '@/features/league/PlayerBadge'
import { DIRECTION_TEXT } from '@/features/market/Change'
import { toast } from '@/features/notifications/toastStore'

export const Route = createFileRoute('/_app/admin/players/$handle')({
  component: AdminPlayerPage,
})

function Stat({ label, value, className }: { label: string; value: React.ReactNode; className?: string }) {
  return (
    <div className="bg-bg-1 p-3">
      <dt className="text-caption text-text-2">{label}</dt>
      <dd className={cx('mt-0.5 text-section font-semibold', className)}>{value}</dd>
    </div>
  )
}

function Section({ title, children, aside }: { title: string; children: React.ReactNode; aside?: React.ReactNode }) {
  return (
    <section className="rounded-lg border border-divider bg-bg-1">
      <div className="flex items-center justify-between gap-2 px-4 pt-3 pb-1">
        <h2 className="text-section font-semibold">{title}</h2>
        {aside}
      </div>
      {children}
    </section>
  )
}

/**
 * Admin · Player detail (§21.3): runs, balances, END-OF-DAY holdings, API keys, "allow API trading", transfers and
 * recent audit rows. Live positions and orders only after "Break glass: view live" (reason + re-auth), which the player
 * sees as "Admin viewed your live account" in their activity.
 */
function AdminPlayerPage() {
  const { handle } = Route.useParams()
  const { data: d, isPending, error } = useAdminPlayer(handle)
  const { data: me } = useMe()
  const update = useUpdatePlayer()
  const revoke = useRevokeKey()
  const [issueFor, setIssueFor] = useState<string | null>(null)
  const [breakGlass, setBreakGlass] = useState(false)
  const [live, setLive] = useState<AdminPlayerLiveView | null>(null)
  const [resetOpen, setResetOpen] = useState(false)
  const [pwOpen, setPwOpen] = useState(false)

  if (isPending) return <Skeleton className="h-64 w-full" />
  if (!d?.player)
    return (
      <EmptyState
        title={error && isApiError(error) && error.code === 'PLAYER_NOT_FOUND' ? `No player @${handle}` : 'This player is unavailable'}
        action={
          <Link to="/admin/players" className="text-accent">
            All players
          </Link>
        }
      />
    )
  const p = d.player
  const b = d.balances
  return (
    <div className="space-y-4 pb-6">
      <Link to="/admin/players" className="inline-flex items-center gap-1 text-caption text-text-2 hover:text-text-1">
        <IconChevronLeft size={16} /> Players
      </Link>
      <section className="flex flex-wrap items-center gap-3 rounded-lg border border-divider bg-bg-1 p-4">
        <Avatar player={p} size={48} />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-[20px] leading-7 font-bold tracking-tight">{p.displayName}</h1>
            {p.kind === 'AI' ? <AiChip model={p.aiModel} /> : <Pill tone="neutral">Human</Pill>}
            {p.role === 'ADMIN' ? <Pill tone="accent">Admin</Pill> : null}
            <Pill tone={p.status === 'ACTIVE' ? 'up' : p.status === 'FROZEN' ? 'warn' : 'flat'}>{p.status}</Pill>
          </div>
          <p className="text-caption text-text-2">
            @{p.handle} · run {p.runNo} · joined {formatDateEt(p.createdAt?.slice(0, 10))}
            {p.ownerHandle ? ` · run by @${p.ownerHandle}` : ''} · last seen {p.lastSeenAt ? formatDateTimeEt(p.lastSeenAt) : 'never'}
          </p>
        </div>
        <div className="flex w-full items-center justify-end gap-2 tablet:w-auto">
          <Link to="/admin/money" search={{ handle: p.handle ?? undefined }} className="inline-flex h-8 items-center rounded-md bg-accent px-3 text-caption font-semibold text-white hover:bg-accent-hover">
            Deposit / withdraw
          </Link>
          <PlayerActions p={p} myHandle={me?.handle} onIssueKey={setIssueFor} />
        </div>
      </section>

      <dl className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-divider bg-divider tablet:grid-cols-4">
        <Stat label="Equity" value={formatMoney(b?.equity ?? p.equity)} />
        <Stat label="Cash" value={formatMoney(b?.cash?.total ?? p.cash)} />
        <Stat label="Buying power" value={formatMoney(b?.buyingPower)} />
        <Stat label="Withdrawable" value={formatMoney(b?.cash?.withdrawable)} />
        <Stat label="Net contributions" value={formatMoney(p.netContributions)} />
        <Stat label="P&L (this run)" value={formatMoney(p.pnl, { signed: true })} className={DIRECTION_TEXT[direction(p.pnl)]} />
        <Stat label="Return (this run)" value={formatPct(p.return)} className={DIRECTION_TEXT[direction(p.return)]} />
        <Stat label="Open orders" value={p.openOrders} />
      </dl>

      {p.kind === 'HUMAN' ? (
        <section className="rounded-lg border border-divider bg-bg-1 px-4 py-1">
          <Switch
            checked={d.apiTradingAllowed}
            disabled={update.isPending}
            onChange={(apiTradingAllowed) =>
              update.mutate(
                { handle, patch: { apiTradingAllowed } },
                { onError: (e) => toast({ tone: 'error', title: 'Not changed', body: describeError(e).title }) },
              )
            }
            label="Allow API trading"
            description="Lets this human create personal keys with the trade scope. Turning it off removes trade from their existing keys; their trades are labeled “via API”."
          />
        </section>
      ) : null}

      <div className="grid gap-4 desktop:grid-cols-2">
        <Section title="Holdings at last close" aside={<span className="text-micro text-text-3">{d.holdingsAsOf ? formatDateEt(d.holdingsAsOf) : ''}</span>}>
          {(d.eodHoldings ?? []).length ? (
            <ul className="divide-y divide-divider">
              {(d.eodHoldings ?? []).map((h) => (
                <li key={h.symbol} className="flex justify-between px-4 py-2 text-table">
                  <span className="font-semibold">{h.symbol}</span>
                  <span className="text-text-2">
                    {formatQty(h.qty)} × {formatPrice(h.mark)} = <span className="font-medium text-text-1">{formatMoney(h.marketValue)}</span>
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="px-4 pb-3 text-caption text-text-3">No holdings at the last end-of-day snapshot.</p>
          )}
          <div className="border-t border-divider px-4 py-3">
            <Button variant="secondary" size="sm" onClick={() => setBreakGlass(true)}>
              <IconShield size={16} /> Break glass: view live
            </Button>
            <p className="mt-1 text-micro text-text-3">You are also a competitor: live positions stay hidden unless you have a reason, and the player is told.</p>
          </div>
        </Section>
        <Section title="Runs">
          <ul className="divide-y divide-divider">
            {(d.runs ?? []).map((r) => (
              <li key={r.runNo} className="flex items-center justify-between gap-3 px-4 py-2 text-table">
                <span className="font-semibold">Run {r.runNo}</span>
                <span className="text-caption text-text-3">
                  {formatDateEt(r.openedAt?.slice(0, 10))} → {r.closedAt ? formatDateEt(r.closedAt.slice(0, 10)) : 'now'} · {formatMoney(r.startingCash)}
                </span>
                <span className={cx('font-semibold', DIRECTION_TEXT[direction(r.return)])}>{formatPct(r.return)}</span>
              </li>
            ))}
          </ul>
          <div className="flex flex-wrap gap-2 border-t border-divider px-4 py-3">
            <Button variant="danger" size="sm" onClick={() => setResetOpen(true)}>
              Reset account…
            </Button>
            {p.kind === 'HUMAN' ? (
              <Button variant="secondary" size="sm" onClick={() => setPwOpen(true)}>
                Set password…
              </Button>
            ) : null}
          </div>
        </Section>
      </div>

      <Section
        title="API keys"
        aside={
          <Button size="sm" variant="primary" onClick={() => setIssueFor(handle)}>
            Issue key
          </Button>
        }
      >
        <ApiKeyList
          keys={d.apiKeys ?? []}
          revoking={revoke.isPending}
          emptyBody={p.kind === 'AI' ? 'Issue a key so the bot can trade through REST or MCP.' : 'This player has no personal keys.'}
          onRevoke={(k) =>
            k.keyId &&
            revoke.mutate(
              { keyId: k.keyId, handle },
              {
                onSuccess: () => toast({ tone: 'neutral', title: `Revoked ${k.name ?? 'key'}` }),
                onError: (e) => toast({ tone: 'error', title: 'Not revoked', body: describeError(e).title }),
              },
            )
          }
        />
      </Section>

      <Section title="Transfers">
        <TransferRows rows={d.transfers ?? []} hideHandle />
      </Section>

      <Section title="Recent audit">
        {(d.recentAudit ?? []).length ? (
          <ul className="divide-y divide-divider">
            {(d.recentAudit ?? []).map((a) => (
              <li key={a.id} className="flex flex-wrap items-center gap-x-3 px-4 py-2 text-table">
                <span className="font-mono text-caption">{a.category}</span>
                <span className="font-semibold">{a.action}</span>
                <span className="text-caption text-text-3">{a.actor ? `@${a.actor}` : 'system'}</span>
                <span className="ml-auto text-caption text-text-3">{formatDateTimeEt(a.occurredAt)}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="px-4 pb-3 text-caption text-text-3">No audit rows yet.</p>
        )}
      </Section>

      <IssueKeyDialog handle={issueFor} onClose={() => setIssueFor(null)} />
      <BreakGlassDialog open={breakGlass} handle={handle} onClose={() => setBreakGlass(false)} onViewed={setLive} />
      <LiveViewDialog view={live} onClose={() => setLive(null)} />
      <ResetDialog open={resetOpen} handle={handle} onClose={() => setResetOpen(false)} />
      <PasswordDialog open={pwOpen} handle={handle} onClose={() => setPwOpen(false)} />
    </div>
  )
}

function BreakGlassDialog({ open, handle, onClose, onViewed }: { open: boolean; handle: string; onClose: () => void; onViewed: (v: AdminPlayerLiveView) => void }) {
  const liveView = useLiveView()
  const [reason, setReason] = useState('')
  const valid = reason.trim().length >= 5 && reason.trim().length <= 200
  return (
    <Dialog open={open} onClose={onClose} title="Break glass: view live account">
      <form
        className="space-y-3 p-4"
        onSubmit={(e) => {
          e.preventDefault()
          liveView.mutate(
            { handle, reason: reason.trim() },
            {
              onSuccess: (v) => {
                setReason('')
                onClose()
                onViewed(v)
              },
            },
          )
        }}
      >
        <p className="rounded-md bg-warn/10 p-3 text-caption text-warn">
          @{handle} will see “Admin viewed your live account” with your reason in their activity, and the view is written to the audit log.
        </p>
        <TextAreaField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} rows={3} maxLength={200} hint="5–200 characters." />
        {liveView.error ? (
          <p role="alert" className="text-caption text-down">
            {describeError(liveView.error).title}
          </p>
        ) : null}
        <Button type="submit" variant="primary" size="lg" block disabled={!valid || liveView.isPending}>
          {liveView.isPending ? 'Opening…' : 'View live positions and orders'}
        </Button>
      </form>
    </Dialog>
  )
}

function LiveViewDialog({ view, onClose }: { view: AdminPlayerLiveView | null; onClose: () => void }) {
  const cancel = useAdminCancelOrder()
  const [cancelled, setCancelled] = useState<string[]>([])
  return (
    <Dialog open={view !== null} onClose={onClose} title={`Live view · @${view?.player?.handle ?? ''}`}>
      {view ? (
        <div className="space-y-3 p-4">
          <p className="text-caption text-text-3">
            Viewed {formatDateTimeEt(view.viewedAt)} · reason “{view.reason}”
          </p>
          <h3 className="text-caption font-semibold tracking-wide text-text-2 uppercase">Positions</h3>
          {(view.positions ?? []).length ? (
            <ul className="divide-y divide-divider rounded-md border border-divider">
              {(view.positions ?? []).map((p) => (
                <li key={p.symbol} className="flex justify-between px-3 py-2 text-table">
                  <span className="font-semibold">{p.symbol}</span>
                  <span>
                    {formatQty(p.qty)} sh · {formatMoney(p.marketValue)} · <span className={DIRECTION_TEXT[direction(p.unrealizedPnl)]}>{formatMoney(p.unrealizedPnl, { signed: true })}</span>
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-caption text-text-3">No positions.</p>
          )}
          <h3 className="text-caption font-semibold tracking-wide text-text-2 uppercase">Open orders</h3>
          {(view.openOrders ?? []).length ? (
            <ul className="divide-y divide-divider rounded-md border border-divider">
              {(view.openOrders ?? []).map((o) => (
                <li key={o.orderId} className="flex items-center justify-between gap-2 px-3 py-2 text-table">
                  <span>
                    {o.side === 'BUY' ? 'Buy' : 'Sell'} {orderSizeLabel(o)} {o.symbol} · {o.type ? ORDER_TYPE_LABEL[o.type] : ''}
                    {o.limitPrice ? ` ${formatPrice(o.limitPrice)}` : ''}
                  </span>
                  {o.orderId && cancelled.includes(o.orderId) ? (
                    <Pill tone="flat">Cancelled</Pill>
                  ) : (
                    <Button
                      size="sm"
                      variant="danger"
                      disabled={cancel.isPending}
                      onClick={() =>
                        o.orderId &&
                        cancel.mutate(o.orderId, {
                          onSuccess: () => setCancelled((c) => [...c, o.orderId!]),
                          onError: (e) => toast({ tone: 'error', title: 'Not cancelled', body: errorBody(describeError(e)) }),
                        })
                      }
                    >
                      Cancel
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-caption text-text-3">No open orders.</p>
          )}
          <Button variant="secondary" block onClick={onClose}>
            Close
          </Button>
        </div>
      ) : null}
    </Dialog>
  )
}

function ResetDialog({ open, handle, onClose }: { open: boolean; handle: string; onClose: () => void }) {
  const reset = useResetPlayer()
  const [memo, setMemo] = useState('')
  const [cash, setCash] = useState('')
  const [key, setKey] = useState(() => crypto.randomUUID())
  const [typed, setTyped] = useState('')
  return (
    <Dialog open={open} onClose={onClose} title={`Reset @${handle}`}>
      <form
        className="space-y-3 p-4"
        onSubmit={(e) => {
          e.preventDefault()
          reset.mutate(
            { handle, memo: memo.trim(), ...(cash ? { startingCash: cash } : {}), idempotencyKey: key },
            {
              onSuccess: () => {
                toast({ tone: 'neutral', title: `@${handle} reset`, body: 'A new run has started.' })
                setMemo('')
                setCash('')
                setTyped('')
                setKey(crypto.randomUUID())
                onClose()
              },
            },
          )
        }}
      >
        <p className="text-caption text-text-2">
          Cancels their open orders, closes the current run and opens a new one. Their league series continues at the same unit value, so a reset never erases a loss.
        </p>
        <TextField label="Starting cash (optional)" prefix="$" inputMode="decimal" value={cash} onChange={(e) => setCash(e.target.value.replace(/[^\d.]/g, ''))} placeholder="League default" />
        <TextField label="Memo (shown to the player)" value={memo} onChange={(e) => setMemo(e.target.value)} maxLength={200} />
        <TextField label={`Type ${handle} to confirm`} value={typed} onChange={(e) => setTyped(e.target.value)} autoCapitalize="none" />
        {reset.error ? (
          <p role="alert" className="text-caption text-down">
            {describeError(reset.error).title}
          </p>
        ) : null}
        <Button type="submit" variant="danger" size="lg" block disabled={!memo.trim() || typed !== handle || reset.isPending}>
          {reset.isPending ? 'Resetting…' : 'Reset account'}
        </Button>
      </form>
    </Dialog>
  )
}

function PasswordDialog({ open, handle, onClose }: { open: boolean; handle: string; onClose: () => void }) {
  const setPassword = useSetPlayerPassword()
  const [pw, setPw] = useState('')
  return (
    <Dialog open={open} onClose={onClose} title={`New password for @${handle}`}>
      <form
        className="space-y-3 p-4"
        onSubmit={(e) => {
          e.preventDefault()
          setPassword.mutate(
            { handle, newPassword: pw },
            {
              onSuccess: () => {
                toast({ tone: 'neutral', title: 'Password set', body: 'Their sessions were signed out and personal keys revoked.' })
                setPw('')
                onClose()
              },
            },
          )
        }}
      >
        <TextField label="New password" type="password" autoComplete="new-password" value={pw} onChange={(e) => setPw(e.target.value)} hint="At least 10 characters. Share it privately." />
        {setPassword.error ? (
          <p role="alert" className="text-caption text-down">
            {describeError(setPassword.error).title}
          </p>
        ) : null}
        <Button type="submit" variant="primary" size="lg" block disabled={pw.length < 10 || setPassword.isPending}>
          Set password
        </Button>
      </form>
    </Dialog>
  )
}
