import { useMemo, useState } from 'react'
import { createFileRoute } from '@tanstack/react-router'
import { useAudit } from '@/lib/api/hooks/admin'
import type { AuditRow } from '@/lib/api/types'
import { formatDateTimeEt } from '@/lib/format/time'
import { useDebounced } from '@/lib/useDebounced'
import { Button } from '@/components/ui/Button'
import { EmptyState } from '@/components/ui/EmptyState'
import { SelectField, TextField } from '@/components/ui/Field'
import { Pill } from '@/components/ui/Pill'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { cx } from '@/components/ui/cx'

export const Route = createFileRoute('/_app/admin/audit')({
  component: AuditPage,
})

const CATEGORIES = ['', 'AUTH', 'ADMIN', 'MONEY', 'ORDER', 'KEY', 'SYSTEM']

function AuditItem({ a }: { a: AuditRow }) {
  const [open, setOpen] = useState(false)
  return (
    <li className="px-4 py-2.5">
      <button type="button" onClick={() => setOpen((o) => !o)} aria-expanded={open} className="flex w-full flex-wrap items-center gap-x-3 gap-y-1 text-left text-table">
        <Pill tone={a.outcome === 'OK' ? 'neutral' : 'down'}>{a.category}</Pill>
        <span className="font-semibold">{a.action}</span>
        <span className="text-text-2">{a.actor ? `@${a.actor}` : 'system'}</span>
        {a.targetType ? (
          <span className="text-caption text-text-3">
            → {a.targetType}
            {a.targetId ? ` ${a.targetId}` : ''}
          </span>
        ) : null}
        {a.outcome !== 'OK' ? <Pill tone="down">{a.outcome}</Pill> : null}
        <span className="ml-auto text-caption text-text-3">{formatDateTimeEt(a.occurredAt)}</span>
      </button>
      {open ? (
        <div className="mt-2 space-y-1 rounded-md bg-bg-2 p-2 text-caption text-text-2">
          <p>
            Channel {a.channel ?? '--'} · IP {a.ip ?? '--'} · request <span className="font-mono">{a.requestId ?? '--'}</span>
            {a.apiKeyId ? ` · key ${a.apiKeyId}` : ''}
            {a.mcpClient ? ` · MCP ${a.mcpClient}${a.mcpTool ? `/${a.mcpTool}` : ''}` : ''}
          </p>
          {a.detail ? <pre className="overflow-x-auto font-mono text-micro whitespace-pre-wrap text-text-1">{JSON.stringify(a.detail, null, 2)}</pre> : null}
        </div>
      ) : null}
    </li>
  )
}

/** Admin · Audit (§21.3): the filterable audit_log viewer (category, actor, action), newest first, with request details. */
function AuditPage() {
  const [category, setCategory] = useState('')
  const [actor, setActor] = useState('')
  const [action, setAction] = useState('')
  const raw = useMemo(() => ({ category, actor: actor.trim().replace(/^@/, '').toLowerCase(), action: action.trim().toUpperCase() }), [category, actor, action])
  const filter = useDebounced(raw, 300)
  const q = useAudit(filter)
  const rows = (q.data?.pages ?? []).flatMap((p) => p.items ?? [])
  return (
    <div className="space-y-3">
      <div className="grid gap-3 tablet:grid-cols-3">
        <SelectField label="Category" value={category} onChange={(e) => setCategory(e.target.value)} options={CATEGORIES.map((c) => ({ value: c, label: c || 'All categories' }))} />
        <TextField label="Actor" value={actor} onChange={(e) => setActor(e.target.value)} placeholder="handle" autoCapitalize="none" />
        <TextField label="Action contains" value={action} onChange={(e) => setAction(e.target.value)} placeholder="TRANSFER" autoCapitalize="characters" />
      </div>
      <section className={cx('rounded-lg border border-divider bg-bg-1')}>
        {q.isPending ? (
          <div className="px-4">
            <SkeletonRows rows={6} cols={4} />
          </div>
        ) : q.isError ? (
          <EmptyState title="The audit log is unavailable right now" />
        ) : rows.length === 0 ? (
          <EmptyState title="No audit rows match" />
        ) : (
          <ul className="divide-y divide-divider" aria-label="Audit log">
            {rows.map((a) => (
              <AuditItem key={a.id} a={a} />
            ))}
          </ul>
        )}
        {q.hasNextPage ? (
          <div className="flex justify-center border-t border-divider p-3">
            <Button size="sm" onClick={() => void q.fetchNextPage()} disabled={q.isFetchingNextPage}>
              Load more
            </Button>
          </div>
        ) : null}
      </section>
    </div>
  )
}
