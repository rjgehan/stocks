import { createFileRoute } from '@tanstack/react-router'
import { PagePlaceholder } from '@/components/PagePlaceholder'

export const Route = createFileRoute('/_app/admin/players/')({
  component: () => (
    <PagePlaceholder
      className="px-0 desktop:px-0"
      title="Players"
      milestone="M2"
      description="Handle, kind badge, status, equity, cash, net contributions, P&L, return, open orders, keys, last seen. Create a human or AI player (issue key now), disable/enable, freeze/unfreeze, reset, promote a human to admin."
    />
  ),
})
