import { createFileRoute } from '@tanstack/react-router'
import { PagePlaceholder } from '@/components/PagePlaceholder'

export const Route = createFileRoute('/_app/admin/system')({
  component: () => (
    <PagePlaceholder
      className="px-0 desktop:px-0"
      title="System"
      milestone="M2"
      layout="cards"
      description="Engine state and queue, invariants, provider stream usage and REST budget, probe results, stale symbols, corporate-action holds, jobs with “Run now”, halt and resume, calendar overrides, corporate actions needing admin."
    />
  ),
})
