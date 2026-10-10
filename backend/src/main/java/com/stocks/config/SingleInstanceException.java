package com.stocks.config;

import java.io.Serial;

/** Another instance of the app owns the database (§12.9); this one must not start. */
public class SingleInstanceException extends IllegalStateException {

  @Serial
  private static final long serialVersionUID = 1L;

  public SingleInstanceException(String message) {
    super(message);
  }

  public SingleInstanceException(String message, Throwable cause) {
    super(message, cause);
  }
}
