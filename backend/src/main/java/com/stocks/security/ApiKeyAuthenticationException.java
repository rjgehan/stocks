package com.stocks.security;

import com.stocks.common.error.ErrorCode;
import java.io.Serial;
import org.springframework.security.core.AuthenticationException;

/**
 * A rejected API key, with the §7.2 code to answer with: 401 {@code INVALID_API_KEY}, {@code API_KEY_REVOKED},
 * {@code API_KEY_EXPIRED}, or 403 {@code PLAYER_FROZEN}, {@code PLAYER_DISABLED} (in that outcome order).
 */
public final class ApiKeyAuthenticationException extends AuthenticationException {

  @Serial
  private static final long serialVersionUID = 1L;

  private final ErrorCode code;

  public ApiKeyAuthenticationException(ErrorCode code, String detail) {
    super(detail);
    this.code = code;
  }

  public ErrorCode code() {
    return code;
  }
}
