package com.stocks.marketdata.core;

import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.marketdata.spi.ProviderException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * A provider's REST access (Spring {@link RestClient} over the JDK {@code HttpClient}, §15.3): every call takes a
 * permit from the {@link ProviderRateLimiter}, feeds the rate-limit headers back to it, and turns any failure into a
 * {@link ProviderException}. Credentials are added as headers and never logged.
 */
public class ProviderHttp {

  /** A raw response. */
  public record Response(int status, byte[] body, HttpHeaders headers) {

    public String text() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  private final String provider;
  private final RestClient client;
  private final ProviderRateLimiter limiter;
  private final Supplier<Instant> clock;
  private final Map<String, String> authHeaders;
  private volatile Instant lastSuccessAt;
  private volatile String lastError;

  public ProviderHttp(String provider, Duration connectTimeout, Duration readTimeout, ProviderRateLimiter limiter,
                      Supplier<Instant> clock, Map<String, String> authHeaders) {
    this.provider = provider;
    this.limiter = limiter;
    this.clock = clock;
    this.authHeaders = Map.copyOf(authHeaders);
    HttpClient http = HttpClient.newBuilder().connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .executor(Executors.newVirtualThreadPerTaskExecutor()).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(readTimeout);
    this.client = RestClient.builder().requestFactory(factory).build();
  }

  public ProviderRateLimiter limiter() {
    return limiter;
  }

  public Instant lastSuccessAt() {
    return lastSuccessAt;
  }

  public String lastError() {
    return lastError;
  }

  /** GET {@code baseUrl + path} with query parameters (null values are skipped); JSON body. */
  public JsonNode getJson(RefreshPriority priority, String baseUrl, String path, Map<String, ?> query) {
    Response r = get(priority, baseUrl, path, query);
    try {
      return Json.parse(r.text());
    } catch (RuntimeException e) {
      lastError = "unparseable response from " + path;
      throw new ProviderException(provider, r.status(), "unparseable JSON from " + path, null, e);
    }
  }

  public Response get(RefreshPriority priority, String baseUrl, String path, Map<String, ?> query) {
    if (!limiter.tryAcquire(priority)) {
      throw new ProviderException(provider, 429, "local rate limit reached (" + priority + ")", null, null);
    }
    UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl + path);
    Map<String, ?> q = query == null ? Map.of() : query;
    q.forEach((k, v) -> {
      if (v != null) {
        b.queryParam(k, v);
      }
    });
    URI uri = b.encode().build().toUri();
    Response r;
    try {
      r = client.get().uri(uri).headers(h -> authHeaders.forEach(h::set))
          .exchange((req, res) -> new Response(res.getStatusCode().value(), res.getBody().readAllBytes(),
              res.getHeaders()), true);
    } catch (RuntimeException e) {
      lastError = path + ": " + rootMessage(e);
      throw new ProviderException(provider, 0, path + ": " + rootMessage(e), null, e);
    }
    Integer remaining = intHeader(r.headers(), "X-RateLimit-Remaining");
    Duration resetIn = null;
    Long reset = longHeader(r.headers(), "X-RateLimit-Reset");
    if (reset != null) {
      resetIn = Duration.between(clock.get(), Instant.ofEpochSecond(reset));
    }
    limiter.onResponse(r.status(), remaining, resetIn);
    if (r.status() == 429) {
      lastError = path + ": HTTP 429";
      throw new ProviderException(provider, 429, "HTTP 429 on " + path, reset == null ? null
          : Instant.ofEpochSecond(reset), null);
    }
    if (r.status() >= 400) {
      String body = r.text();
      lastError = path + ": HTTP " + r.status();
      throw new ProviderException(provider, r.status(), "HTTP " + r.status() + " on " + path + ": "
          + (body.length() > 200 ? body.substring(0, 200) : body), null, null);
    }
    lastSuccessAt = clock.get();
    lastError = null;
    return r;
  }

  private static Integer intHeader(HttpHeaders h, String name) {
    String v = h.getFirst(name);
    try {
      return v == null ? null : Integer.valueOf(v.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Long longHeader(HttpHeaders h, String name) {
    String v = h.getFirst(name);
    try {
      return v == null ? null : Long.valueOf(v.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static String rootMessage(Throwable t) {
    Throwable c = t;
    while (c.getCause() != null && c.getCause() != c) {
      c = c.getCause();
    }
    return c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
  }

  /** Query-map builder that keeps insertion order. */
  public static Map<String, Object> query(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }
}
