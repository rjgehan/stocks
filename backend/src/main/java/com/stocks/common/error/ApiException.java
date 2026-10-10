package com.stocks.common.error;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The one exception type that becomes a problem response with a stable {@link ErrorCode} (§6.5). Services, the
 * engine and MCP tools throw it; {@link ProblemDetailsAdvice} renders it for REST and WS5's {@code McpErrors} maps it
 * for MCP. Immutable: the {@code with*} methods return a copy.
 *
 * <pre>{@code
 * throw ApiException.of(ErrorCode.INSUFFICIENT_BUYING_POWER, "Order needs $231.96; buying power is $198.10.")
 *     .withHint("Try a notional (dollar) market order of \"198.10\" during regular hours.")
 *     .withField("qty", ErrorCode.INSUFFICIENT_BUYING_POWER, "Exceeds buying power");
 * }</pre>
 */
public final class ApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ErrorCode code;
  private final String hint;
  private final List<ProblemField> fields;
  private final Long retryAfterS;

  public ApiException(ErrorCode code, String detail) {
    this(code, detail, null, List.of(), null, null);
  }

  public ApiException(ErrorCode code, String detail, Throwable cause) {
    this(code, detail, null, List.of(), null, cause);
  }

  private ApiException(ErrorCode code, String detail, String hint, List<ProblemField> fields, Long retryAfterS,
      Throwable cause) {
    super(detail, cause);
    this.code = Objects.requireNonNull(code, "code");
    this.hint = hint;
    this.fields = List.copyOf(fields);
    this.retryAfterS = retryAfterS;
  }

  public static ApiException of(ErrorCode code, String detail) {
    return new ApiException(code, detail);
  }

  /** For Day-0 stub beans (§27.1): 501 {@code NOT_IMPLEMENTED}. */
  public static ApiException notImplemented(String what) {
    return new ApiException(ErrorCode.NOT_IMPLEMENTED, what + " is not implemented yet.");
  }

  /** 400 {@code VALIDATION_FAILED} for one field. */
  public static ApiException validation(String field, String message) {
    return new ApiException(ErrorCode.VALIDATION_FAILED, message)
        .withField(field, ErrorCode.VALIDATION_FAILED, message);
  }

  public ApiException withHint(String newHint) {
    return new ApiException(code, getMessage(), newHint, fields, retryAfterS, getCause());
  }

  public ApiException withField(String field, ErrorCode fieldCode, String message) {
    List<ProblemField> copy = new ArrayList<>(fields);
    copy.add(new ProblemField(field, fieldCode.name(), message));
    return new ApiException(code, getMessage(), hint, copy, retryAfterS, getCause());
  }

  public ApiException withFields(List<ProblemField> moreFields) {
    List<ProblemField> copy = new ArrayList<>(fields);
    copy.addAll(moreFields);
    return new ApiException(code, getMessage(), hint, copy, retryAfterS, getCause());
  }

  /** Seconds until a retry may succeed; also sent as the {@code Retry-After} header on 429/503. */
  public ApiException withRetryAfter(long seconds) {
    return new ApiException(code, getMessage(), hint, fields, seconds, getCause());
  }

  public ErrorCode code() {
    return code;
  }

  public int status() {
    return code.status();
  }

  /** The problem {@code detail}. */
  public String detail() {
    return getMessage();
  }

  public String hint() {
    return hint;
  }

  public List<ProblemField> fields() {
    return fields;
  }

  public Long retryAfterS() {
    return retryAfterS;
  }

  @Override
  public String toString() {
    return "ApiException[" + code + "]: " + getMessage();
  }
}
