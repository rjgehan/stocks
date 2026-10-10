package com.stocks.push;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A local stand-in for a browser push service (FCM, autopush, APNs web push) on 127.0.0.1: it records every request
 * (path, headers, body) and answers with the scripted statuses (201 by default). Tests decrypt the recorded bodies
 * with the "browser" key pair of {@link #subscription}, so they check the wire format end to end.
 */
final class FakePushService implements AutoCloseable {

  /** One request as the push service saw it. */
  record Received(String path, Map<String, List<String>> headers, byte[] body) {
    String header(String name) {
      for (Map.Entry<String, List<String>> e : headers.entrySet()) {
        if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
          return e.getValue().isEmpty() ? null : e.getValue().get(0);
        }
      }
      return null;
    }
  }

  /** A scripted answer. */
  record Reply(int status, Map<String, String> headers) {
    static Reply of(int status) {
      return new Reply(status, Map.of());
    }
  }

  /** The browser side of one subscription: its endpoint and keys. */
  record Browser(String endpoint, KeyPair keys, byte[] auth) {
    String p256dh() {
      return WebPushCrypto.b64(WebPushCrypto.encodePoint((ECPublicKey) keys.getPublic()));
    }

    String authB64() {
      return WebPushCrypto.b64(auth);
    }

    /** Decrypts a body this subscription received (RFC 8291, receiver side). */
    String decrypt(byte[] body) throws Exception {
      return new String(WebPushCryptoTest.decrypt(body, (ECPrivateKey) keys.getPrivate(),
          (ECPublicKey) keys.getPublic(), auth), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  final List<Received> received = new CopyOnWriteArrayList<>();
  private final ConcurrentLinkedDeque<Reply> replies = new ConcurrentLinkedDeque<>();
  private final HttpServer server;
  private int seq;

  FakePushService() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", exchange -> {
      byte[] body;
      try (InputStream in = exchange.getRequestBody()) {
        body = in.readAllBytes();
      }
      received.add(new Received(exchange.getRequestURI().getPath(), Map.copyOf(exchange.getRequestHeaders()), body));
      Reply reply = replies.pollFirst();
      if (reply == null) {
        reply = Reply.of(201);
      }
      reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
      exchange.sendResponseHeaders(reply.status(), -1);
      exchange.close();
    });
    server.start();
  }

  /** {@code http://127.0.0.1:<port>}. */
  String origin() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** A new browser subscription at this service. */
  synchronized Browser subscription() {
    byte[] auth = new byte[16];
    new SecureRandom().nextBytes(auth);
    return new Browser(origin() + "/push/" + (++seq), WebPushCrypto.generateKeyPair(), auth);
  }

  /** The next answers, in order (then 201 again). */
  void reply(Reply... next) {
    for (Reply r : next) {
      replies.addLast(r);
    }
  }

  /** Waits until {@code n} requests arrived (at most 10 s). */
  void await(int n) throws InterruptedException {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (received.size() < n && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
