package com.stocks.marketdata.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * An HTTP provider double on the JDK's built-in server (no Docker, no extra dependency): routes by path, answers
 * recorded JSON fixtures from {@code classpath:fixtures/…}, and records every request (path, query, headers).
 */
public final class FakeHttpServer implements AutoCloseable {

  /** A request as received. Header names are lower case. */
  public record Request(String method, String path, Map<String, String> query, Map<String, String> headers) {}

  /** A canned response. */
  public record Response(int status, String body, Map<String, String> headers) {
    public static Response json(String body) {
      return new Response(200, body, Map.of());
    }
  }

  private final HttpServer server;
  private final Map<String, Function<Request, Response>> routes = new ConcurrentHashMap<>();
  private final List<Request> requests = new CopyOnWriteArrayList<>();

  public FakeHttpServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", this::handle);
    server.start();
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public FakeHttpServer route(String path, Function<Request, Response> handler) {
    routes.put(path, handler);
    return this;
  }

  public FakeHttpServer json(String path, String body) {
    return route(path, r -> Response.json(body));
  }

  public FakeHttpServer fixture(String path, String resource) {
    String body = fixture(resource);
    return route(path, r -> Response.json(body));
  }

  public FakeHttpServer status(String path, int status, String body, Map<String, String> headers) {
    return route(path, r -> new Response(status, body, headers));
  }

  public List<Request> requests() {
    return List.copyOf(requests);
  }

  public List<Request> requests(String path) {
    return requests.stream().filter(r -> r.path().equals(path)).toList();
  }

  public static String fixture(String resource) {
    try (InputStream in = FakeHttpServer.class.getClassLoader().getResourceAsStream("fixtures/" + resource)) {
      if (in == null) {
        throw new IllegalArgumentException("no fixture " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void handle(HttpExchange ex) throws IOException {
    Map<String, String> query = new TreeMap<>();
    String raw = ex.getRequestURI().getRawQuery();
    if (raw != null) {
      for (String kv : raw.split("&")) {
        int eq = kv.indexOf('=');
        String k = URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), StandardCharsets.UTF_8);
        String v = eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
        query.put(k, v);
      }
    }
    Map<String, String> headers = new TreeMap<>();
    ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), String.join(",", v)));
    Request req = new Request(ex.getRequestMethod(), ex.getRequestURI().getPath(), query, headers);
    requests.add(req);
    Function<Request, Response> route = routes.get(req.path());
    Response res = route == null ? new Response(404, "{\"message\":\"not found\"}", Map.of()) : route.apply(req);
    byte[] body = res.body() == null ? new byte[0] : res.body().getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json");
    res.headers().forEach((k, v) -> ex.getResponseHeaders().set(k, v));
    ex.sendResponseHeaders(res.status(), body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      ex.getResponseBody().write(body);
    }
    ex.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
