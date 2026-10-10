package com.stocks.realtime;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * STOMP CSRF (§17.1 chain 3, §19). The SPA sends the <b>raw</b> value of the {@code XSRF-TOKEN} cookie as
 * {@code X-XSRF-TOKEN} in the {@code CONNECT} frame; the server compares it, unmasked and in constant time, with the
 * token of the same {@link CsrfTokenRepository} the HTTP chains use ({@code CookieCsrfTokenRepository}), loaded at the
 * handshake. Spring Security's own {@code XorCsrfChannelInterceptor} expects a masked token and would reject the raw
 * cookie value; this pair replaces it ({@link #channelInterceptor} is published as the bean
 * {@code csrfChannelInterceptor}, the name Spring Security's WebSocket support looks up).
 *
 * <ul>
 *   <li>{@link Handshake}: copies the handshake request's token (the cookie value, or null) into the WebSocket session
 *       attributes under {@link #SESSION_ATTRIBUTE}. It never creates a token: a client without the cookie must call
 *       {@code GET /api/v1/auth/csrf} first (the SPA's {@code beforeConnect} does).</li>
 *   <li>{@link ChannelCheck}: on {@code CONNECT}, a missing or different header is refused with an ERROR frame
 *       {@code CSRF_INVALID: …} ({@link StompErrors}; the socket closes); every other frame passes.</li>
 * </ul>
 */
public final class StompCsrf {

  /** WebSocket session attribute holding the expected raw token ({@code String}, absent without a cookie). */
  public static final String SESSION_ATTRIBUTE = StompCsrf.class.getName() + ".token";
  /** The CONNECT header (§19), the same name as the HTTP header. */
  public static final String HEADER = "X-XSRF-TOKEN";

  private StompCsrf() {}

  public static HandshakeInterceptor handshake(CsrfTokenRepository repository) {
    return new Handshake(repository);
  }

  public static ChannelInterceptor channelInterceptor(StompErrors errors) {
    return new ChannelCheck(errors);
  }

  /** Stores the handshake request's CSRF token in the session attributes. */
  static final class Handshake implements HandshakeInterceptor {

    private final CsrfTokenRepository repository;

    Handshake(CsrfTokenRepository repository) {
      this.repository = repository;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
      if (request instanceof ServletServerHttpRequest servlet) {
        HttpServletRequest http = servlet.getServletRequest();
        CsrfToken token = repository.loadToken(http);
        if (token != null && token.getToken() != null && !token.getToken().isEmpty()) {
          attributes.put(SESSION_ATTRIBUTE, token.getToken());
        }
      }
      return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                               Exception exception) {
      // nothing to do
    }
  }

  /** Checks {@code X-XSRF-TOKEN} on CONNECT against the handshake token, unmasked, in constant time. */
  static final class ChannelCheck implements ChannelInterceptor {

    private final StompErrors errors;

    ChannelCheck(StompErrors errors) {
      this.errors = errors;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
      if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.CONNECT) {
        return message;
      }
      Map<String, Object> attributes = SimpMessageHeaderAccessor.getSessionAttributes(message.getHeaders());
      Object expected = attributes == null ? null : attributes.get(SESSION_ATTRIBUTE);
      SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);
      String actual = firstNativeHeader(accessor, HEADER);
      if (!(expected instanceof String token) || actual == null || !constantTimeEquals(token, actual)) {
        return errors.reject(message, "CSRF_INVALID: send the raw XSRF-TOKEN cookie value as " + HEADER
            + " in CONNECT (GET /api/v1/auth/csrf sets the cookie)");
      }
      return message;
    }

    private static String firstNativeHeader(SimpMessageHeaderAccessor accessor, String name) {
      Object nativeHeaders = accessor.getHeader(SimpMessageHeaderAccessor.NATIVE_HEADERS);
      if (nativeHeaders instanceof Map<?, ?> map) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
          if (e.getKey() instanceof String key && key.equalsIgnoreCase(name)
              && e.getValue() instanceof java.util.List<?> values && !values.isEmpty()
              && values.get(0) instanceof String value) {
            return value;
          }
        }
      }
      return null;
    }

    static boolean constantTimeEquals(String a, String b) {
      return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
  }
}
