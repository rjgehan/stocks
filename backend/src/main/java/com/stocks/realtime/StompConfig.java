package com.stocks.realtime;

import com.stocks.config.StocksProperties;
import com.stocks.realtime.stream.StreamTopicTap;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * STOMP over native WebSocket at {@code /ws} (§19, §17.1 chain 3). Same origin, no SockJS.
 * <ul>
 *   <li><b>Handshake</b>: chain 3 ({@code SecurityConfig.wsChain}) admits only an authenticated session; the
 *       {@code Origin} must be {@code stocks.public-url} or one of {@code stocks.security.extra-ws-origins} (dev adds
 *       {@code http://localhost:5173}); a request without {@code Origin} (not a browser) is allowed. The CSRF token of
 *       the handshake is remembered for the CONNECT check ({@link StompCsrf}), and so is the HTTP session id, so
 *       {@link LiveSockets} can close the socket when that session ends (password change, logout, freeze,
 *       disable, role change, expiry).</li>
 *   <li><b>Broker</b>: Spring's simple in-memory broker, prefixes {@code /topic} and {@code /queue}, application
 *       prefix {@code /app}, user prefix {@code /user}; heartbeats 10 s / 10 s (under Cloudflare's ~100 s idle
 *       limit), on Spring's {@code messageBrokerTaskScheduler}.</li>
 *   <li><b>Inbound channel</b>: {@code csrfChannelInterceptor} (raw-token CSRF on CONNECT), then
 *       {@link StompAuthInterceptor} (destinations, caps, leases). Frames of one session keep their order
 *       ({@code preserveReceiveOrder}); so do messages to one session ({@code preservePublishOrder}).</li>
 *   <li><b>Payloads</b> are written by Boot's {@code JsonMapper} (Boot's WebSocket auto-configuration registers it as
 *       the message converter), so STOMP follows §6.3 like REST and MCP.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class StompConfig implements WebSocketMessageBrokerConfigurer {

  public static final String ENDPOINT = "/ws";
  /** §19: 10 s / 10 s. */
  public static final long HEARTBEAT_MS = 10_000;
  /** Largest inbound frame: clients only send CONNECT, SUBSCRIBE and UNSUBSCRIBE. */
  static final int MAX_INBOUND_FRAME_BYTES = 16 * 1024;

  private final StocksProperties properties;
  private final CsrfTokenRepository csrfTokenRepository;
  private final StompAuthInterceptor authInterceptor;
  private final ChannelInterceptor csrfChannelInterceptor;
  private final TaskScheduler brokerScheduler;
  private final SubscriptionTracker tracker;
  private final LiveSockets liveSockets;
  private final StreamTopicTap streamTap;

  public StompConfig(StocksProperties properties, CsrfTokenRepository csrfTokenRepository,
                     StompAuthInterceptor authInterceptor,
                     @Qualifier("csrfChannelInterceptor") ChannelInterceptor csrfChannelInterceptor,
                     @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler brokerScheduler,
                     SubscriptionTracker tracker, LiveSockets liveSockets, StreamTopicTap streamTap) {
    this.properties = properties;
    this.csrfTokenRepository = csrfTokenRepository;
    this.authInterceptor = authInterceptor;
    this.csrfChannelInterceptor = csrfChannelInterceptor;
    this.brokerScheduler = brokerScheduler;
    this.tracker = tracker;
    this.liveSockets = liveSockets;
    this.streamTap = streamTap;
  }

  /**
   * The STOMP CSRF check (§17.1 chain 3): compares the raw {@code XSRF-TOKEN} cookie value, under the bean name
   * Spring Security's WebSocket support uses for its (masked-token) default.
   */
  @Bean(name = "csrfChannelInterceptor")
  static ChannelInterceptor csrfChannelInterceptor(StompErrors errors) {
    return StompCsrf.channelInterceptor(errors);
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint(ENDPOINT)
        .setAllowedOrigins(allowedOrigins(properties).toArray(String[]::new))
        .addInterceptors(StompCsrf.handshake(csrfTokenRepository), LiveSockets.handshakeInterceptor());
    registry.setPreserveReceiveOrder(true);
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/topic", "/queue")
        .setHeartbeatValue(new long[] {HEARTBEAT_MS, HEARTBEAT_MS})
        .setTaskScheduler(brokerScheduler);
    registry.setApplicationDestinationPrefixes("/app");
    registry.setUserDestinationPrefix(StompDestinations.USER_PREFIX);
    registry.setPreservePublishOrder(true);
    registry.configureBrokerChannel().interceptors(streamTap);   // §19.3: broadcast topics → the SSE stream
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(csrfChannelInterceptor, authInterceptor);
  }

  @Override
  public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
    registration.setMessageSizeLimit(MAX_INBOUND_FRAME_BYTES)
        .setSendTimeLimit(15_000)
        .setSendBufferSizeLimit(512 * 1024)
        .addDecoratorFactory(liveSockets::decorate);   // closes the sockets of ended sessions (§17.3)
  }

  /** A socket closed without a DISCONNECT frame: release its subscriptions and leases. */
  @EventListener
  public void onDisconnect(SessionDisconnectEvent event) {
    tracker.disconnect(event.getSessionId());
  }

  /** {@code scheme://host[:port]} of {@code stocks.public-url}, plus {@code stocks.security.extra-ws-origins}. */
  static List<String> allowedOrigins(StocksProperties properties) {
    Set<String> out = new LinkedHashSet<>();
    out.add(origin(properties.publicUrl()));
    if (properties.security() != null && properties.security().extraWsOrigins() != null) {
      for (String extra : properties.security().extraWsOrigins()) {
        if (extra != null && !extra.isBlank()) {
          out.add(origin(extra));
        }
      }
    }
    return new ArrayList<>(out);
  }

  static String origin(String url) {
    URI uri = URI.create(url.strip());
    if (uri.getScheme() == null || uri.getHost() == null) {
      throw new IllegalArgumentException("Not an absolute URL: " + url);
    }
    return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
        + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
  }
}
