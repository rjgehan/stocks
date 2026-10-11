package com.stocks.oauth.web;

import com.stocks.oauth.OAuthException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 6749 §5.2 JSON errors ({@code {error, error_description}}, {@code no-store}) for the {@code /oauth2/**} and
 * {@code /.well-known/**} controllers (§17.9.1), which never answer §6.5 problems. {@code invalid_client} is 401 with
 * {@code WWW-Authenticate: Basic} (RFC 6749 §5.2); {@code slow_down} 429 with {@code Retry-After}.
 */
@RestControllerAdvice(assignableTypes = {OAuthServerController.class, OAuthMetadataController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OAuthErrors {

  private static final Logger log = LoggerFactory.getLogger(OAuthErrors.class);

  @ExceptionHandler(OAuthException.class)
  public ResponseEntity<Map<String, Object>> oauth(OAuthException e) {
    ResponseEntity.BodyBuilder b = ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore());
    if (e.status() == 401) {
      b.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"stocks-oauth\"");
    }
    if (e.status() == 429) {
      b.header(HttpHeaders.RETRY_AFTER, "60");
    }
    return b.body(body(e.error(), e.description()));
  }

  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<Map<String, Object>> unexpected(RuntimeException e) {
    log.error("OAuth endpoint failed", e);
    return ResponseEntity.status(500).cacheControl(CacheControl.noStore())
        .body(body(OAuthException.SERVER_ERROR, "Internal error."));
  }

  static Map<String, Object> body(String error, String description) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("error", error);
    out.put("error_description", description);
    return out;
  }
}
