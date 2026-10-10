package com.stocks.marketdata.spi;

import java.time.Instant;

/**
 * A provider call failed (network, HTTP status, parse). {@code status} is the HTTP status (0 for I/O errors and
 * local refusals); {@code retryAt}, when known, is when the provider will accept calls again (HTTP 429).
 */
public class ProviderException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String provider;
  private final int status;
  private final transient Instant retryAt;

  public ProviderException(String provider, int status, String message, Instant retryAt, Throwable cause) {
    super(provider + ": " + message, cause);
    this.provider = provider;
    this.status = status;
    this.retryAt = retryAt;
  }

  public ProviderException(String provider, String message, Throwable cause) {
    this(provider, 0, message, null, cause);
  }

  public String provider() {
    return provider;
  }

  public int status() {
    return status;
  }

  public Instant retryAt() {
    return retryAt;
  }

  /** The local rate limiter refused the call (budget exhausted for its priority). */
  public boolean throttled() {
    return status == 429;
  }
}
