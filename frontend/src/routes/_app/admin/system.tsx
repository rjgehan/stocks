import { useState } from 'react'
import { createFileRoute } from '@tanstack/react-router'
import { describeError, errorBody } from '@/lib/api/errors'
import { useHaltEngine, useResumeEngine, useRunJob, useSystemStatus } from '@/lib/api/hooks/admin'
import { formatDateTimeEt } from '@/lib/format/time'
import { Button } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { EmptyState } from '@/components/ui/EmptyState'
import { TextField } from '@/components/ui/Field'
import { Pill } from '@/components/ui/Pill'
import { Skeleton } from '@/components/ui/Skeleton'
import { IconRefresh } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'
import { toast } from '@/features/notifications/toastStore'

export const Route = createFileRoute('/_app/admin/system')({
  component: SystemPage,
})

function Card({ title, children, aside }: { title: string; children: React.ReactNode; aside?: React.ReactNode }) {
  return (
    <section className="rounded-lg border border-divider bg-bg-1 p-4">
      <div className="mb-2 flex items-center justify-between gap-2">
        <h2 className="text-section font-semibold">{title}</h2>
        {aside}
      </div>
      {children}
    </section>
  )
}

function Usage({ used, max }: { used: number; max: number }) {
  const pct = max > 0 ? Math.min(100, Math.round((used / max) * 100)) : 0
  return (
    <span className="flex items-center gap-2">
      <span className="h-1.5 w-20 overflow-hidden rounded-full bg-bg-3" aria-hidden="true">
        <span className={cx('block h-full rounded-full', pct > 85 ? 'bg-down' : pct > 60 ? 'bg-warn' : 'bg-up')} style={{ width: `${pct}%` }} />
      </span>
      <span className="text-caption">
        {used}/{max}
      </span>
    </span>
  )
}

