/**
 * RFC 9457 problem details (§6.5) → a typed `ApiError`. Clients switch on the stable `code` (§7.2).
 */
export interface FieldError {
  field: string
  code: string
  message: string
}

export interface ProblemDetail {
  type: string | null
  title: string
  status: number
  code: string
  detail: string | null
  hint: string | null
  fields: FieldError[]
  retryAfterS: number | null
  requestId: string | null
}

/** Codes synthesised by the client when the server did not send a problem document. */
export type ClientErrorCode = 'NETWORK_ERROR' | 'UNEXPECTED_RESPONSE'

export class ApiError extends Error {
  readonly problem: ProblemDetail

  constructor(problem: ProblemDetail) {
    super(problem.detail ?? problem.title)
    this.name = 'ApiError'
    this.problem = problem
  }

  get status(): number {
    return this.problem.status
  }

  get code(): string {
    return this.problem.code
  }

  get hint(): string | null {
    return this.problem.hint
  }

  /** Server field errors keyed by field name, for inline display next to ticket inputs (§22.5). */
  get fieldErrors(): Record<string, string> {
    const out: Record<string, string> = {}
    for (const f of this.problem.fields) out[f.field] ??= f.message
    return out
  }

  is(...codes: string[]): boolean {
    return codes.includes(this.problem.code)
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError
}

const asString = (v: unknown): string | null => (typeof v === 'string' && v !== '' ? v : null)
const asNumber = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null)

/**
 * Build a ProblemDetail from whatever the server returned (parsed JSON, text or nothing).
 * Non-problem bodies (a proxy's HTML 502, an empty 500) still map to a stable code.
 */
export function toProblem(body: unknown, response: Pick<Response, 'status' | 'statusText' | 'headers'>): ProblemDetail {
  const status = response.status
  const headerRetry = response.headers.get('Retry-After')
  const retryHeader = headerRetry !== null && /^\d+$/.test(headerRetry) ? Number(headerRetry) : null
  const requestIdHeader = response.headers.get('X-Request-Id')
  if (body !== null && typeof body === 'object' && !Array.isArray(body)) {
    const b = body as Record<string, unknown>
    const fields = Array.isArray(b.fields)
      ? b.fields
          .filter((f): f is Record<string, unknown> => f !== null && typeof f === 'object')
          .map((f) => ({ field: asString(f.field) ?? '', code: asString(f.code) ?? '', message: asString(f.message) ?? '' }))
      : []
    return {
      type: asString(b.type),
      title: asString(b.title) ?? (response.statusText || `HTTP ${status}`),
      status: asNumber(b.status) ?? status,
      code: asString(b.code) ?? fallbackCode(status),
      detail: asString(b.detail),
      hint: asString(b.hint),
      fields,
      retryAfterS: asNumber(b.retryAfterS) ?? retryHeader,
      requestId: asString(b.requestId) ?? requestIdHeader,
    }
  }
  return {
    type: null,
    title: response.statusText || `HTTP ${status}`,
    status,
    code: fallbackCode(status),
    detail: typeof body === 'string' && body.length > 0 && body.length < 300 && !body.trimStart().startsWith('<') ? body : null,
    hint: null,
    fields: [],
    retryAfterS: retryHeader,
    requestId: requestIdHeader,
  }
}

/** A sensible §7.2 code when the body carries none. */
function fallbackCode(status: number): string {
  switch (status) {
    case 400:
      return 'VALIDATION_FAILED'
    case 401:
      return 'UNAUTHENTICATED'
    case 403:
      return 'FORBIDDEN'
    case 404:
      return 'NOT_FOUND'
    case 429:
      return 'RATE_LIMITED'
    case 501:
      return 'NOT_IMPLEMENTED'
    case 503:
      return 'PROVIDER_UNAVAILABLE'
    default:
      return status >= 500 ? 'INTERNAL_ERROR' : 'UNEXPECTED_RESPONSE'
  }
}

export function networkError(cause: unknown): ApiError {
  const err = new ApiError({
    type: null,
    title: 'Network error',
    status: 0,
    code: 'NETWORK_ERROR' satisfies ClientErrorCode,
    detail: cause instanceof Error ? cause.message : 'The server could not be reached.',
    hint: 'Check your connection and try again.',
    fields: [],
    retryAfterS: null,
    requestId: null,
  })
  return err
}
