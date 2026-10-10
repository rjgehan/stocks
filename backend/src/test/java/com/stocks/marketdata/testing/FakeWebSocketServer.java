package com.stocks.marketdata.testing;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * A minimal RFC 6455 server for stream adapter tests (text frames, ping, close; no extensions): it completes the
 * upgrade handshake, hands every client text message to a handler, and lets the test send text frames or drop the
 * connection. It plays Alpaca's and Finnhub's protocols in the tests.
 */
public final class FakeWebSocketServer implements AutoCloseable {

  /** One accepted client connection. */
  public final class Connection {
    private final Socket socket;
    private final OutputStream out;
    private final String path;

    Connection(Socket socket, String path) throws IOException {
      this.socket = socket;
      this.out = socket.getOutputStream();
      this.path = path;
    }

    public String path() {
      return path;
    }

    public synchronized void send(String text) {
      try {
        byte[] p = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x81);
        if (p.length < 126) {
          f.write(p.length);
        } else if (p.length < 65536) {
          f.write(126);
          f.write(p.length >>> 8);
          f.write(p.length & 0xff);
        } else {
          f.write(127);
          for (int i = 7; i >= 0; i--) {
            f.write((int) ((long) p.length >>> (8 * i)) & 0xff);
          }
        }
        f.write(p);
        out.write(f.toByteArray());
        out.flush();
      } catch (IOException e) {
        // the client went away
      }
    }

    /** Drops the TCP connection without a close frame. */
    public void drop() {
      try {
        socket.close();
      } catch (IOException ignored) {
        // already closed
      }
    }

    public boolean isOpen() {
      return !socket.isClosed();
    }
  }

  private final ServerSocket server;
  private final List<Connection> connections = new CopyOnWriteArrayList<>();
  private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
  private volatile BiConsumer<Connection, String> handler = (c, m) -> { };
  private volatile java.util.function.Consumer<Connection> onOpen = c -> { };
  private final Thread acceptor;

  public FakeWebSocketServer() throws IOException {
    server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    acceptor = Thread.ofVirtual().name("fake-ws-accept").start(this::acceptLoop);
  }

  public String url(String path) {
    return "ws://127.0.0.1:" + server.getLocalPort() + path;
  }

  public FakeWebSocketServer onOpen(java.util.function.Consumer<Connection> onOpen) {
    this.onOpen = onOpen;
    return this;
  }

  public FakeWebSocketServer onMessage(BiConsumer<Connection, String> handler) {
    this.handler = handler;
    return this;
  }

  public List<Connection> connections() {
    return List.copyOf(connections);
  }

  public Connection last() {
    return connections.isEmpty() ? null : connections.get(connections.size() - 1);
  }

  /** The next client message, waiting up to {@code timeout}; null on timeout. */
  public String nextMessage(Duration timeout) throws InterruptedException {
    return received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Every message received so far (and drains them). */
  public List<String> drainMessages() {
    List<String> out = new ArrayList<>();
    received.drainTo(out);
    return out;
  }

  private void acceptLoop() {
    while (!server.isClosed()) {
      try {
        Socket s = server.accept();
        Thread.ofVirtual().start(() -> serve(s));
      } catch (IOException e) {
        return;
      }
    }
  }

  private void serve(Socket s) {
    try {
      InputStream in = s.getInputStream();
      String request = readHeaders(in);
      String key = null;
      String path = "/";
      for (String line : request.split("\r\n")) {
        if (line.startsWith("GET ")) {
          path = line.split(" ")[1];
        }
        if (line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:")) {
          key = line.substring(line.indexOf(':') + 1).trim();
        }
      }
      String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
          .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
      OutputStream out = s.getOutputStream();
      out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
          + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      out.flush();
      Connection c = new Connection(s, path);
      connections.add(c);
      onOpen.accept(c);
      DataInputStream din = new DataInputStream(in);
      ByteArrayOutputStream message = new ByteArrayOutputStream();
      while (!s.isClosed()) {
        int b0 = din.read();
        if (b0 < 0) {
          break;
        }
        int b1 = din.readUnsignedByte();
        int opcode = b0 & 0x0f;
        boolean fin = (b0 & 0x80) != 0;
        long len = b1 & 0x7f;
        if (len == 126) {
          len = din.readUnsignedShort();
        } else if (len == 127) {
          len = din.readLong();
        }
        byte[] mask = new byte[4];
        if ((b1 & 0x80) != 0) {
          din.readFully(mask);
        }
        byte[] payload = new byte[(int) len];
        din.readFully(payload);
        for (int i = 0; i < payload.length; i++) {
          payload[i] ^= mask[i % 4];
        }
        if (opcode == 0x8) {
          s.close();
          break;
        }
        if (opcode == 0x9) {
          continue;                                  // ping: ignored (the client does not need a pong here)
        }
        if (opcode == 0x1 || opcode == 0x0) {
          message.write(payload);
          if (fin) {
            String text = message.toString(StandardCharsets.UTF_8);
            message.reset();
            received.add(text);
            handler.accept(c, text);
          }
        }
      }
    } catch (Exception e) {
      // connection ended
    } finally {
      try {
        s.close();
      } catch (IOException ignored) {
        // ignore
      }
    }
  }

  private static String readHeaders(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    int matched = 0;
    int c;
    byte[] end = {'\r', '\n', '\r', '\n'};
    while ((c = in.read()) >= 0) {
      b.write(c);
      matched = c == end[matched] ? matched + 1 : (c == '\r' ? 1 : 0);
      if (matched == 4) {
        break;
      }
    }
    return b.toString(StandardCharsets.US_ASCII);
  }

  @Override
  public void close() throws IOException {
    for (Connection c : connections) {
      c.drop();
    }
    server.close();
    acceptor.interrupt();
  }
}
