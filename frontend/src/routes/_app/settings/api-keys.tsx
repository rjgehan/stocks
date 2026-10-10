import { createFileRoute } from '@tanstack/react-router'
import { PagePlaceholder } from '@/components/PagePlaceholder'
import { SettingsHeader } from '@/features/settings/SettingsHeader'

export const Route = createFileRoute('/_app/settings/api-keys')({
  staticData: { phone: 'pushed' },
  component: () => (
    <div className="space-y-2">
      <SettingsHeader title="API keys" />
      <PagePlaceholder className="px-0 desktop:px-0" title="API keys" milestone="M2" layout="form" description="Personal keys are read-only unless the admin allows API trading; shown once; revoked on password change. Requires re-authentication." />
    </div>
  ),
})
