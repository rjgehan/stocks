import { describe, expect, it, vi } from 'vitest'
import { createApiClient, unwrap, type Transport } from './client'
import { CSRF_HEADER } from './csrf'
import { type ApiError, isApiError } from './problem'

const BASE = 'http://localhost'

function json(status: number, body: unknown, contentType = 'application/json'): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } })
}

function setCookie(value: string) {
  document.cookie = `XSRF-TOKEN=${value}; path=/`
}

/** A fake server: records requests (with their bodies) and answers through `handler`. */
function fakeServer(handler: (req: Request, body: string) => Response | Promise<Response>) {
  const seen: { method: string; path: string; csrf: string | null; body: string }[] = []
  const transport = vi.fn<Transport>(async (req: Request) => {
    const body = req.method === 'GET' ? '' : await req.text()
    const url = new URL(req.url)
    seen.push({ method: req.method, path: url.pathname, csrf: req.headers.get(CSRF_HEADER), body })
    return handler(req, body)
  })
  return { seen, transport }
}

const meBody = { playerId: 'ply_1', handle: 'demo' }

const failingTransport: Transport = () => Promise.reject(new TypeError('Failed to fetch'))

describe('api client: CSRF (§17.3)', () => {
  it('does not send the CSRF header on safe methods', async () => {
    setCookie('tok-1')
    const { seen, transport } = fakeServer(() => json(200, meBody))
    const api = createApiClient({ baseUrl: BASE, transport })
    await unwrap(api.GET('/api/v1/me'))
    expect(seen).toEqual([{ method: 'GET', path: '/api/v1/me', csrf: null, body: '' }])
  })

  it('echoes the raw XSRF-TOKEN cookie on unsafe methods', async () => {
    setCookie('raw-token-value')
    const { seen, transport } = fakeServer(() => json(200, meBody))
    const api = createApiClient({ baseUrl: BASE, transport })
    await unwrap(api.POST('/api/v1/auth/login', { body: { handle: 'demo', password: 'pw' } }))
    expect(seen).toHaveLength(1)
    expect(seen[0]).toMatchObject({ method: 'POST', path: '/api/v1/auth/login', csrf: 'raw-token-value' })
    expect(JSON.parse(seen[0]!.body)).toEqual({ handle: 'demo', password: 'pw' })
  })

  it('fetches /auth/csrf first when the cookie is missing', async () => {
    const { seen, transport } = fakeServer((req) => {
      if (new URL(req.url).pathname === '/api/v1/auth/csrf') {
        setCookie('fresh')
        return new Response(null, { status: 204 })
      }
      return json(200, meBody)
    })
    const api = createApiClient({ baseUrl: BASE, transport })
    await unwrap(api.POST('/api/v1/auth/logout'))
    expect(seen.map((s) => `${s.method} ${s.path} ${s.csrf}`)).toEqual([
      'GET /api/v1/auth/csrf null',
      'POST /api/v1/auth/logout fresh',
    ])
  })

  it('refreshes the token and retries once on 403 CSRF_INVALID, resending the body', async () => {
    setCookie('stale')
    let posts = 0
    const { seen, transport } = fakeServer((req) => {
      if (new URL(req.url).pathname === '/api/v1/auth/csrf') {
        setCookie('rotated')
        return new Response(null, { status: 204 })
      }
      posts += 1
      return posts === 1
        ? json(403, { title: 'Forbidden', status: 403, code: 'CSRF_INVALID' }, 'application/problem+json')
        : json(201, { replayed: false })
    })
    const api = createApiClient({ baseUrl: BASE, transport })
    const body = { clientOrderId: 'web-1', symbol: 'AAPL', side: 'BUY', type: 'MARKET', qty: '1' } as const
    await unwrap(api.POST('/api/v1/orders', { body }))
    expect(seen.map((s) => `${s.method} ${s.path} ${s.csrf}`)).toEqual([
      'POST /api/v1/orders stale',
      'GET /api/v1/auth/csrf null',
      'POST /api/v1/orders rotated',
    ])
    expect(JSON.parse(seen[2]!.body)).toEqual(body)
  })

  it('does not retry other 403s', async () => {
    setCookie('tok')
    const { seen, transport } = fakeServer(() =>
      json(403, { title: 'Forbidden', status: 403, code: 'PLAYER_FROZEN' }, 'application/problem+json'),
    )
    const api = createApiClient({ baseUrl: BASE, transport })
    await expect(unwrap(api.POST('/api/v1/auth/logout'))).rejects.toMatchObject({ code: 'PLAYER_FROZEN', status: 403 })
    expect(seen).toHaveLength(1)
  })
})

describe('api client: problem details (§6.5)', () => {
  it('maps a 422 problem to an ApiError with fields and hint', async () => {
    setCookie('tok')
    const problem = {
      type: '/errors/INSUFFICIENT_BUYING_POWER',
      title: 'Insufficient buying power',
      status: 422,
      code: 'INSUFFICIENT_BUYING_POWER',
      detail: 'Order needs $231.96; buying power is $198.10.',
      hint: 'Try a notional (dollar) market order of "198.10".',
      fields: [{ field: 'qty', code: 'INSUFFICIENT_BUYING_POWER', message: 'Too many shares' }],
      retryAfterS: null,
      requestId: 'req_01J9Z',
    }
    const { transport } = fakeServer(() => json(422, problem, 'application/problem+json'))
    const api = createApiClient({ baseUrl: BASE, transport })
    const err = await unwrap(
      api.POST('/api/v1/orders', { body: { clientOrderId: 'web-test-1', symbol: 'AAPL', side: 'BUY', type: 'MARKET', qty: '1' } }),
    ).catch((e: unknown) => e)
    expect(isApiError(err)).toBe(true)
    const e = err as ApiError
    expect(e.status).toBe(422)
    expect(e.code).toBe('INSUFFICIENT_BUYING_POWER')
    expect(e.hint).toContain('notional')
    expect(e.fieldErrors).toEqual({ qty: 'Too many shares' })
    expect(e.problem.requestId).toBe('req_01J9Z')
    expect(e.message).toBe(problem.detail)
  })

  it('maps a non-JSON gateway error to a stable code', async () => {
    const { transport } = fakeServer(
      () => new Response('<html>Bad gateway</html>', { status: 502, headers: { 'Content-Type': 'text/html' } }),
    )
    const api = createApiClient({ baseUrl: BASE, transport })
    await expect(unwrap(api.GET('/api/v1/me'))).rejects.toMatchObject({ code: 'INTERNAL_ERROR', status: 502 })
  })

  it('maps 429 with Retry-After', async () => {
    const { transport } = fakeServer(
      () => new Response('', { status: 429, headers: { 'Retry-After': '12' } }),
    )
    const api = createApiClient({ baseUrl: BASE, transport })
    const err = (await unwrap(api.GET('/api/v1/me')).catch((e: unknown) => e)) as ApiError
    expect(err.code).toBe('RATE_LIMITED')
    expect(err.problem.retryAfterS).toBe(12)
  })

  it('maps a network failure to NETWORK_ERROR', async () => {
    const api = createApiClient({ baseUrl: BASE, transport: failingTransport })
    await expect(unwrap(api.GET('/api/v1/me'))).rejects.toMatchObject({ code: 'NETWORK_ERROR', status: 0 })
  })
})
