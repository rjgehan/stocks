package com.stocks.security;

import com.stocks.common.error.ErrorCode;
import com.stocks.common.error.Problem;
import com.stocks.common.error.ProblemDetailsAdvice;
import com.stocks.common.web.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an RFC 9457 problem body (§6.5) from a servlet filter, where {@code ProblemDetailsAdvice} cannot run: the
 * bearer filter, {@code AdminSessionOnlyFilter}, and the security entry point and access-denied handler. Same
 * {@link Problem} record and the same Boot {@link JsonMapper} as the advice, so every 401/403 has the §6.5 shape.
 */
public final class ProblemWriter {

  /** The {@code WWW-Authenticate} value on chain-2 401s (§17.1; Phase 2 adds {@code resource_metadata}). */
  public static final String BEARER_CHALLENGE = ProblemDetailsAdvice.BEARER_CHALLENGE;

  private final JsonMapper jsonMapper;

  public ProblemWriter(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String detail)
      throws IOException {
    write(request, response, code, detail, null);
  }

  /** Writes the problem unless the response is already committed; {@code wwwAuthenticate} may be null. */
  public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String detail,
                    String wwwAuthenticate) throws IOException {
    write(request, response, code, detail, wwwAuthenticate, null);
  }

  /**
   * Same, with {@code retryAfterS} in the body and the {@code Retry-After} header (429, §6.5) when not null.
   */
  public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String detail,
                    String wwwAuthenticate, Long retryAfterS) throws IOException {
    if (response.isCommitted()) {
      return;
    }
    String requestId = RequestIds.of(request);
    Problem problem = Problem.of(code, code.status(), detail, null, List.of(), retryAfterS, requestId);
    response.resetBuffer();
    response.setStatus(code.status());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    if (requestId != null) {
      response.setHeader(RequestIds.HEADER, requestId);
    }
    if (wwwAuthenticate != null) {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate);
    }
    if (retryAfterS != null) {
      response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterS));
    }
    response.getOutputStream().write(jsonMapper.writeValueAsBytes(problem));
    response.flushBuffer();
  }
}
