package com.stocks.common.web;

import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code X-Request-Id} handling (§6.6). A client value is accepted only if it matches
 * {@code ^[A-Za-z0-9._:-]{1,64}$}; otherwise (missing, too long, bad characters) the server generates
 * {@code req_<ULID>}. Never rejected. The id is echoed on every response, stored as a request attribute, put in the
 * logging MDC ({@code requestId}) and ends up in problem bodies and {@code audit_log.request_id}.
 *
 * <p>Runs before Spring Security's filter chain, so 401/403 responses carry the header too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIds extends OncePerRequestFilter {

  public static final String HEADER = "X-Request-Id";
  public static final String ATTRIBUTE = RequestIds.class.getName() + ".requestId";
  public static final String MDC_KEY = "requestId";
  private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

  private final PublicId publicId;

  public RequestIds(PublicId publicId) {
    this.publicId = publicId;
  }

  /** True if a client-supplied id may be used as is. */
  public static boolean isAcceptable(String clientValue) {
    return clientValue != null && VALID.matcher(clientValue).matches();
  }

  /** The request id of {@code request}, or {@code null} if this filter has not run for it. */
  public static String of(HttpServletRequest request) {
    Object v = request.getAttribute(ATTRIBUTE);
    return v instanceof String s ? s : null;
  }

  /** The request id of the current request thread, or {@code null} outside a request. */
  public static String current() {
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    if (attrs != null) {
      Object v = attrs.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
      if (v instanceof String s) {
        return s;
      }
    }
    return MDC.get(MDC_KEY);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = of(request); // async and error dispatches keep the id of the original request
    if (id == null) {
      String supplied = request.getHeader(HEADER);
      id = isAcceptable(supplied) ? supplied : publicId.next(Prefix.REQUEST);
      request.setAttribute(ATTRIBUTE, id);
    }
    if (!response.isCommitted()) {
      response.setHeader(HEADER, id);
    }
    String previous = MDC.get(MDC_KEY);
    MDC.put(MDC_KEY, id);
    try {
      chain.doFilter(request, response);
    } finally {
      if (previous == null) {
        MDC.remove(MDC_KEY);
      } else {
        MDC.put(MDC_KEY, previous);
      }
    }
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }
}
