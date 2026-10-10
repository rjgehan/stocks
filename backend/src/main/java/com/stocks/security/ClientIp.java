package com.stocks.security;

import com.stocks.common.web.Text;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The client's IP address (§24.3). In production Tomcat's {@code RemoteIpValve} has already replaced the peer address
 * with {@code CF-Connecting-IP}, but only when the peer is a trusted proxy ({@code TRUSTED_PROXIES}), so this is
 * simply {@code request.getRemoteAddr()}. Feeds rate limiting, audit and {@code api_key.last_used_ip}. Bounded to
 * 45 characters, the width of every IP column.
 */
public final class ClientIp {

  public static final int MAX_LENGTH = 45;

  private ClientIp() {}

  public static String resolve(HttpServletRequest request) {
    return request == null ? null : Text.clip(request.getRemoteAddr(), MAX_LENGTH);
  }
}
