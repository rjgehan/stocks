import { Link } from '@tanstack/react-router'
import { AuthCard } from '@/features/auth/AuthCard'

const COPY: Record<string, string> = {
  invalid_client: 'The app that sent you here is not one this server accepts, or its details could not be loaded.',
  invalid_request: 'The connection request was incomplete or did not match what the app registered.',
  not_found: 'Connecting apps is switched off on this server.',
  slow_down: 'Too many connection attempts from your network. Wait a minute and try again.',
}

/**
 * `/oauth/error` (§17.9.3): authorization errors that must not be sent back to the app (an unknown client or an
 * unregistered redirect URI could make this server an open redirector). The description comes from the server.
 */
export function OAuthErrorScreen({ error, description }: { error: string; description: string }) {
  return (
    <AuthCard title="Could not connect the app" subtitle="stocks">
      <p role="alert" className="text-body">
        {COPY[error] ?? 'The connection request could not be completed.'}
      </p>
      {description ? <p className="rounded-sm bg-bg-2 p-2 font-mono text-micro text-text-2">{description.slice(0, 300)}</p> : null}
      <p className="text-caption text-text-2">Start again from Claude or ChatGPT. If it keeps failing, tell the league admin.</p>
      <Link to="/" className="font-semibold text-accent">
        Go to stocks
      </Link>
    </AuthCard>
  )
}
