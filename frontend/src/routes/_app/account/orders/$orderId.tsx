import { createFileRoute } from '@tanstack/react-router'
import { PagePlaceholder } from '@/components/PagePlaceholder'

export const Route = createFileRoute('/_app/account/orders/$orderId')({
  staticData: { phone: 'pushed' },
  component: OrderDetail,
})

/** Order detail (§22.4): event timeline, fills with liquidity tags, fee breakdown, quote used, rationale, channel, waitReason. M1. */
function OrderDetail() {
  const { orderId } = Route.useParams()
  return (
    <PagePlaceholder
      className="px-0 desktop:px-0"
      title={`Order ${orderId.slice(0, 12)}…`}
      milestone="M1"
      layout="cards"
      description="Timeline of every event, fills with liquidity tags, the fee breakdown (SEC · TAF · CAT), the quote used, the rationale, the channel and the wait reason."
    />
  )
}
