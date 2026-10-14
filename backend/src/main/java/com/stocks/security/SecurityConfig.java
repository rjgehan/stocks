package com.stocks.security;

import com.stocks.apilog.ApiAccessLogFilter;
import com.stocks.common.error.BearerChallenge;
import com.stocks.common.time.TimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.agents.ApiAccessRecorder;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.security.Scopes;
import com.stocks.oauth.AccessTokens;
import com.stocks.oauth.OAuthProperties;
import com.stocks.oauth.OAuthUrls;
import jakarta.servlet.DispatcherType;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The security filter chains of §17.1 (Spring Security 7.1.1), in order:
 * <ol start="0">
 *   <li><b>actuator</b> (the management port 8081, never published or tunneled): the exposed endpoints (health,
 *       info, metrics, prometheus) are open on that internal port.</li>
 *   <li><b>oauth</b> {@code /oauth2/**}, {@code /.well-known/**} (Phase 2, §17.9): the authorization server's public
 *       endpoints; no CSRF, no session.</li>
 *   <li><b>admin</b> {@code /api/v1/admin/**}: {@link AdminSessionOnlyFilter} first (any {@code Authorization} header →
 *       403 {@code ADMIN_REQUIRES_SESSION}), then session + {@code ROLE_ADMIN} + CSRF.</li>
 *   <li><b>h2-console</b> (dev only, {@code spring.h2.console.enabled}).</li>
 *   <li><b>api</b> {@code /api/**} (incl. Swagger UI: {@code /api/docs} → {@code /api/swagger-ui/index.html}),
 *       {@code /mcp}, {@code /v3/**} (the OpenAPI JSON and YAML): {@code Bearer stk_…}, or on {@code /mcp} an OAuth
 *       access token (§17.9.5) ({@link BearerTokenFilter}: stateless, cookies ignored, no CSRF) or a session cookie +
 *       CSRF. Public: only
 *       {@code GET /auth/csrf}, {@code POST /auth/login}, {@code POST /auth/signup}. Per-route scopes and
 *       session-only routes per §18 ({@link RouteAuthorization}).</li>
 *   <li><b>ws</b> {@code /ws}: session cookie at the handshake (STOMP CSRF is WS5's {@code csrfChannelInterceptor}).</li>
 *   <li><b>web</b>: everything else (SPA, static files), public.</li>
 * </ol>
 * Every chain answers 401/403 with the §6.5 problem body ({@link SecurityErrors}) and sends the §24.3 headers
 * (CSP, {@code Referrer-Policy: same-origin}, {@code nosniff}, HSTS when secure). {@code /api/**} and {@code /mcp}
 * responses are {@code Cache-Control: no-store}; static files get theirs from {@code WebConfig}.
 * CSRF: one {@link CookieCsrfTokenRepository#withHttpOnlyFalse()} ({@code XSRF-TOKEN} cookie, {@code X-XSRF-TOKEN}
 * header, {@code csrf.spa()}) shared by every chain and by STOMP.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

  /** §24.3. */
  public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; img-src 'self' data: https:; "
      + "connect-src 'self' wss:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'self'; "
      + "form-action 'self'";

  /**
   * Chain 2. {@code /v3/**} covers every springdoc document path: {@code /v3/api-docs}, its groups and the YAML
   * variants ({@code /v3/api-docs.yaml}), which would otherwise fall through to the public web chain.
   */
  static final String[] API_PATHS = {"/api/**", "/mcp", "/mcp/**", "/v3/**", "/swagger-ui/**", "/swagger-ui.html"};

  /** Anonymous callers may use these (their CSRF failures stay 403 {@code CSRF_INVALID}). */
  static final RequestMatcher PUBLIC_API = new OrRequestMatcher(
      PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/auth/csrf"),
      PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/login"),
      PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/signup"));

  @Bean
  PasswordEncoder passwordEncoder(StocksProperties properties) {
    // §17.3: DelegatingPasswordEncoder with bcrypt (cost 12; stocks.security.password-hash-cost). Hashes are stored
    // as {bcrypt}… so a future algorithm change keeps old hashes verifiable.
    BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(properties.security().passwordHashCost());
    DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", bcrypt));
    encoder.setDefaultPasswordEncoderForMatches(bcrypt);
    return encoder;
  }

  @Bean
  CookieCsrfTokenRepository csrfTokenRepository() {
    CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
    return repository;
  }

  /**
   * The Spring Session cookie, from {@code server.servlet.session.cookie.*} ({@code stocks_sid}; prod
   * {@code __Host-stocks_sid}, Secure). Explicit, so it does not depend on the servlet container's
   * {@code SessionCookieConfig} (MockMvc has none and would fall back to {@code SESSION}).
   */
  @Bean
  CookieSerializer sessionCookieSerializer(Environment env) {
    DefaultCookieSerializer serializer = new DefaultCookieSerializer();
    serializer.setCookieName(env.getProperty("server.servlet.session.cookie.name", "stocks_sid"));
    serializer.setCookiePath(env.getProperty("server.servlet.session.cookie.path", "/"));
    serializer.setUseHttpOnlyCookie(env.getProperty("server.servlet.session.cookie.http-only", Boolean.class, true));
    serializer.setUseSecureCookie(env.getProperty("server.servlet.session.cookie.secure", Boolean.class, false));
    String sameSite = env.getProperty("server.servlet.session.cookie.same-site", "lax");
    serializer.setSameSite(sameSite.substring(0, 1).toUpperCase(Locale.ROOT) + sameSite.substring(1).toLowerCase(Locale.ROOT));
    return serializer;
  }

  @Bean
  SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  ProblemWriter securityProblemWriter(JsonMapper jsonMapper) {
    return new ProblemWriter(jsonMapper);
  }

  @Bean
  ApiKeyAuthenticationProvider apiKeyAuthenticationProvider(JdbcClient jdbc, TimeSource time,
                                                            StocksProperties properties, PlayerQueries players) {
    return new ApiKeyAuthenticationProvider(jdbc, time, properties, players);
  }

  /** §17.9.5: OAuth access tokens on {@code /mcp} (used only while {@code stocks.oauth.enabled}). */
  @Bean
  OAuthTokenAuthenticationProvider oauthTokenAuthenticationProvider(AccessTokens tokens, JdbcClient jdbc,
                                                                    PlayerQueries players, TimeSource time,
                                                                    OAuthProperties oauth, ActivityRecorder activity) {
    return new OAuthTokenAuthenticationProvider(tokens, jdbc, players, time, oauth.grantCacheTtl(), activity);
  }

  /**
   * The chain-2 401 challenge (§17.1), with {@code resource_metadata} while OAuth is on (§17.9.5); also used by
   * {@code ProblemDetailsAdvice} for the 401s controllers raise.
   */
  @Bean
  BearerChallenge bearerChallenge(OAuthProperties oauth, OAuthUrls urls) {
    return oauth.enabled() ? BearerChallenge.of(urls.protectedResourceMetadata()) : BearerChallenge.DEFAULT;
  }

  // ----------------------------------------------------------------------------------------------- chains

  @Bean
  @Order(0)
  SecurityFilterChain actuatorChain(HttpSecurity http, ProblemWriter problems, SecurityContextRepository contexts)
      throws Exception {
    SecurityErrors errors = new SecurityErrors(problems, false, request -> false);
    // The management port is a separate server without Spring Session, so no player can authenticate there; it is
    // protected by never being published or tunneled (§23.1, §24.1). Only the endpoints in
    // management.endpoints.web.exposure.include exist (health, info, metrics, prometheus).
    http.securityMatcher(EndpointRequest.toAnyEndpoint())
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .csrf(AbstractHttpConfigurer::disable); // read-only GET endpoints; no browser forms on 8081
    common(http, errors, contexts, true);
    return http.build();
  }

  /**
   * The OAuth 2.1 authorization server (§17.9): {@code /oauth2/**} and {@code /.well-known/**} are public. No CSRF
   * (the token, register and revoke endpoints are server-to-server form and JSON posts; authorize is a GET that only
   * stores a pending request) and no session is read or created; the controllers set their own
   * {@code Cache-Control} (metadata cacheable, token answers {@code no-store}) and CORS.
   */
  @Bean
  @Order(1)
  SecurityFilterChain oauthChain(HttpSecurity http, ProblemWriter problems, SecurityContextRepository contexts)
      throws Exception {
    SecurityErrors errors = new SecurityErrors(problems, false, request -> false);
    http.securityMatcher("/oauth2/**", "/.well-known/**")
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(m -> m.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    common(http, errors, new RequestAttributeSecurityContextRepository(), false);
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain adminChain(HttpSecurity http, ProblemWriter problems, CookieCsrfTokenRepository csrf,
                                 SecurityContextRepository contexts, PlayerQueries players) throws Exception {
    SecurityErrors errors = new SecurityErrors(problems, false, request -> false);
    http.securityMatcher("/api/v1/admin/**")
        .addFilterBefore(new AdminSessionOnlyFilter(problems), SecurityContextHolderFilter.class)
        .authorizeHttpRequests(a -> a.anyRequest().access(RouteAuthorization.activeAdmin(players)))
        .csrf(c -> c.spa().csrfTokenRepository(csrf));
    common(http, errors, contexts, true);
    return http.build();
  }

  @Bean
  @Order(3)
  @ConditionalOnBooleanProperty("spring.h2.console.enabled")
  SecurityFilterChain h2ConsoleChain(HttpSecurity http) throws Exception {
    // dev only: the console posts forms and uses frames and inline scripts, so no CSRF and no CSP here.
    http.securityMatcher("/h2-console", "/h2-console/**")
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .csrf(AbstractHttpConfigurer::disable)
        .headers(h -> h.frameOptions(f -> f.sameOrigin()))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable);
    return http.build();
  }

  @Bean
  @Order(4)
  SecurityFilterChain apiChain(HttpSecurity http, ProblemWriter problems, CookieCsrfTokenRepository csrf,
                               SecurityContextRepository contexts, ApiKeyAuthenticationProvider apiKeys,
                               OAuthTokenAuthenticationProvider oauthTokens, OAuthProperties oauth,
                               BearerChallenge challenge, RateLimitServiceImpl rateLimits, ActivityRecorder activity,
                               ApiAccessRecorder apiAccessLog) throws Exception {
    SecurityErrors errors = new SecurityErrors(problems, challenge, PUBLIC_API);
    AuthFailureLimiter failures = new AuthFailureLimiter(rateLimits, problems);
    http.securityMatcher(API_PATHS)
        .addFilterBefore(new BearerTokenFilter(apiKeys, oauth.enabled() ? oauthTokens : null, challenge, problems,
            rateLimits), CsrfFilter.class)
        // Phase 2 §17.10: API-key calls, counted after the rest of the chain answered (403/429 included)
        .addFilterAfter(new ApiAccessLogFilter(apiAccessLog), BearerTokenFilter.class)
        // §17.5: reads and per-IP limits, charged only for requests that passed authorization
        .addFilterAfter(new RateLimitFilter(rateLimits, activity, problems), AuthorizationFilter.class)
        .csrf(c -> c.spa().csrfTokenRepository(csrf).ignoringRequestMatchers(BearerTokenFilter.BEARER))
        .authorizeHttpRequests(a -> a
            // the ASYNC dispatch that completes a stream (GET /api/v1/stream, §19.3) was authorized as a REQUEST
            .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
            // public (§17.1 chain 2)
            .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/signup").permitAll()
            // §18.1: notifications take account:read on any channel; GET /me any; the rest of /me is session-only
            .requestMatchers("/api/v1/me/notifications", "/api/v1/me/notifications/**")
                .access(RouteAuthorization.scope(Scopes.ACCOUNT_READ))
            .requestMatchers(HttpMethod.GET, "/api/v1/me").authenticated()
            .requestMatchers("/api/v1/me/**", "/api/v1/auth/logout", "/api/v1/auth/reauth")
                .access(RouteAuthorization.session())
            .requestMatchers(HttpMethod.POST, "/api/v1/account/reset")
                .access(RouteAuthorization.sessionWithScope(Scopes.TRADE))
            // §18.2 market:read
            .requestMatchers("/api/v1/market/**", "/api/v1/assets/**", "/api/v1/quotes", "/api/v1/snapshots/**",
                "/api/v1/bars/**", "/api/v1/news", "/api/v1/rules")
                .access(RouteAuthorization.scope(Scopes.MARKET_READ))
            // §18.2 trade (preview is account:read)
            .requestMatchers(HttpMethod.POST, "/api/v1/orders/preview", "/api/v1/order-groups/preview")
                .access(RouteAuthorization.scope(Scopes.ACCOUNT_READ))
            .requestMatchers(HttpMethod.POST, "/api/v1/order-groups").access(RouteAuthorization.scope(Scopes.TRADE))
            .requestMatchers(HttpMethod.PATCH, "/api/v1/order-groups/*").access(RouteAuthorization.scope(Scopes.TRADE))
            .requestMatchers(HttpMethod.DELETE, "/api/v1/order-groups/*").access(RouteAuthorization.scope(Scopes.TRADE))
            .requestMatchers(HttpMethod.GET, "/api/v1/order-groups/*")
                .access(RouteAuthorization.scope(Scopes.ACCOUNT_READ))
            .requestMatchers(HttpMethod.POST, "/api/v1/orders", "/api/v1/account/positions/*/close",
                "/api/v1/watchlists", "/api/v1/watchlists/**")
                .access(RouteAuthorization.scope(Scopes.TRADE))
            // Phase 2: run reports (§20.6), journal notes (§20.7) and claiming/finishing Run now requests (§20.8)
            // need trade; the services allow AI players' keys only
            .requestMatchers(HttpMethod.POST, "/api/v1/agent-runs", "/api/v1/journal",
                "/api/v1/agent-runs/requests/claim", "/api/v1/agent-runs/requests/*/finish")
                .access(RouteAuthorization.scope(Scopes.TRADE))
            .requestMatchers(HttpMethod.PATCH, "/api/v1/orders/*").access(RouteAuthorization.scope(Scopes.TRADE))
            .requestMatchers(HttpMethod.PUT, "/api/v1/watchlists/**").access(RouteAuthorization.scope(Scopes.TRADE))
            .requestMatchers(HttpMethod.DELETE, "/api/v1/orders", "/api/v1/orders/*", "/api/v1/watchlists/**")
                .access(RouteAuthorization.scope(Scopes.TRADE))
            // §16.11 (Phase 2) feed reactions are writes: trade
            .requestMatchers(HttpMethod.PUT, "/api/v1/feed/*/reaction").access(RouteAuthorization.scope(Scopes.TRADE))
            .requestMatchers(HttpMethod.DELETE, "/api/v1/feed/*/reaction")
                .access(RouteAuthorization.scope(Scopes.TRADE))
            // §18.2 account:read
            .requestMatchers(HttpMethod.GET, "/api/v1/account/**", "/api/v1/orders", "/api/v1/orders/**",
                "/api/v1/watchlists", "/api/v1/watchlists/**")
                .access(RouteAuthorization.scope(Scopes.ACCOUNT_READ))
            // §18.2 league:read
            .requestMatchers("/api/v1/leaderboard", "/api/v1/players", "/api/v1/players/**", "/api/v1/feed",
                "/api/v1/league/**")
                .access(RouteAuthorization.scope(Scopes.LEAGUE_READ))
            // Phase 2 price alerts (§18.2, §19.1): read with account:read, every write with trade
            .requestMatchers(HttpMethod.GET, "/api/v1/alerts", "/api/v1/alerts/**")
                .access(RouteAuthorization.scope(Scopes.ACCOUNT_READ))
            .requestMatchers("/api/v1/alerts", "/api/v1/alerts/**").access(RouteAuthorization.scope(Scopes.TRADE))
            // everything else on this chain (incl. /mcp, /v3/**, Swagger UI, /api/v1/test/**): any player.
            // Each MCP tool's scope is checked per call by McpToolHandler (McpToolCatalog.SCOPES, §20.3).
            .anyRequest().authenticated());
    common(http, errors, contexts, true);
    // §17.5: an anonymous 401 (an unknown or expired session cookie, no credentials) costs the IP's anonymous budget
    http.exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) -> {
      if (!failures.charge(request, response)) {
        errors.commence(request, response, ex);
      }
    }));
    return http.build();
  }

  @Bean
  @Order(5)
  SecurityFilterChain wsChain(HttpSecurity http, ProblemWriter problems, CookieCsrfTokenRepository csrf,
                              SecurityContextRepository contexts) throws Exception {
    SecurityErrors errors = new SecurityErrors(problems, false, request -> false);
    http.securityMatcher("/ws", "/ws/**")
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .csrf(c -> c.spa().csrfTokenRepository(csrf));
    common(http, errors, contexts, true);
    return http.build();
  }

  @Bean
  @Order(6)
  SecurityFilterChain webChain(HttpSecurity http, ProblemWriter problems, CookieCsrfTokenRepository csrf,
                               SecurityContextRepository contexts) throws Exception {
    SecurityErrors errors = new SecurityErrors(problems, false, request -> false);
    http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .csrf(c -> c.spa().csrfTokenRepository(csrf));
    common(http, errors, contexts, false);
    return http.build();
  }

  /**
   * Shared by the player-facing chains: no form login, basic auth, logout filter or request cache (the JSON
   * endpoints of {@code identity.web.AuthController} replace them), problem-body errors, §24.3 headers.
   *
   * @param noStore {@code Cache-Control: no-store} (API, admin, ws); false leaves caching to {@code WebConfig}
   */
  private static void common(HttpSecurity http, SecurityErrors errors, SecurityContextRepository contexts,
                             boolean noStore) throws Exception {
    http.formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .securityContext(c -> c.securityContextRepository(contexts))
        .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
        .headers(h -> {
          h.contentSecurityPolicy(c -> c.policyDirectives(CONTENT_SECURITY_POLICY));
          h.referrerPolicy(r -> r.policy(ReferrerPolicy.SAME_ORIGIN));
          h.cacheControl(c -> c.disable());
          if (noStore) {
            h.addHeaderWriter(new StaticHeadersWriter("Cache-Control", "no-store"));
          }
        });
  }
}
