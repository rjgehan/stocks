package com.stocks.oauth.cimd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The SSRF-safe CIMD fetch (§17.9.2) against a local socket server (plain HTTP through a test connector; production
 * wraps the same socket in TLS): one DNS resolution whose every address must be public, the connection to the
 * validated address, no redirects, the size cap (Content-Length, chunked, until close), one overall deadline, and
 * identity encoding only.
 */
class SafeHttpsFetcherTest {

  private ServerSocket server;
  private Thread serverThread;

  @AfterEach
  void stop() throws IOException {
    if (server != null) {
      server.close();
    }
    if (serverThread != null) {
      serverThread.interrupt();
    }
  }

  /** A one-request server that answers with {@code respond}. */
  private int serve(Consumer<OutputStream> respond) throws IOException {
    server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
    serverThread = Thread.ofVirtual().start(() -> {
      try (Socket s = server.accept()) {
        InputStream in = s.getInputStream();
        // read the request head
        int matched = 0;
        int b;
        while (matched < 4 && (b = in.read()) != -1) {
          matched = (b == '\r' || b == '\n') ? matched + 1 : 0;
        }
        respond.accept(s.getOutputStream());
      } catch (IOException ignored) {
        // the client may hang up first
      }
    });
    return server.getLocalPort();
  }

