package com.stocks.oauth.cimd;

import java.net.URI;

/**
 * Fetches a Client ID Metadata Document (§17.9.2). The production implementation is {@link SafeHttpsFetcher}; tests
 * replace the bean to serve documents without the network.
 */
public interface CimdFetcher {

  /** The raw body and the {@code Cache-Control} header (may be null) of a 200 response. */
  record Response(byte[] body, String cacheControl) {}

  /** Thrown for every refusal or failure; the message is safe to show to the user. */
  final class FetchException extends Exception {
    private static final long serialVersionUID = 1L;

    public FetchException(String message) {
      super(message);
    }

    public FetchException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  Response fetch(URI clientIdUrl) throws FetchException;
}
