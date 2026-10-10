package com.stocks.security;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Bounds password guessing from inside an authenticated session (§17.3 re-authentication and password change),
 * which the anonymous login lockout deliberately does not cover: after {@value #MAX_FAILURES} wrong passwords in one
 * session the session ends (401 {@code UNAUTHENTICATED}) and its sockets close. A correct password clears the count.
 */
@Component
public class SessionPasswordGuard {

  public static final int MAX_FAILURES = 5;
  static final String ATTRIBUTE = "stocks.sessionPasswordFailures";

  private final SessionLogin sessionLogin;

  public SessionPasswordGuard(SessionLogin sessionLogin) {
    this.sessionLogin = sessionLogin;
  }

  /** Runs a password check of the session's own player, counting {@code BAD_CREDENTIALS} against the session. */
  public <T> T check(HttpServletRequest request, HttpServletResponse response, Supplier<T> passwordCheck) {
    T result;
    try {
      result = passwordCheck.get();
    } catch (ApiException e) {
      if (e.code() == ErrorCode.BAD_CREDENTIALS) {
        HttpSession session = request.getSession(false);
        if (session != null) {
          int failures = (session.getAttribute(ATTRIBUTE) instanceof Integer n ? n : 0) + 1;
          if (failures >= MAX_FAILURES) {
            sessionLogin.end(request, response);
            throw ApiException.of(ErrorCode.UNAUTHENTICATED, "Too many wrong passwords; this session has ended.")
                .withHint("Sign in again.");
          }
          session.setAttribute(ATTRIBUTE, failures);
        }
      }
      throw e;
    }
    HttpSession session = request.getSession(false);
    if (session != null && session.getAttribute(ATTRIBUTE) != null) {
      session.removeAttribute(ATTRIBUTE);
    }
    return result;
  }
}
