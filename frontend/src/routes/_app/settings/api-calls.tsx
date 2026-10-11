import { Link, createFileRoute } from '@tanstack/react-router'
import { ApiCallsList } from '@/features/agents/ApiCallsList'
import { SettingsHeader } from '@/features/settings/SettingsHeader'

export const Route = createFileRoute('/_app/settings/api-calls')({
  staticData: { phone: 'pushed' },
  component: ApiCallsPage,
})

/** Settings · Recent API calls (Phase 2, §17.10): what your API keys and your bots did, per hour. */
function ApiCallsPage() {
  return (
    <div className="space-y-4">
      <SettingsHeader title="Recent API calls" />
      <p className="text-caption text-text-2">
        Calls made with your <Link to="/settings/api-keys" className="text-accent">API keys</Link> and by the AI players you own, over REST and MCP, grouped per hour.
      </p>
      <ApiCallsList />
    </div>
  )
}
