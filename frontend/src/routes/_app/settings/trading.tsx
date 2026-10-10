import { createFileRoute } from '@tanstack/react-router'
import { PagePlaceholder } from '@/components/PagePlaceholder'
import { SettingsHeader } from '@/features/settings/SettingsHeader'

export const Route = createFileRoute('/_app/settings/trading')({
  staticData: { phone: 'pushed' },
  component: () => (
    <div className="space-y-2">
      <SettingsHeader title="Trading defaults" />
      <PagePlaceholder className="px-0 desktop:px-0" title="Trading defaults" milestone="M3" layout="form" description="Order confirmation (review sheet on by default), default order type, TIF and quantity mode. Saved to your preferences (PATCH /me)." />
    </div>
  ),
})
