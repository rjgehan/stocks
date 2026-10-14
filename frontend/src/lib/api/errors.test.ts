import { describe, expect, it } from 'vitest'
import { ERROR_COPY, describeError, errorBody, errorField, humanizeMessage, orderErrorText, warningText } from './errors'
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
NOT_IMPLEMENTED ENGINE_BUSY ENGINE_TIMEOUT ENGINE_HALTED PROVIDER_UNAVAILABLE RUN_REQUEST_CONFLICT`.split(/\s+/)

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

// Review regression: the ticket never shows Java field names or 4-decimal internal prices
describe('order error text for the ticket', () => {
  it('turns stop and trail rejections into friendly copy with the market price', () => {
    expect(orderErrorText({ code: 'STOP_PRICE_WRONG_SIDE', message: 'A buy stop must be above the market: stopPrice 100.0000, reference 256.3300.' })).toBe('Buy stops must be above the market (256.33).')
    expect(orderErrorText({ code: 'STOP_PRICE_WRONG_SIDE', message: 'Below 41.2000' })).toBe('Sell stops must be below the market (41.20).')
    expect(orderErrorText({ code: 'PRICE_TOO_FAR', message: 'stopPrice 900.0000 is 251.1% from the reference 256.3200.' })).toBe('Too far from the market price (256.32).')
    expect(orderErrorText({ code: 'INVALID_TICK', message: 'trailAmount 0.4256 is not on the 0.01 tick' })).toBe('Use steps of $0.01.')
  })

  it('humanizes any other server message', () => {
    expect(humanizeMessage('limitPrice 227.4000 must be > 0')).toBe('limit price 227.40 must be > 0')
    expect(orderErrorText({ code: 'VALIDATION_FAILED', message: 'qty must be positive' })).toBe('quantity must be positive')
    expect(orderErrorText({ code: 'INSUFFICIENT_BUYING_POWER', message: '' })).toBe('Not enough buying power')
  })

  it('words FAT_FINGER warnings without Java field names (review finding)', () => {
    expect(warningText({ code: 'FAT_FINGER', message: 'Take profit: limitPrice is 5.0% from the reference price 11.97.' })).toBe(
      'Take profit limit price is 5.0% from the market (11.97).',
    )
    expect(warningText({ code: 'FAT_FINGER', message: 'stopPrice is 7.3% from the reference price 256.3300.' })).toBe('Stop price is 7.3% from the market (256.33).')
    expect(warningText({ code: 'FAT_FINGER', message: "The trailing stop's starting stop is 6.0% from the reference price 41.2." })).toBe(
      "The trailing stop's starting stop is 6.0% from the market (41.20).",
    )
    expect(warningText({ code: 'GFV_RISK', message: 'Selling now may cause a good-faith violation.' })).toBe('Selling now may cause a good-faith violation.')
    expect(warningText({ code: 'OTHER', message: 'limitPrice 12.5000 is far' })).toBe('limit price 12.50 is far')
    expect(warningText({ code: 'FAT_FINGER', message: null })).toBe('')
  })
})
