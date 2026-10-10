package com.stocks.oauth;

import java.io.Serial;

/**
 * An RFC 6749 error ({@code {error, error_description}}) raised by the OAuth endpoints (§17.9.1); they answer it as
 * JSON (token, register, revoke) or as a redirect (authorize), never as a §6.5 problem.
 */
public class OAuthException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  public static final String INVALID_REQUEST = "invalid_request";
  public static final String INVALID_CLIENT = "invalid_client";
  public static final String INVALID_GRANT = "invalid_grant";
  public static final String UNAUTHORIZED_CLIENT = "unauthorized_client";
  public static final String UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type";
  public static final String UNSUPPORTED_RESPONSE_TYPE = "unsupported_response_type";
  public static final String INVALID_SCOPE = "invalid_scope";
  public static final String INVALID_TARGET = "invalid_target";
  public static final String ACCESS_DENIED = "access_denied";
  public static final String SERVER_ERROR = "server_error";
  public static final String TEMPORARILY_UNAVAILABLE = "temporarily_unavailable";
  public static final String INVALID_REDIRECT_URI = "invalid_redirect_uri";
  public static final String INVALID_CLIENT_METADATA = "invalid_client_metadata";
  /** Not an RFC 6749 code: the caller exceeded an OAuth endpoint's per-IP limit (answered 429). */
  public static final String SLOW_DOWN = "slow_down";

  private final String error;
  private final int status;

  public OAuthException(String error, String description) {
    this(error, description, defaultStatus(error));
  }

  public OAuthException(String error, String description, int status) {
    super(description);
    this.error = error;
    this.status = status;
  }

  public String error() {
    return error;
  }

  public String description() {
    return getMessage();
  }

  public int status() {
    return status;
  }

  static int defaultStatus(String error) {
    return switch (error) {
      case INVALID_CLIENT -> 401;
      case SERVER_ERROR -> 500;
      case TEMPORARILY_UNAVAILABLE -> 503;
      case SLOW_DOWN -> 429;
      default -> 400;
    };
  }
}
