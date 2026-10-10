import { createFileRoute } from '@tanstack/react-router'
import { describeError } from '@/lib/api/errors'
import { useMe, useUpdatePreferences } from '@/lib/api/hooks/identity'
import { Segmented } from '@/components/ui/Segmented'
import { Switch } from '@/components/ui/Field'
import { SettingsHeader } from '@/features/settings/SettingsHeader'
import { toast } from '@/features/notifications/toastStore'

export const Route = createFileRoute('/_app/settings/trading')({
  staticData: { phone: 'pushed' },
  component: TradingDefaults,
})

/** Trading defaults (§22.4 Settings): order confirmation, default order type, TIF and quantity mode (PATCH /me). */
function TradingDefaults() {
  const { data: me } = useMe()
  const update = useUpdatePreferences()
  const p = me?.preferences
  const save = (patch: Parameters<typeof update.mutate>[0]) =>
    update.mutate(patch, { onError: (e) => toast({ tone: 'error', title: 'Not saved', body: describeError(e).title }) })
  return (
    <div className="space-y-4">
      <SettingsHeader title="Trading defaults" />
      <section className="divide-y divide-divider rounded-lg border border-divider bg-bg-1 px-4">
        <Switch
          className="py-2"
          checked={p?.confirmOrders ?? true}
          onChange={(confirmOrders) => save({ confirmOrders })}
          label="Review before sending"
          description="Show the review sheet with every field and the estimate before an order is placed."
        />
        <div className="flex flex-wrap items-center justify-between gap-3 py-3">
          <div>
            <h2 className="font-medium">Default order type</h2>
            <p className="text-caption text-text-3">What the ticket starts with.</p>
          </div>
          <Segmented
            label="Default order type"
            value={p?.defaultOrderType ?? 'LIMIT'}
            onChange={(defaultOrderType) => save({ defaultOrderType })}
            options={[
              { value: 'LIMIT', label: 'Limit' },
              { value: 'MARKET', label: 'Market' },
            ]}
          />
        </div>
        <div className="flex flex-wrap items-center justify-between gap-3 py-3">
          <div>
            <h2 className="font-medium">Default time in force</h2>
            <p className="text-caption text-text-3">GTC lasts 60 days; Market orders are always Day.</p>
          </div>
          <Segmented
            label="Default time in force"
            value={p?.defaultTimeInForce ?? 'DAY'}
            onChange={(defaultTimeInForce) => save({ defaultTimeInForce })}
            options={[
              { value: 'DAY', label: 'Day' },
              { value: 'GTC', label: 'GTC' },
            ]}
          />
        </div>
        <div className="flex flex-wrap items-center justify-between gap-3 py-3">
          <div>
            <h2 className="font-medium">Default quantity</h2>
            <p className="text-caption text-text-3">Dollar amounts buy fractional shares with a Market order.</p>
          </div>
          <Segmented
            label="Default quantity"
            value={p?.defaultQtyMode ?? 'SHARES'}
            onChange={(defaultQtyMode) => save({ defaultQtyMode })}
            options={[
              { value: 'SHARES', label: 'Shares' },
              { value: 'AMOUNT', label: 'Amount ($)' },
            ]}
          />
        </div>
      </section>
    </div>
  )
}
