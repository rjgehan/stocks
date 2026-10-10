/**
 * The REST client (§22.1): openapi-fetch over the generated schema, plus
 *  - CSRF: the raw `XSRF-TOKEN` cookie echoed as `X-XSRF-TOKEN` on unsafe methods (§17.3), with one
 *    refresh-and-retry on `403 CSRF_INVALID`;
 *  - problem details: every non-2xx response becomes an `ApiError` carrying the stable §7.2 `code`;
 *  - mock mode: `VITE_API_MOCK=1` routes requests to the in-memory fake in ./mock (§22.9). The mock is
 *    dynamically imported inside a branch that is constant-folded away in normal builds, so it never
 *    ships to production.
 */
import createClient from 'openapi-fetch'
import type { paths } from './contract'
import { CSRF_PATH, withCsrf } from './csrf'
import { ApiError, networkError, toProblem } from './problem'

export const API_MOCK: boolean = import.meta.env.VITE_API_MOCK === '1'

export type Transport = (request: Request) => Promise<Response>

function defaultBaseUrl(): string {
  const origin = typeof window === 'undefined' ? undefined : window.location?.origin
  return origin && origin !== 'null' ? origin : 'http://localhost'
}

function defaultTransport(): Transport {
  if (import.meta.env.VITE_API_MOCK === '1') {
    return async (request) => (await import('./mock')).mockFetch(request)
  }
  return (request) => globalThis.fetch(request)
}

export interface ApiClientOptions {
  /** Origin the `/api/v1/...` paths are resolved against (default: the page origin; single-origin app). */
  baseUrl?: string
  /** The actual network layer (default: `fetch`, or the mock in mock mode). */
  transport?: Transport
}

export function createApiClient(options: ApiClientOptions = {}) {
  const baseUrl = options.baseUrl ?? defaultBaseUrl()
  const csrfTransport = withCsrf(options.transport ?? defaultTransport(), { csrfUrl: `${baseUrl}${CSRF_PATH}` })
  const fetchWithErrors: Transport = async (request) => {
    try {
      return await csrfTransport(request)
    } catch (e) {
      throw e instanceof ApiError ? e : networkError(e)
    }
  }
  return createClient<paths>({
    baseUrl,
    fetch: fetchWithErrors,
    credentials: 'same-origin',
    headers: { Accept: 'application/json, application/problem+json' },
  })
}

export type ApiClient = ReturnType<typeof createApiClient>

/** The app-wide client. */
export const api: ApiClient = createApiClient()

interface FetchResult<T> {
  data?: T
  error?: unknown
  response: Response
}

/**
 * Await an openapi-fetch call and return its data, or throw an `ApiError` built from the problem body.
 * Use inside TanStack Query `queryFn`/`mutationFn` so errors flow into query state.
 */
export async function unwrap<T>(call: Promise<FetchResult<T>>): Promise<T> {
  const { data, error, response } = await call
  if (response.ok) return data as T
  throw new ApiError(toProblem(error ?? null, response))
}
