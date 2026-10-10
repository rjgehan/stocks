package com.stocks.common.error;

/**
 * Stable error codes (§7.2, CONTRACT). Clients and bots switch on {@link #name()}; the HTTP status and the problem
 * {@code title} come from here. Adding a code is a contract change (§27.3 rule 6).
 */
public enum ErrorCode {
  // 400
  VALIDATION_FAILED(400, "Validation failed"),

  // 401
  UNAUTHENTICATED(401, "Authentication required"),
  INVALID_API_KEY(401, "Invalid API key"),
  API_KEY_REVOKED(401, "API key revoked"),
  API_KEY_EXPIRED(401, "API key expired"),
  BAD_CREDENTIALS(401, "Bad credentials"),

  // 403
  FORBIDDEN(403, "Forbidden"),
  FORBIDDEN_SCOPE(403, "Missing API scope"),
  ADMIN_REQUIRES_SESSION(403, "Admin requires a session"),
  RECENT_AUTH_REQUIRED(403, "Recent authentication required"),
  CSRF_INVALID(403, "Invalid CSRF token"),
  PLAYER_DISABLED(403, "Player disabled"),
  PLAYER_FROZEN(403, "Player frozen"),

  // 404
  NOT_FOUND(404, "Not found"),
  SYMBOL_NOT_FOUND(404, "Symbol not found"),
  ORDER_NOT_FOUND(404, "Order not found"),
  PLAYER_NOT_FOUND(404, "Player not found"),
  ACCOUNT_NOT_FOUND(404, "Account not found"),

  // 409
  CLIENT_ORDER_ID_CONFLICT(409, "Client order ID conflict"),
  IDEMPOTENCY_KEY_CONFLICT(409, "Idempotency key conflict"),
  ORDER_NOT_CANCELLABLE(409, "Order not cancellable"),
  ORDER_NOT_REPLACEABLE(409, "Order not replaceable"),
  HANDLE_TAKEN(409, "Handle taken"),

  // 422 (orders)
  CLIENT_ORDER_ID_INVALID(422, "Invalid client order ID"),
  RATIONALE_REQUIRED(422, "Rationale required"),
  SYMBOL_NOT_TRADABLE(422, "Symbol not tradable"),
  INVALID_QTY(422, "Invalid quantity"),
  QTY_OR_NOTIONAL_REQUIRED(422, "Quantity or notional required"),
  NOT_FRACTIONABLE(422, "Symbol not fractionable"),
  FRACTIONAL_MARKET_ONLY(422, "Fractional orders must be market orders"),
  FRACTIONAL_DAY_ONLY(422, "Fractional orders must be DAY orders"),
  FRACTIONAL_NOT_REPLACEABLE(422, "Fractional orders cannot be replaced"),
  NOTIONAL_TOO_SMALL(422, "Notional too small"),
  INVALID_TICK(422, "Price not on tick"),
  LIMIT_PRICE_REQUIRED(422, "Limit price required"),
  STOP_PRICE_REQUIRED(422, "Stop price required"),
  TRAIL_REQUIRED(422, "Trail amount or percent required"),
  PRICE_NOT_ALLOWED(422, "Price not allowed"),
  EXT_HOURS_LIMIT_ONLY(422, "Extended hours requires a limit order"),
  TIF_NOT_ALLOWED(422, "Time in force not allowed"),
  SESSION_NOT_ALLOWED(422, "Order not allowed in this session"),
  STOP_PRICE_WRONG_SIDE(422, "Stop price on the wrong side"),
  PRICE_TOO_FAR(422, "Price too far from the market"),
  SHORT_SELLING_NOT_ALLOWED(422, "Short selling not allowed"),
  INSUFFICIENT_POSITION(422, "Insufficient position"),
  INSUFFICIENT_BUYING_POWER(422, "Insufficient buying power"),
  ACCOUNT_RESTRICTED(422, "Account restricted"),
  TOO_MANY_OPEN_ORDERS(422, "Too many open orders"),
  GFV_ACK_REQUIRED(422, "Good-faith violation acknowledgement required"),
  MARKET_DATA_UNAVAILABLE(422, "Market data unavailable"),

  // 422 (other)
  INSUFFICIENT_WITHDRAWABLE(422, "Insufficient withdrawable cash"),
  MARKS_DEGRADED(422, "Marks degraded"),
  SELF_RESET_DISABLED(422, "Self reset disabled"),
  INVITE_INVALID(422, "Invalid invite"),
  WEAK_PASSWORD(422, "Weak password"),
  SETTING_INVALID(422, "Invalid setting"),
  CORPORATE_ACTION_NOT_RESOLVABLE(422, "Corporate action not resolvable"),

  // 429
  RATE_LIMITED(429, "Rate limited"),
  LOGIN_LOCKED(429, "Login locked"),

  // 500: any unmapped exception; the body carries only the requestId, never a stack trace
  INTERNAL_ERROR(500, "Internal error"),

  // 501: Day-0 stub beans (§27.1); none may remain at M4
  NOT_IMPLEMENTED(501, "Not implemented"),

  // 503
  ENGINE_BUSY(503, "Engine busy"),
  ENGINE_TIMEOUT(503, "Engine timeout"),
  ENGINE_HALTED(503, "Engine halted"),
  PROVIDER_UNAVAILABLE(503, "Market data provider unavailable");

  private final int status;
  private final String title;

  ErrorCode(int status, String title) {
    this.status = status;
    this.title = title;
  }

  /** HTTP status of the problem response. */
  public int status() {
    return status;
  }

  /** Human-readable problem {@code title}. */
  public String title() {
    return title;
  }

  /** Problem {@code type}: {@code /errors/<CODE>} (§6.5). */
  public String type() {
    return "/errors/" + name();
  }
}
