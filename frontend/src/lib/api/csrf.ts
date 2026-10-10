/**
 * CSRF for the session-cookie channel (§17.1, §17.3): Spring's CookieCsrfTokenRepository.withHttpOnlyFalse()
 * sets the `XSRF-TOKEN` cookie; every unsafe request echoes its RAW value in `X-XSRF-TOKEN`.
 * The same raw value goes into the STOMP CONNECT headers (§19).
 */
export const CSRF_COOKIE = 'XSRF-TOKEN'
export const CSRF_HEADER = 'X-XSRF-TOKEN'
export const CSRF_PATH = '/api/v1/auth/csrf'

const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

export function isUnsafeMethod(method: string): boolean {
  return UNSAFE_METHODS.has(method.toUpperCase())
}

export function readCookie(name: string, cookieString: string = typeof document === 'undefined' ? '' : document.cookie): string | null {
  for (const part of cookieString.split(';')) {
    const eq = part.indexOf('=')
    if (eq < 0) continue
    if (part.slice(0, eq).trim() === name) {
      const raw = part.slice(eq + 1).trim()
      try {
        return decodeURIComponent(raw)
      } catch {
        return raw
      }
    }
  }
  return null
}

export const readCsrfToken = (): string | null => readCookie(CSRF_COOKIE)

type Transport = (request: Request) => Promise<Response>

export interface CsrfOptions {
  /** Absolute URL of `GET /api/v1/auth/csrf`. */
  csrfUrl: string
  readToken?: () => string | null
}

/**
 * Wraps a transport so unsafe requests carry the CSRF header. If the cookie is missing it first calls
 * `GET /auth/csrf`; on `403 CSRF_INVALID` (rotated or expired token) it refreshes once and retries.
 */
export function withCsrf(transport: Transport, { csrfUrl, readToken = readCsrfToken }: CsrfOptions): Transport {
  let inflight: Promise<void> | null = null

  async function refresh(): Promise<void> {
    inflight ??= transport(new Request(csrfUrl, { method: 'GET', credentials: 'same-origin' }))
      .then(() => undefined)
      .finally(() => {
        inflight = null
      })
    await inflight
  }

  async function token(force: boolean): Promise<string | null> {
    const current = readToken()
    if (current && !force) return current
    await refresh()
    return readToken()
  }

  return async (request) => {
    if (!isUnsafeMethod(request.method)) return transport(request)
    const retry = request.clone()
    const first = await token(false)
    if (first) request.headers.set(CSRF_HEADER, first)
    const response = await transport(request)
    if (response.status !== 403 || !(await isCsrfProblem(response))) return response
    const fresh = await token(true)
    if (fresh) retry.headers.set(CSRF_HEADER, fresh)
    return transport(retry)
  }
}

async function isCsrfProblem(response: Response): Promise<boolean> {
  try {
    const body: unknown = await response.clone().json()
    return typeof body === 'object' && body !== null && (body as { code?: unknown }).code === 'CSRF_INVALID'
  } catch {
    return false
  }
}
