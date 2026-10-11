package com.stocks.oauth.web;

import com.stocks.common.domain.Channel;
import com.stocks.common.web.RequestIds;
import com.stocks.common.web.Text;
import com.stocks.contract.security.RequestMeta;
import com.stocks.oauth.OAuthException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.util.MultiValueMap;

/**
 * Helpers of the OAuth endpoints: client IP and request metadata (the {@code security} package depends on
 * {@code oauth}, not the other way round, so {@code security.ClientIp}/{@code RequestMetas} are mirrored here), the
 * single-valued form parameters (RFC 6749 §3.1: a repeated parameter is {@code invalid_request}) and the
 * {@code no-store} JSON responses.
 */
final class OAuthRequests {

  static final CacheControl NO_STORE = CacheControl.noStore();

  private OAuthRequests() {}

  /** The client IP after the trusted-proxy valve ({@code security.ClientIp}). */
  static String ip(HttpServletRequest request) {
    return Text.clip(request.getRemoteAddr(), 45);
  }

  static String header(HttpServletRequest request, String name) {
    try {
      return request.getHeader(name);
    } catch (RequestRejectedException rejected) {
      return null;
    }
  }

  /** Metadata for audit rows written by the OAuth endpoints. */
  static RequestMeta meta(HttpServletRequest request, Channel channel, String client) {
    return new RequestMeta(RequestIds.of(request), channel, ip(request), header(request, HttpHeaders.USER_AGENT),
        client, null);
  }

  /** The form as single values; a repeated parameter is {@code invalid_request}. */
  static Map<String, String> single(MultiValueMap<String, String> form) {
    Map<String, String> out = new LinkedHashMap<>();
    if (form == null) {
      return out;
    }
    for (Map.Entry<String, List<String>> e : form.entrySet()) {
      if (e.getValue().size() > 1) {
        throw new OAuthException(OAuthException.INVALID_REQUEST, e.getKey() + " is repeated.");
      }
      String v = e.getValue().isEmpty() ? null : e.getValue().getFirst();
      if (v != null && !v.isEmpty()) {
        out.put(e.getKey(), v);
      }
    }
    return out;
  }

  static <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().cacheControl(NO_STORE).header(HttpHeaders.PRAGMA, "no-cache").body(body);
  }
}
