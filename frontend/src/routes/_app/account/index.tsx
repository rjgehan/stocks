import { createFileRoute } from '@tanstack/react-router'
import { useAccount } from '@/lib/api/hooks/account'
import { Pill } from '@/components/ui/Pill'
import { Skeleton } from '@/components/ui/Skeleton'
import { BalancesGrid } from '@/features/account/BalancesGrid'

export const Route = createFileRoute('/_app/account/')({
  component: AccountOverview,
})

function AccountOverview() {
  const { data: a } = useAccount()
  return (
    <div className="space-y-4">
      <BalancesGrid />
      <section className="flex items-center justify-between rounded-lg border border-divider bg-bg-1 p-4">
        <div>
          <h2 className="text-section font-semibold">Good-faith violations</h2>
          <p className="text-caption text-text-2">3 active → settled cash only · 4 → liquidation only</p>
        </div>
        <Pill tone={a && a.gfv.active > 0 ? 'warn' : 'up'}>{a ? `${a.gfv.active}/3 active` : '…'}</Pill>
      </section>
      <section className="rounded-lg border border-divider bg-bg-1 p-4">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-section font-semibold">Equity vs SPY</h2>
          <Pill tone="neutral">M3</Pill>
        </div>
        <Skeleton className="h-48 w-full" />
      </section>
    </div>
  )
}
