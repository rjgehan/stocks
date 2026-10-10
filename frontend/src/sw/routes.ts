/**
 * Pure rules of the service worker (§22.10), shared with its unit tests:
 *  - which navigations the offline shell may answer (never the server's own paths);
 *  - how a push payload becomes a system notification;
 *  - where a notification click may go (same-origin paths only).
 */

/**
 * Server paths a navigation must reach untouched: the API (private data is never cached or faked), MCP, the
 * WebSocket, OpenAPI and Swagger UI, OAuth, actuator and the dev H2 console.
 */
export const NAVIGATION_DENYLIST: RegExp[] = [
  /^\/api(\/|$)/,
  /^\/mcp(\/|$)/,
  /^\/ws(\/|$)/,
  /^\/v3(\/|$)/,
  /^\/swagger-ui(\/|$)/,
  /^\/oauth2(\/|$)/,
  /^\/login\/oauth2(\/|$)/,
  /^\/\.well-known(\/|$)/,
  /^\/h2-console(\/|$)/,
  /^\/actuator(\/|$)/,
]

/** Whether the offline shell (`index.html`) may answer a navigation to `pathname`. */
export function shellMayAnswer(pathname: string): boolean {
  return !NAVIGATION_DENYLIST.some((re) => re.test(pathname))
}

export interface ShownNotification {
  title: string
  body: string
  tag: string | undefined
  url: string
}

const str = (v: unknown, max: number): string | undefined => (typeof v === 'string' && v.trim() !== '' ? v.slice(0, max) : undefined)

const FALLBACK: ShownNotification = { title: 'stocks', body: 'You have a new notification.', tag: undefined, url: '/' }

/**
 * The §19.2 payload `{v: 1, kind, title, body, url, tag}` as a notification. Anything unexpected (no payload, not
 * JSON, another version) still shows something, because browsers penalize a push that shows no notification.
 */
export function notificationFromPush(text: string | null | undefined): ShownNotification {
  if (!text) return FALLBACK
  let data: unknown
  try {
    data = JSON.parse(text)
  } catch {
    return FALLBACK
  }
  if (typeof data !== 'object' || data === null) return FALLBACK
  const d = data as Record<string, unknown>
  return {
    title: str(d.title, 120) ?? FALLBACK.title,
    body: str(d.body, 240) ?? '',
    tag: str(d.tag, 64),
    url: typeof d.url === 'string' ? d.url : '/',
  }
}

/** A notification click target: a same-origin path, never another site (`//evil`, `https://…`, `javascript:`). */
export function safeTarget(url: unknown, origin: string): string {
  if (typeof url !== 'string' || !url.startsWith('/') || url.startsWith('//') || url.includes('\\')) return `${origin}/`
  try {
    const resolved = new URL(url, origin)
    return resolved.origin === origin ? resolved.href : `${origin}/`
  } catch {
    return `${origin}/`
  }
}
