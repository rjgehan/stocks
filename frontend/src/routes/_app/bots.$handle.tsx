import { createFileRoute } from '@tanstack/react-router'
import { PushedHeader } from '@/app/PushedHeader'
import { useBreakpoint } from '@/app/useBreakpoint'
import { ManualConsole } from '@/features/manualbot/ManualConsole'

const SESSION_ID = /^mbs_[0-9A-HJKMNP-TV-Z]{26}$/

/** `/bots/$handle?session` (§20.8, §22.2): an AI bot's manual console, for its owner or the admin. */
export const Route = createFileRoute('/_app/bots/$handle')({
  validateSearch: (s: Record<string, unknown>): { session?: string } => ({
    session: typeof s.session === 'string' && SESSION_ID.test(s.session) ? s.session : undefined,
  }),
  staticData: { chrome: 'full', phone: 'pushed', title: 'Bot console' },
  component: BotConsolePage,
})

function BotConsolePage() {
  const { handle } = Route.useParams()
  const { session } = Route.useSearch()
  const navigate = Route.useNavigate()
  const bp = useBreakpoint()
  return (
    <div className="scrollbar-thin h-full overflow-y-auto">
      {bp === 'phone' ? <PushedHeader title="Console" subtitle={`@${handle}`} /> : null}
      <div className="mx-auto w-full max-w-6xl px-4 py-4 desktop:px-6">
        <ManualConsole handle={handle} sessionId={session} onSession={(id) => void navigate({ search: { session: id }, replace: true })} />
      </div>
    </div>
  )
}
