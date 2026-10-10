import { createFileRoute } from '@tanstack/react-router'
import { PagePlaceholder } from '@/components/PagePlaceholder'

export const Route = createFileRoute('/_app/admin/audit')({
  component: () => (
    <PagePlaceholder
      className="px-0 desktop:px-0"
      title="Audit"
      milestone="M2"
      layout="table"
      description="Filterable audit_log viewer: category, action, actor, channel, API key, MCP client and tool, IP, request id."
    />
  ),
})
