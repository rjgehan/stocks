import { createFileRoute } from '@tanstack/react-router'
import { PagePlaceholder } from '@/components/PagePlaceholder'

export const Route = createFileRoute('/_app/admin/invites')({
  component: () => (
    <PagePlaceholder
      className="px-0 desktop:px-0"
      title="Invites"
      milestone="M1"
      layout="table"
      description="Create an invite (copy link), list and revoke."
    />
  ),
})
