package com.stocks.common.error;

import java.util.List;

/**
 * The RFC 9457 {@code application/problem+json} body (§6.5). Every member is always present (nulls included), so
 * clients can rely on the shape.
 *
 * @param type        {@code /errors/<CODE>}
 * @param title       {@link ErrorCode#title()}
 * @param status      HTTP status
 * @param code        {@link ErrorCode#name()}, stable
 * @param detail      human-readable detail; {@code null} for {@code INTERNAL_ERROR}
 * @param hint        what to do next (LLM-friendly), or {@code null}
 * @param fields      field-level problems, never {@code null}
 * @param retryAfterS seconds to wait before retrying, or {@code null}
 * @param requestId   the request's {@code X-Request-Id} (§6.6)
 */
public record Problem(String type, String title, int status, String code, String detail, String hint,
                      List<ProblemField> fields, Long retryAfterS, String requestId) {

  public Problem {
    fields = fields == null ? List.of() : List.copyOf(fields);
  }

  public static Problem of(ErrorCode code, int status, String detail, String hint, List<ProblemField> fields,
                           Long retryAfterS, String requestId) {
    return new Problem(code.type(), code.title(), status, code.name(), detail, hint, fields, retryAfterS, requestId);
  }

  public static Problem of(ApiException e, String requestId) {
    return of(e.code(), e.status(), e.detail(), e.hint(), e.fields(), e.retryAfterS(), requestId);
  }
}
