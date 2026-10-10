package com.stocks.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.SessionType;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.realtime.RealtimeTestSupport.CapturingMessaging;
import com.stocks.security.PlayerAuthenticationToken;
import com.stocks.testkit.TestPrincipals;
import com.stocks.testkit.TestTimes;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * §17.1 chain 3 and §19: STOMP CSRF on CONNECT (raw cookie token) and the destination / cap rules. A refusal drops
 * the frame and answers the session with an ERROR frame on {@code clientOutboundChannel} ({@link StompErrors}).
 */
class StompInterceptorsTest {

  static final String TOKEN = "3c2f7e5a-1b2d-4e6f-8a9b-0c1d2e3f4a5b";

  MutableTimeSource time;
  RecordingMarketData md;
  SubscriptionTracker tracker;
  QuoteFanout fanout;
  StompAuthInterceptor auth;
  ChannelInterceptor csrf;
  List<Message<?>> outbound;

  @BeforeEach
  void setUp() {
    time = TestTimes.clock();
    md = new RecordingMarketData(time);
    tracker = new SubscriptionTracker(md, time);
    fanout = new QuoteFanout(md, tracker, new QuoteMessages(time, RealtimeTestSupport.calendarIn(
        SessionType.REGULAR)), time, RealtimeTestSupport.provider(new CapturingMessaging()));
    outbound = new CopyOnWriteArrayList<>();
    MessageChannel clientOutbound = (message, timeout) -> outbound.add(message);
    DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
    factory.registerSingleton("clientOutboundChannel", clientOutbound);
    StompErrors errors = new StompErrors(factory.getBeanProvider(MessageChannel.class));
    auth = new StompAuthInterceptor(tracker, fanout, errors);
    csrf = StompConfig.csrfChannelInterceptor(errors);
  }

  static Authentication player(long id) {
    return new PlayerAuthenticationToken(TestPrincipals.session(id, "ply_" + id, "p" + id));
  }

  static Authentication admin(long id) {
    return new PlayerAuthenticationToken(TestPrincipals.adminSession(id, "ply_" + id, "admin" + id));
  }

  static Authentication botKey(long id) {
    PlayerPrincipal p = TestPrincipals.apiKey(id, "ply_" + id, "bot" + id, PlayerKind.AI, Set.of("market:read"), 9);
    return new PlayerAuthenticationToken(p);
  }

  static Message<byte[]> frame(StompCommand command, String sessionId, Authentication user, String destination,
                               String subscriptionId, String csrfHeader, String sessionToken) {
    StompHeaderAccessor a = StompHeaderAccessor.create(command);
    a.setSessionId(sessionId);
    Map<String, Object> attrs = new HashMap<>();
    if (sessionToken != null) {
      attrs.put(StompCsrf.SESSION_ATTRIBUTE, sessionToken);
    }
    a.setSessionAttributes(attrs);
    if (user != null) {
      a.setUser(user);
    }
    if (destination != null) {
      a.setDestination(destination);
    }
    if (subscriptionId != null) {
      a.setSubscriptionId(subscriptionId);
    }
    if (csrfHeader != null) {
      a.setNativeHeader(StompCsrf.HEADER, csrfHeader);
    }
    return MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
  }

  static Message<byte[]> connect(String sessionId, Authentication user) {
    return frame(StompCommand.CONNECT, sessionId, user, null, null, TOKEN, TOKEN);
  }

  static Message<byte[]> subscribe(String sessionId, Authentication user, String destination, String id) {
    return frame(StompCommand.SUBSCRIBE, sessionId, user, destination, id, null, TOKEN);
  }

  /** Asserts that {@code result} dropped the frame and the session got an ERROR containing {@code text}. */
  void assertRejected(Message<?> result, String sessionId, String text) {
    assertThat(result).as("dropped").isNull();
    assertThat(outbound).as("an ERROR frame was sent").isNotEmpty();
    Message<?> error = outbound.remove(outbound.size() - 1);
    StompHeaderAccessor a = StompHeaderAccessor.wrap(error);
    assertThat(a.getCommand()).isEqualTo(StompCommand.ERROR);
    assertThat(SimpMessageHeaderAccessor.getSessionId(error.getHeaders())).isEqualTo(sessionId);
    assertThat(a.getMessage()).contains(text);
  }

  // ------------------------------------------------------------------------------------------- CSRF

  @Test
  void connectNeedsTheRawCookieTokenAsHeader() {
    assertThat(csrf.preSend(connect("s1", player(1)), null)).isNotNull();
    assertRejected(csrf.preSend(frame(StompCommand.CONNECT, "s1", player(1), null, null, null, TOKEN), null), "s1",
        "CSRF_INVALID");
    assertRejected(csrf.preSend(frame(StompCommand.CONNECT, "s2", player(1), null, null, "wrong", TOKEN), null),
        "s2", "CSRF_INVALID");
    assertRejected(csrf.preSend(frame(StompCommand.CONNECT, "s3", player(1), null, null, TOKEN, null), null), "s3",
        "CSRF_INVALID");
    // only CONNECT is checked
    assertThat(csrf.preSend(subscribe("s1", player(1), "/topic/leaderboard", "a"), null)).isNotNull();
    assertThat(outbound).isEmpty();
  }

