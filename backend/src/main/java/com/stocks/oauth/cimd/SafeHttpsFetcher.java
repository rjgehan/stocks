package com.stocks.oauth.cimd;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * The SSRF-safe CIMD fetch of §17.9.2. The host is resolved <b>once</b> and every address must pass the
 * {@link AddressPolicy} (public unicast only); the socket then connects to that validated address, so a second DNS
 * answer (DNS rebinding) can never redirect the request. TLS uses SNI and HTTPS hostname verification for the
 * original host. One HTTP/1.1 {@code GET} with {@code Connection: close}, no redirects (any non-200 is an error), no
 * content encoding, one overall deadline for resolution, connect, handshake and every read, and a body cap (chunked
 * or {@code Content-Length}). Nothing is cached here; the caller caches valid documents.
 */
public final class SafeHttpsFetcher implements CimdFetcher {

  /** Resolves a host name (production: {@link InetAddress#getAllByName}). */
  @FunctionalInterface
  public interface Resolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  /** Opens the connection to an already validated address (production: TLS with SNI for {@code host}). */
  @FunctionalInterface
  public interface Connector {
    Socket connect(InetAddress address, int port, String host, int timeoutMillis) throws IOException;
  }

  /** TLS to {@code address}, verifying the certificate against {@code host} (not the address). */
  public static final Connector TLS = (address, port, host, timeoutMillis) -> {
    Socket raw = new Socket();
    try {
      raw.connect(new InetSocketAddress(address, port), timeoutMillis);
      raw.setSoTimeout(timeoutMillis);
      SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
      SSLParameters params = tls.getSSLParameters();
      params.setEndpointIdentificationAlgorithm("HTTPS");
      params.setServerNames(List.of(new SNIHostName(host)));
      tls.setSSLParameters(params);
      tls.startHandshake();
      return tls;
    } catch (IOException | RuntimeException e) {
      raw.close();
      throw e;
    }
  };

  private final Duration timeout;
  private final int maxBytes;
  private final AddressPolicy policy;
  private final Resolver resolver;
  private final Connector connector;
  private final String scheme;

  /** Production: https, public addresses only, TLS. */
  public SafeHttpsFetcher(Duration timeout, int maxBytes) {
    this(timeout, maxBytes, AddressPolicy.PUBLIC_ONLY, InetAddress::getAllByName, TLS, "https");
  }

  /** Tests: a plain-socket {@code connector} and a policy that admits the loopback test server. */
  public SafeHttpsFetcher(Duration timeout, int maxBytes, AddressPolicy policy, Resolver resolver,
                          Connector connector, String scheme) {
    this.timeout = timeout;
    this.maxBytes = maxBytes;
    this.policy = policy;
    this.resolver = resolver;
    this.connector = connector;
    this.scheme = scheme;
  }

  @Override
  public Response fetch(URI url) throws FetchException {
    if (url == null || !scheme.equalsIgnoreCase(url.getScheme()) || url.getHost() == null) {
      throw new FetchException("The client_id URL must use " + scheme + ".");
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    String host = url.getHost();
    int port = url.getPort() == -1 ? 443 : url.getPort();
    InetAddress[] addresses = resolve(host, deadline);
    if (addresses.length == 0) {
      throw new FetchException("The client_id host does not resolve.");
    }
    for (InetAddress a : addresses) {
      if (!policy.isAllowed(a)) {
        throw new FetchException("The client_id host resolves to a non-public address.");
      }
    }
    try (Socket socket = connect(addresses, port, host, deadline)) {
      OutputStream out = socket.getOutputStream();
      String target = (url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath())
          + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery());
      String hostHeader = port == 443 ? host : host + ":" + port;
      String request = "GET " + target + " HTTP/1.1\r\n"
          + "Host: " + hostHeader + "\r\n"
          + "Accept: application/json\r\n"
          + "Accept-Encoding: identity\r\n"
          + "User-Agent: stocks-oauth/1 (client-id-metadata-document)\r\n"
          + "Connection: close\r\n\r\n";
      out.write(request.getBytes(StandardCharsets.US_ASCII));
      out.flush();
      return HttpResponseReader.read(new DeadlineInputStream(socket, deadline), maxBytes);
    } catch (SocketTimeoutException e) {
      throw new FetchException("Fetching the client metadata document timed out.", e);
    } catch (IOException e) {
      throw new FetchException("The client metadata document could not be fetched.", e);
    }
  }

  /**
   * Connects to the first validated address that accepts the connection (all of them passed the policy), within the
   * one deadline: a host with an IPv6 address first still works from a container without an IPv6 route.
   */
  private Socket connect(InetAddress[] addresses, int port, String host, long deadline) throws IOException {
    IOException last = null;
    for (InetAddress address : addresses) {
      try {
        return connector.connect(address, port, host, remainingMillis(deadline));
      } catch (SocketTimeoutException e) {
        throw e;
      } catch (IOException e) {
        last = e;
      }
    }
    throw last;
  }

  private InetAddress[] resolve(String host, long deadline) throws FetchException {
    CompletableFuture<InetAddress[]> result = new CompletableFuture<>();
    Thread.ofVirtual().name("cimd-dns").start(() -> {
      try {
        result.complete(resolver.resolve(host));
      } catch (Throwable t) {
        result.completeExceptionally(t);
      }
    });
    try {
      return result.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      throw new FetchException("Resolving the client_id host timed out.", e);
    } catch (ExecutionException e) {
      throw new FetchException("The client_id host does not resolve.", e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new FetchException("Interrupted.", e);
    }
  }

  static int remainingMillis(long deadline) throws SocketTimeoutException {
    long nanos = deadline - System.nanoTime();
    if (nanos <= 0) {
      throw new SocketTimeoutException("deadline passed");
    }
    return (int) Math.max(1, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(nanos)));
  }

  /** Re-arms the socket timeout before every read so the whole exchange honours one deadline. */
  static final class DeadlineInputStream extends InputStream {
    private final Socket socket;
    private final InputStream in;
    private final long deadline;

    DeadlineInputStream(Socket socket, long deadline) throws IOException {
      this.socket = socket;
      this.in = socket.getInputStream();
      this.deadline = deadline;
    }

    @Override
    public int read() throws IOException {
      socket.setSoTimeout(remainingMillis(deadline));
      return in.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      socket.setSoTimeout(remainingMillis(deadline));
      return in.read(b, off, len);
    }
  }

  /** Lower-cased header name, for {@link HttpResponseReader}. */
  static String lower(String s) {
    return s.toLowerCase(Locale.ROOT);
  }
}
