import { createFileRoute } from '@tanstack/react-router'
import type { CorporateActionFilter } from '@/lib/api/hooks/admin'
import { CorporateActions } from '@/features/admin/CorporateActions'

const STATUSES: readonly CorporateActionFilter[] = ['NEEDS_ADMIN', 'PENDING', 'APPLIED', 'SKIPPED', 'ALL']

export const Route = createFileRoute('/_app/admin/corporate-actions')({
  validateSearch: (s: Record<string, unknown>): { status?: CorporateActionFilter } => ({
    status: STATUSES.find((v) => v === s.status),
  }),
  component: CorporateActionsPage,
})

/** Admin · Corporate actions (§21.3 System "corporate actions needing admin", given its own tab): list and resolve. */
function CorporateActionsPage() {
  const { status } = Route.useSearch()
  return <CorporateActions initialFilter={status ?? 'NEEDS_ADMIN'} />
}