  @Test
  void theHandshakeRemembersTheCookieTokenWithoutCreatingOne() {
    StompCsrf.Handshake handshake = new StompCsrf.Handshake(CookieCsrfTokenRepository.withHttpOnlyFalse());
    MockHttpServletRequest withCookie = new MockHttpServletRequest("GET", "/ws");
    withCookie.setCookies(new Cookie("XSRF-TOKEN", TOKEN));
    Map<String, Object> attrs = new HashMap<>();
    assertThat(handshake.beforeHandshake(new ServletServerHttpRequest(withCookie),
        new ServletServerHttpResponse(new MockHttpServletResponse()), null, attrs)).isTrue();
    assertThat(attrs).containsEntry(StompCsrf.SESSION_ATTRIBUTE, TOKEN);

    Map<String, Object> none = new HashMap<>();
    MockHttpServletResponse response = new MockHttpServletResponse();
    handshake.beforeHandshake(new ServletServerHttpRequest(new MockHttpServletRequest("GET", "/ws")),
        new ServletServerHttpResponse(response), null, none);
    assertThat(none).isEmpty();
    assertThat(response.getCookies()).isEmpty();
  }

  // ------------------------------------------------------------------------------------------- CONNECT

  @Test
  void connectNeedsAnAuthenticatedPlayerAndAtMostFiveSessions() {
    assertRejected(auth.preSend(connect("s0", null), null), "s0", "UNAUTHENTICATED");
    for (int i = 1; i <= 5; i++) {
      assertThat(auth.preSend(connect("s" + i, player(1)), null)).isNotNull();
    }
    assertRejected(auth.preSend(connect("s6", player(1)), null), "s6", "RATE_LIMITED");
    assertThat(tracker.userOf(1)).hasValue("ply_1");
    auth.preSend(frame(StompCommand.DISCONNECT, "s1", player(1), null, null, null, null), null);
    assertThat(auth.preSend(connect("s6", player(1)), null)).isNotNull();
  }

  // ------------------------------------------------------------------------------------------- SUBSCRIBE

  @Test
  void onlyTheSection19DestinationsAndOwnQueues() {
    auth.preSend(connect("s1", player(1)), null);
    for (String ok : new String[] {"/topic/quote.AAPL", "/topic/tick.AAPL", "/topic/bars.AAPL.1Min",
        "/topic/market.session", "/topic/leaderboard", "/topic/feed", "/user/queue/orders", "/user/queue/account",
        "/user/queue/notifications"}) {
      assertThat(auth.preSend(subscribe("s1", player(1), ok, "id-" + ok), null)).as(ok).isNotNull();
    }
    for (String bad : new String[] {"/queue/orders-users2", "/topic/secret", "/user/ply_2/queue/orders",
        "/app/orders"}) {
      assertRejected(auth.preSend(subscribe("s1", player(1), bad, "x" + bad), null), "s1", "FORBIDDEN");
    }
  }

  @Test
  void theAdminQueueIsForAdminsOnASession() {
    auth.preSend(connect("s1", player(1)), null);
    assertRejected(auth.preSend(subscribe("s1", player(1), "/user/queue/admin", "a"), null), "s1", "admins");
    auth.preSend(connect("s2", admin(2)), null);
    assertThat(auth.preSend(subscribe("s2", admin(2), "/user/queue/admin", "a"), null)).isNotNull();
    auth.preSend(connect("s3", botKey(3)), null);
    assertRejected(auth.preSend(subscribe("s3", botKey(3), "/user/queue/admin", "a"), null), "s3", "admins");
  }

  @Test
  void badSymbolsAndTheSixtyFirstMarketSubscriptionAreDroppedSilently() {
    auth.preSend(connect("s1", player(1)), null);
    assertThat(auth.preSend(subscribe("s1", player(1), "/topic/quote.aapl", "a"), null)).isNull();
    for (int i = 0; i < SubscriptionTracker.MAX_MARKET_SUBSCRIPTIONS_PER_SESSION; i++) {
      assertThat(auth.preSend(subscribe("s1", player(1), "/topic/tick.S" + i, "t" + i), null)).isNotNull();
    }
    assertThat(auth.preSend(subscribe("s1", player(1), "/topic/tick.EXTRA", "extra"), null)).isNull();
    assertThat(outbound).as("no ERROR: the socket stays open").isEmpty();
    assertThat(tracker.subscribers("/topic/tick.EXTRA")).isZero();
    assertThat(md.lease("S0", SubscriptionTracker.ownerKey("s1"))).isPresent();
  }

  @Test
  void unsubscribeAndDisconnectReleaseLeasesAndClientsCannotSend() {
    auth.preSend(connect("s1", player(1)), null);
    auth.preSend(subscribe("s1", player(1), "/topic/quote.AAPL", "q"), null);
    assertThat(md.lease("AAPL", SubscriptionTracker.ownerKey("s1"))).isPresent();
    auth.preSend(frame(StompCommand.UNSUBSCRIBE, "s1", player(1), null, "q", null, null), null);
    assertThat(md.lease("AAPL", SubscriptionTracker.ownerKey("s1"))).isEmpty();

    assertRejected(auth.preSend(frame(StompCommand.SEND, "s1", player(1), "/app/x", null, null, null), null), "s1",
        "FORBIDDEN");
  }

  @Test
  void subscribingBeforeConnectingAndDuplicateIdsAreRefused() {
    assertRejected(auth.preSend(subscribe("ghost", player(1), "/topic/leaderboard", "a"), null), "ghost",
        "CONNECT first");
    auth.preSend(connect("s1", player(1)), null);
    auth.preSend(subscribe("s1", player(1), "/topic/leaderboard", "a"), null);
    assertRejected(auth.preSend(subscribe("s1", player(1), "/topic/feed", "a"), null), "s1", "already in use");
  }
}