/** Admin · System (§21.3): engine state and queue, invariants, market-data providers and budgets, jobs with "Run now", halt and resume. */
function SystemPage() {
  const { data: s, isPending, isError, refetch, isFetching } = useSystemStatus()
  const runJob = useRunJob()
  const halt = useHaltEngine()
  const resume = useResumeEngine()
  const [haltOpen, setHaltOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [jobConfirm, setJobConfirm] = useState<string | null>(null)
  if (isPending) return <Skeleton className="h-80 w-full" />
  if (isError || !s) return <EmptyState title="System status is unavailable" body="The backend may be restarting." />
  const engine = s.engine
  const halted = engine?.status === 'HALTED'
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2 text-caption text-text-2">
        <span>
          Version <span className="font-mono text-text-1">{s.version}</span>
        </span>
        <span>· started {formatDateTimeEt(s.startedAt)}</span>
        <span>· database {s.db}</span>
        <span className="flex-1" />
        <Button size="sm" variant="ghost" onClick={() => void refetch()} disabled={isFetching}>
          <IconRefresh size={16} /> Refresh
        </Button>
      </div>
      <div className="grid gap-4 desktop:grid-cols-2">
        <Card
          title="Trading engine"
          aside={<Pill tone={halted ? 'down' : engine?.status === 'RUNNING' ? 'up' : 'warn'}>{engine?.status ?? 'UNKNOWN'}</Pill>}
        >
          <dl className="space-y-1 text-table">
            <div className="flex justify-between">
              <dt className="text-text-2">Queue depth</dt>
              <dd>{engine?.queueDepth ?? '--'}</dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-text-2">Last command</dt>
              <dd>{formatDateTimeEt(engine?.lastCommandAt)}</dd>
            </div>
            {engine?.haltReason ? (
              <div className="flex justify-between gap-3">
                <dt className="text-text-2">Halt reason</dt>
                <dd className="text-right text-down">{engine.haltReason}</dd>
              </div>
            ) : null}
          </dl>
          <div className="mt-3">
            {halted ? (
              <Button
                variant="primary"
                disabled={resume.isPending}
                onClick={() =>
                  resume.mutate(undefined, {
                    onSuccess: () => toast({ tone: 'up', title: 'Trading resumed', body: 'Invariants were checked first.' }),
                    onError: (e) => toast({ tone: 'error', title: 'Not resumed', body: errorBody(describeError(e)) ?? describeError(e).title }),
                  })
                }
              >
                Resume trading
              </Button>
            ) : (
              <Button variant="danger" onClick={() => setHaltOpen(true)}>
                Halt trading…
              </Button>
            )}
          </div>
        </Card>
        <Card title="Invariants" aside={<Pill tone={s.invariants?.ok ? 'up' : 'down'}>{s.invariants?.ok ? 'OK' : 'FAILING'}</Pill>}>
          <p className="text-caption text-text-2">Last run {formatDateTimeEt(s.invariants?.lastRunAt)}</p>
          {(s.invariants?.failures ?? []).length ? (
            <ul className="mt-2 list-disc space-y-0.5 pl-5 text-caption text-down">
              {(s.invariants?.failures ?? []).map((f) => (
                <li key={f}>{f}</li>
              ))}
            </ul>
          ) : (
            <p className="mt-1 text-caption text-text-3">Cash, positions, lots and units all reconcile.</p>
          )}
          {s.corporateActionsNeedingAdmin ? <p className="mt-2 text-caption text-warn">{s.corporateActionsNeedingAdmin} corporate action(s) need a decision.</p> : null}
        </Card>
      </div>
      <Card title="Market data" aside={<span className="font-mono text-caption text-text-2">{s.marketData?.profile}</span>}>
        <div className="overflow-x-auto">
          <table className="w-full text-table">
            <thead className="text-left text-micro tracking-wide text-text-3 uppercase">
              <tr className="h-8 border-b border-divider">
                <th className="font-medium">Provider</th>
                <th className="px-2 font-medium">State</th>
                <th className="px-2 font-medium">Stream symbols</th>
                <th className="px-2 font-medium">REST / min</th>
                <th className="px-2 font-medium">Last message</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-divider">
              {(s.marketData?.providers ?? []).map((p) => (
                <tr key={p.id} className="h-10">
                  <td className="font-semibold">{p.id}</td>
                  <td className="px-2">
                    <Pill tone={p.state === 'LIVE' || p.state === 'CONNECTED' ? 'up' : p.state === 'DISABLED' ? 'flat' : 'warn'}>{p.state}</Pill>
                  </td>
                  <td className="px-2">
                    <Usage used={p.streamSymbolsUsed} max={p.streamSymbolsMax} />
                  </td>
                  <td className="px-2">
                    <Usage used={p.restPerMinUsed} max={p.restPerMinMax} />
                  </td>
                  <td className="px-2 text-caption text-text-3">{formatDateTimeEt(p.lastMessageAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {(s.marketData?.staleSymbols ?? []).length ? <p className="mt-2 text-caption text-warn">Stale: {(s.marketData?.staleSymbols ?? []).join(', ')}</p> : null}
        {(s.marketData?.corporateActionHolds ?? []).length ? <p className="mt-1 text-caption text-warn">On corporate-action hold: {(s.marketData?.corporateActionHolds ?? []).join(', ')}</p> : null}
      </Card>
      <Card title="Jobs">
        <ul className="divide-y divide-divider">
          {(s.jobs ?? []).map((j) => (
            <li key={j.name} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2 text-table">
              <span className="font-mono font-semibold">{j.name}</span>
              {j.status ? <Pill tone={j.status === 'SUCCEEDED' ? 'up' : j.status === 'FAILED' ? 'down' : 'neutral'}>{j.status}</Pill> : null}
              <span className="text-caption text-text-3">
                {j.lastRunKey ? `last ${j.lastRunKey}` : 'never run'}
                {j.finishedAt ? ` · ${formatDateTimeEt(j.finishedAt)}` : ''}
                {j.detail ? ` · ${j.detail}` : ''}
              </span>
              <span className="flex-1" />
              <Button size="sm" disabled={runJob.isPending} onClick={() => setJobConfirm(j.name)}>
                Run now
              </Button>
            </li>
          ))}
        </ul>
      </Card>

      <ConfirmDialog
        open={haltOpen}
        onClose={() => setHaltOpen(false)}
        title="Halt all trading?"
        tone="danger"
        confirmLabel="Halt trading"
        pending={halt.isPending}
        onConfirm={() => {
          if (!reason.trim()) return
          halt.mutate(reason.trim(), {
            onSuccess: () => {
              setHaltOpen(false)
              setReason('')
              toast({ tone: 'warn', title: 'Trading halted' })
            },
          })
        }}
      >
        <p>New orders are refused with ENGINE_HALTED until you resume. Open orders stay open.</p>
        <TextField label="Reason (audited)" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={200} />
      </ConfirmDialog>
      <ConfirmDialog
        open={jobConfirm !== null}
        onClose={() => setJobConfirm(null)}
        title={`Run ${jobConfirm ?? ''} now?`}
        confirmLabel="Run job"
        pending={runJob.isPending}
        onConfirm={() =>
          jobConfirm &&
          runJob.mutate(jobConfirm, {
            onSuccess: () => {
              toast({ tone: 'neutral', title: `${jobConfirm} started` })
              setJobConfirm(null)
            },
            onError: (e) => toast({ tone: 'error', title: 'Job not started', body: describeError(e).title }),
          })
        }
      >
        Runs the latest past occurrence. Jobs are idempotent, so a duplicate run is harmless.
      </ConfirmDialog>
    </div>
  )
}
