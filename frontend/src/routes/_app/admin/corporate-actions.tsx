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
  const navigate = Route.useNavigate()
  // the filter lives in the URL, so a reload or Back keeps it
  return (
    <CorporateActions
      filter={status ?? 'NEEDS_ADMIN'}
      onFilterChange={(next) => void navigate({ search: { status: next === 'NEEDS_ADMIN' ? undefined : next }, replace: true })}
    />
  )
}
