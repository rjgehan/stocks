package com.stocks.testkit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A browser-like MockMvc client (§17.3): keeps cookies between requests ({@code stocks_sid} from Spring Session JDBC,
 * {@code XSRF-TOKEN}) and, like the SPA, echoes the raw {@code XSRF-TOKEN} cookie as {@code X-XSRF-TOKEN} on unsafe
 * methods. Goes through the real filter chains, so it exercises sessions, CSRF and the security headers.
 *
 * <pre>{@code
 * WebSession alice = WebSession.login(mvc, "alice", Fixtures.PASSWORD);
 * alice.perform(get("/api/v1/me")).getResponse().getStatus();           // 200
 * alice.performWithoutCsrf(post("/api/v1/auth/logout")).getResponse();   // 403 CSRF_INVALID
 * }</pre>
 */
public final class WebSession {

  public static final String SESSION_COOKIE = "stocks_sid";
  public static final String XSRF_COOKIE = "XSRF-TOKEN";
  public static final String XSRF_HEADER = "X-XSRF-TOKEN";
  private static final Set<HttpMethod> SAFE = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS,
      HttpMethod.TRACE);

  private final MockMvc mvc;
  private final Map<String, Cookie> cookies = new LinkedHashMap<>();

  private WebSession(MockMvc mvc) {
    this.mvc = mvc;
  }

  /** No cookies yet. */
  public static WebSession anonymous(MockMvc mvc) {
    return new WebSession(mvc);
  }

  /** CSRF bootstrap + {@code POST /api/v1/auth/login}; fails unless the login answers 200. */
  public static WebSession login(MockMvc mvc, String handle, String password) {
    WebSession s = new WebSession(mvc);
    MvcResult r = s.login(handle, password);
    if (r.getResponse().getStatus() != 200) {
      throw new AssertionError("login as " + handle + " failed: " + r.getResponse().getStatus() + " "
          + contentOf(r));
    }
    return s;
  }

  /** {@code POST /api/v1/auth/login} (fetching the CSRF cookie first if needed); returns the raw result. */
  public MvcResult login(String handle, String password) {
    if (!cookies.containsKey(XSRF_COOKIE)) {
      perform(get("/api/v1/auth/csrf"));
    }
    String body = "{\"handle\":\"" + handle + "\",\"password\":\"" + password + "\"}";
    return perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  /** Sends the request with this session's cookies, plus {@code X-XSRF-TOKEN} on unsafe methods. */
  public MvcResult perform(MockHttpServletRequestBuilder request) {
    return send(request, true);
  }

  /** Same, but never adds {@code X-XSRF-TOKEN} (to test CSRF rejection). */
  public MvcResult performWithoutCsrf(MockHttpServletRequestBuilder request) {
    return send(request, false);
  }

  private MvcResult send(MockHttpServletRequestBuilder request, boolean csrfHeader) {
    if (!cookies.isEmpty()) {
      request.cookie(cookies.values().toArray(Cookie[]::new));
    }
    try {
      MvcResult result = mvc.perform(request.with(r -> {
        if (csrfHeader && !SAFE.contains(HttpMethod.valueOf(r.getMethod())) && xsrf() != null) {
          r.addHeader(XSRF_HEADER, xsrf());
        }
        return r;
      })).andReturn();
      for (Cookie c : result.getResponse().getCookies()) {
        if (c.getMaxAge() == 0 || c.getValue() == null || c.getValue().isEmpty()) {
          cookies.remove(c.getName());
        } else {
          cookies.put(c.getName(), new Cookie(c.getName(), c.getValue()));
        }
      }
      return result;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The raw {@code XSRF-TOKEN} cookie value, or null. */
  public String xsrf() {
    Cookie c = cookies.get(XSRF_COOKIE);
    return c == null ? null : c.getValue();
  }

  /** The {@code stocks_sid} cookie value, or null when there is no session. */
  public String sessionId() {
    Cookie c = cookies.get(SESSION_COOKIE);
    return c == null ? null : c.getValue();
  }

  public Map<String, Cookie> cookies() {
    return Map.copyOf(cookies);
  }

  public static String contentOf(MvcResult r) {
    try {
      return r.getResponse().getContentAsString();
    } catch (Exception e) {
      return "<unreadable>";
    }
  }
}
