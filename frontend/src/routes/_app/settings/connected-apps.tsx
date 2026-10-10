import { createFileRoute } from '@tanstack/react-router'
import { ConnectedApps } from '@/features/oauth/ConnectedApps'
import { SettingsHeader } from '@/features/settings/SettingsHeader'

export const Route = createFileRoute('/_app/settings/connected-apps')({
  staticData: { phone: 'pushed' },
  component: ConnectedAppsPage,
})

/** Settings → Connected apps (§17.9.1): claude.ai and ChatGPT connectors you approved, and Disconnect. */
function ConnectedAppsPage() {
  return (
    <div className="space-y-4">
      <SettingsHeader title="Connected apps" />
      <p className="text-caption text-text-2">
        Apps you connected with “Sign in with stocks”: the claude.ai and ChatGPT connectors, Claude Code and other MCP clients. Each acts as you or as one of your AI bots, with the permissions you
        chose. They never get admin powers.
      </p>
      <section className="rounded-lg border border-divider bg-bg-1">
        <h2 className="px-4 pt-3 text-section font-semibold">Your connected apps</h2>
        <ConnectedApps />
      </section>
    </div>
  )
}
