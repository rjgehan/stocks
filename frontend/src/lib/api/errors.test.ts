import { describe, expect, it } from 'vitest'
import { ERROR_COPY, describeError, errorBody, errorField } from './errors'
import { ApiError, type ProblemDetail } from './problem'

const problem = (over: Partial<ProblemDetail>): ApiError =>
  new ApiError({ type: null, title: 'Unprocessable', status: 422, code: 'INTERNAL_ERROR', detail: null, hint: null, fields: [], retryAfterS: null, requestId: null, ...over })

/** Every §7.2 code (ARCHITECTURE.md), so a renamed or missing code fails here. */
const SPEC_CODES = `VALIDATION_FAILED UNAUTHENTICATED INVALID_API_KEY API_KEY_REVOKED API_KEY_EXPIRED BAD_CREDENTIALS FORBIDDEN FORBIDDEN_SCOPE
ADMIN_REQUIRES_SESSION RECENT_AUTH_REQUIRED CSRF_INVALID PLAYER_DISABLED PLAYER_FROZEN NOT_FOUND SYMBOL_NOT_FOUND ORDER_NOT_FOUND PLAYER_NOT_FOUND
ACCOUNT_NOT_FOUND CLIENT_ORDER_ID_CONFLICT IDEMPOTENCY_KEY_CONFLICT ORDER_NOT_CANCELLABLE ORDER_NOT_REPLACEABLE HANDLE_TAKEN CLIENT_ORDER_ID_INVALID
RATIONALE_REQUIRED SYMBOL_NOT_TRADABLE INVALID_QTY QTY_OR_NOTIONAL_REQUIRED NOT_FRACTIONABLE FRACTIONAL_MARKET_ONLY FRACTIONAL_DAY_ONLY
FRACTIONAL_NOT_REPLACEABLE NOTIONAL_TOO_SMALL INVALID_TICK LIMIT_PRICE_REQUIRED STOP_PRICE_REQUIRED TRAIL_REQUIRED PRICE_NOT_ALLOWED
EXT_HOURS_LIMIT_ONLY TIF_NOT_ALLOWED SESSION_NOT_ALLOWED STOP_PRICE_WRONG_SIDE PRICE_TOO_FAR SHORT_SELLING_NOT_ALLOWED INSUFFICIENT_POSITION
INSUFFICIENT_BUYING_POWER ACCOUNT_RESTRICTED TOO_MANY_OPEN_ORDERS GFV_ACK_REQUIRED MARKET_DATA_UNAVAILABLE INSUFFICIENT_WITHDRAWABLE MARKS_DEGRADED
SELF_RESET_DISABLED INVITE_INVALID WEAK_PASSWORD SETTING_INVALID CORPORATE_ACTION_NOT_RESOLVABLE RATE_LIMITED LOGIN_LOCKED INTERNAL_ERROR
NOT_IMPLEMENTED ENGINE_BUSY ENGINE_TIMEOUT ENGINE_HALTED PROVIDER_UNAVAILABLE`.split(/\s+/)

describe('error copy (§7.2 → UI)', () => {
  it('has human copy for every §7.2 code', () => {
    expect(SPEC_CODES.filter((c) => !ERROR_COPY[c])).toEqual([])
  })

  it('uses the copy as the title and keeps a precise server detail', () => {
    const d = describeError(problem({ code: 'INSUFFICIENT_BUYING_POWER', detail: 'Order needs $251.02; buying power is $200.00' }))
    expect(d).toEqual({ code: 'INSUFFICIENT_BUYING_POWER', title: 'Not enough buying power', detail: 'Order needs $251.02; buying power is $200.00', hint: null })
    expect(errorBody(d)).toBe('Order needs $251.02; buying power is $200.00')
  })

  it('falls back to the problem title for unknown codes, and drops a detail that repeats the title', () => {
    expect(describeError(problem({ code: 'SOMETHING_NEW', title: 'Something new' })).title).toBe('Something new')
    expect(describeError(problem({ code: 'ENGINE_HALTED', detail: 'Trading is paused by the admin' })).detail).toBeNull()
  })

  it('turns Retry-After into a hint for rate limits', () => {
    expect(describeError(problem({ status: 429, code: 'RATE_LIMITED', retryAfterS: 7 })).hint).toBe('Try again in 7 s.')
  })

  it('maps order rejections to the ticket input they belong to', () => {
    expect(errorField('LIMIT_PRICE_REQUIRED')).toBe('limitPrice')
    expect(errorField('NOTIONAL_TOO_SMALL')).toBe('notional')
    expect(errorField('INSUFFICIENT_BUYING_POWER')).toBe('qty')
    expect(errorField('ACCOUNT_RESTRICTED')).toBeUndefined()
    expect(errorField(null)).toBeUndefined()
  })

  it('describes non-API errors', () => {
    expect(describeError(new Error('boom'))).toMatchObject({ code: 'UNEXPECTED', detail: 'boom' })
  })
})