  private static void write(OutputStream out, String s) {
    try {
      out.write(s.getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static final SafeHttpsFetcher.Connector PLAIN = (address, port, host, timeout) -> {
    Socket socket = new Socket();
    socket.connect(new InetSocketAddress(address, port), timeout);
    return socket;
  };

  private SafeHttpsFetcher local(Duration timeout, int maxBytes) {
    return new SafeHttpsFetcher(timeout, maxBytes, a -> true, host -> new InetAddress[] {
        InetAddress.getLoopbackAddress()}, PLAIN, "http");
  }

  private static URI url(int port) {
    return URI.create("http://client.example:" + port + "/meta.json");
  }

  @Test
  void readsAContentLengthBodyAndTheCacheHeader() throws Exception {
    String json = "{\"client_id\":\"x\"}";
    int port = serve(out -> write(out, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nCache-Control: "
        + "max-age=600\r\nContent-Length: " + json.length() + "\r\n\r\n" + json));
    CimdFetcher.Response r = local(Duration.ofSeconds(3), 8192).fetch(url(port));
    assertThat(new String(r.body(), StandardCharsets.UTF_8)).isEqualTo(json);
    assertThat(r.cacheControl()).isEqualTo("max-age=600");
  }

  @Test
  void readsAChunkedBody() throws Exception {
    int port = serve(out -> write(out, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
        + "5\r\n{\"a\":\r\n3\r\n12}\r\n0\r\n\r\n"));
    assertThat(new String(local(Duration.ofSeconds(3), 8192).fetch(url(port)).body(), StandardCharsets.UTF_8))
        .isEqualTo("{\"a\":12}");
  }

  @Test
  void refusesBodiesOverTheCap() throws Exception {
    int port = serve(out -> write(out, "HTTP/1.1 200 OK\r\nContent-Length: 9000\r\n\r\n" + "x".repeat(9000)));
    assertThatThrownBy(() -> local(Duration.ofSeconds(3), 8192).fetch(url(port)))
        .isInstanceOf(CimdFetcher.FetchException.class).hasMessageContaining("larger than 8192");
  }

  @Test
  void refusesChunkedBodiesOverTheCap() throws Exception {
    String chunk = "x".repeat(5000);
    int port = serve(out -> write(out, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
        + Integer.toHexString(5000) + "\r\n" + chunk + "\r\n" + Integer.toHexString(5000) + "\r\n" + chunk
        + "\r\n0\r\n\r\n"));
    assertThatThrownBy(() -> local(Duration.ofSeconds(3), 8192).fetch(url(port)))
        .isInstanceOf(CimdFetcher.FetchException.class).hasMessageContaining("larger than");
  }

  @Test
  void refusesUnframedBodiesOverTheCap() throws Exception {
    int port = serve(out -> write(out, "HTTP/1.1 200 OK\r\n\r\n" + "y".repeat(10_000)));
    assertThatThrownBy(() -> local(Duration.ofSeconds(3), 8192).fetch(url(port)))
        .isInstanceOf(CimdFetcher.FetchException.class).hasMessageContaining("larger than");
  }

  @Test
  void neverFollowsRedirects() throws Exception {
    int port = serve(out -> write(out, "HTTP/1.1 302 Found\r\nLocation: http://169.254.169.254/latest\r\n"
        + "Content-Length: 0\r\n\r\n"));
    assertThatThrownBy(() -> local(Duration.ofSeconds(3), 8192).fetch(url(port)))
        .isInstanceOf(CimdFetcher.FetchException.class).hasMessageContaining("redirects");
  }

  @Test
  void refusesOtherStatusesAndCompressedBodies() throws Exception {
    int port = serve(out -> write(out, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"));
    assertThatThrownBy(() -> local(Duration.ofSeconds(3), 8192).fetch(url(port)))
        .hasMessageContaining("HTTP 404");
    stop();
    int gz = serve(out -> write(out, "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 2\r\n\r\n{}"));
    assertThatThrownBy(() -> local(Duration.ofSeconds(3), 8192).fetch(url(gz)))
        .hasMessageContaining("content encoding");
  }

  @Test
  void oneDeadlineCoversASlowDrip() throws Exception {
    int port = serve(out -> {
      write(out, "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n");
      for (int i = 0; i < 100; i++) {
        write(out, "x");
        try {
          Thread.sleep(50);
        } catch (InterruptedException e) {
          return;
        }
      }
    });
    long start = System.nanoTime();
    assertThatThrownBy(() -> local(Duration.ofMillis(500), 8192).fetch(url(port)))
        .isInstanceOf(CimdFetcher.FetchException.class).hasMessageContaining("timed out");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
  }

  @Test
  void aSilentServerTimesOut() throws Exception {
    int port = serve(out -> {
      try {
        Thread.sleep(5000);
      } catch (InterruptedException ignored) {
        // stop
      }
    });
    long start = System.nanoTime();
    assertThatThrownBy(() -> local(Duration.ofMillis(300), 8192).fetch(url(port)))
        .isInstanceOf(CimdFetcher.FetchException.class).hasMessageContaining("timed out");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void privateAddressesAreRefusedBeforeAnyConnection() throws Exception {
    AtomicInteger connects = new AtomicInteger();
    SafeHttpsFetcher.Connector counting = (a, p, h, t) -> {
      connects.incrementAndGet();
      throw new IOException("must not connect");
    };
    for (String address : List.of("127.0.0.1", "10.1.2.3", "172.20.0.5", "192.168.1.1", "169.254.169.254",
        "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:10.0.0.1", "::ffff:127.0.0.1")) {
      InetAddress resolved = InetAddress.getByName(address);
      SafeHttpsFetcher f = new SafeHttpsFetcher(Duration.ofSeconds(1), 8192, AddressPolicy.PUBLIC_ONLY,
          host -> new InetAddress[] {resolved}, counting, "https");
      assertThatThrownBy(() -> f.fetch(URI.create("https://client.example/meta.json"))).as(address)
          .isInstanceOf(CimdFetcher.FetchException.class).hasMessageContaining("non-public");
    }
    // one private address among public ones is enough to refuse (no "pick the public one")
    SafeHttpsFetcher mixed = new SafeHttpsFetcher(Duration.ofSeconds(1), 8192, AddressPolicy.PUBLIC_ONLY,
        host -> new InetAddress[] {InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.1")},
        counting, "https");
    assertThatThrownBy(() -> mixed.fetch(URI.create("https://client.example/meta.json")))
        .hasMessageContaining("non-public");
    assertThat(connects.get()).isZero();
  }

  @Test
  void resolvesOnceAndConnectsToTheValidatedAddress() throws Exception {
    AtomicInteger resolutions = new AtomicInteger();
    AtomicReference<InetAddress> connectedTo = new AtomicReference<>();
    List<String> sniHosts = new ArrayList<>();
    InetAddress good = InetAddress.getByName("93.184.216.34");
    InetAddress rebound = InetAddress.getByName("127.0.0.1");
    SafeHttpsFetcher f = new SafeHttpsFetcher(Duration.ofSeconds(1), 8192, AddressPolicy.PUBLIC_ONLY, host -> {
      // a rebinding DNS server would answer a second query with a private address
      return new InetAddress[] {resolutions.getAndIncrement() == 0 ? good : rebound};
    }, (address, port, host, timeout) -> {
      connectedTo.set(address);
      sniHosts.add(host);
      throw new IOException("stop here");
    }, "https");
    assertThatThrownBy(() -> f.fetch(URI.create("https://client.example/meta.json")))
        .isInstanceOf(CimdFetcher.FetchException.class);
    assertThat(resolutions.get()).isEqualTo(1);
    assertThat(connectedTo.get()).isEqualTo(good);
    assertThat(sniHosts).containsExactly("client.example");
  }

  @Test
  void refusesOtherSchemes() {
    SafeHttpsFetcher f = new SafeHttpsFetcher(Duration.ofSeconds(1), 8192);
    assertThatThrownBy(() -> f.fetch(URI.create("http://client.example/meta.json")))
        .hasMessageContaining("https");
  }
}
