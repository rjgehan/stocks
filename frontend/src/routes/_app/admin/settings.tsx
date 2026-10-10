import { createFileRoute } from '@tanstack/react-router'
import { PagePlaceholder } from '@/components/PagePlaceholder'

export const Route = createFileRoute('/_app/admin/settings')({
  component: () => (
    <PagePlaceholder
      className="px-0 desktop:px-0"
      title="League settings"
      milestone="M3"
      layout="form"
      description="Default starting cash, self-reset, season start, eligibility, feed delay, holdings visibility, show deposits, require rationale."
    />
  ),
})
