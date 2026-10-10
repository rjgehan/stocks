import { createFileRoute } from '@tanstack/react-router'
import { PagePlaceholder } from '@/components/PagePlaceholder'
import { SettingsHeader } from '@/features/settings/SettingsHeader'

export const Route = createFileRoute('/_app/settings/account')({
  staticData: { phone: 'pushed' },
  component: () => (
    <div className="space-y-2">
      <SettingsHeader title="Account" />
      <PagePlaceholder className="px-0 desktop:px-0" title="Account" milestone="M3" layout="form" description="Change password, and reset your account (type RESET) when the league allows self-reset. A reset never erases a period's loss." />
    </div>
  ),
})
