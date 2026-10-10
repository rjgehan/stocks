import { createFileRoute } from '@tanstack/react-router'
import { PagePlaceholder } from '@/components/PagePlaceholder'

export const Route = createFileRoute('/_app/admin/money')({
  component: () => (
    <PagePlaceholder
      className="px-0 desktop:px-0"
      title="Money"
      milestone="M2"
      layout="form"
      description="Deposit, withdrawal or adjustment to one player or ALL (by kind), with memo, treat-as for adjustments and all-or-nothing. Live preview lists cash, withdrawable, E⁻, U and the worst mark quality; degraded marks need an explicit override; confirmation requires typing the amount."
    />
  ),
})
