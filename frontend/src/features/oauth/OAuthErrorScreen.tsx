import { Link } from '@tanstack/react-router'
import { AuthCard } from '@/features/auth/AuthCard'

/** Fixed copy per RFC 6749 error code; anything else gets the generic sentence. */
export const OAUTH_ERROR_COPY: Record<string, string> = {
  invalid_client: 'The app that sent you here is not one this server accepts, or its details could not be loaded.',
  invalid_request: 'The connection request was incomplete or did not match what the app registered.',
  not_found: 'Connecting apps is switched off on this server.',
  slow_down: 'Too many connection attempts from your network. Wait a minute and try again.',
  temporarily_unavailable: 'Too many connections are being set up right now. Wait a few minutes and try again.',
  server_error: 'Something went wrong on the server. Try again in a moment.',
}

export const OAUTH_ERROR_FALLBACK = 'The connection request could not be completed.'

/**
 * `/oauth/error` (§17.9.3): authorization errors that must not be sent back to the app (an unknown client or an
 * unregistered redirect URI could make this server an open redirector). Only fixed copy keyed by the `error` code is
 * shown: the URL is attacker-controllable, so its free-text `error_description` is never displayed on this trusted
 * origin (content spoofing; the server logs the details).
 */
export function OAuthErrorScreen({ error }: { error: string }) {
  const known = Object.hasOwn(OAUTH_ERROR_COPY, error)
  return (
    <AuthCard title="Could not connect the app" subtitle="stocks">
      <p role="alert" className="text-body">
        {known ? OAUTH_ERROR_COPY[error] : OAUTH_ERROR_FALLBACK}
      </p>
      {known ? <p className="text-micro text-text-3">Error code: {error}</p> : null}
      <p className="text-caption text-text-2">Start again from Claude or ChatGPT. If it keeps failing, tell the league admin.</p>
      <Link to="/" className="font-semibold text-accent">
        Go to stocks
      </Link>
    </AuthCard>
  )
}
