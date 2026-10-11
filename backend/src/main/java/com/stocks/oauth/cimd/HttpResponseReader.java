package com.stocks.oauth.cimd;

import com.stocks.oauth.cimd.CimdFetcher.FetchException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A minimal HTTP/1.1 response parser for {@link SafeHttpsFetcher} (one {@code GET} with {@code Connection: close}):
 * the status line, the headers (each line ≤ 8 KiB, at most 100), and a body of at most {@code maxBytes}, framed by
 * {@code Content-Length}, {@code Transfer-Encoding: chunked}, or the end of the connection. Only status 200 is
 * accepted (a 3xx is an error: redirects are never followed), and only an identity content encoding.
 */
final class HttpResponseReader {

  private static final int MAX_LINE = 8192;
  private static final int MAX_HEADERS = 100;

  private HttpResponseReader() {}

  static CimdFetcher.Response read(InputStream in, int maxBytes) throws IOException, FetchException {
    String statusLine = line(in);
    if (statusLine == null || !statusLine.startsWith("HTTP/1.")) {
      throw new FetchException("The client metadata server sent no HTTP response.");
    }
    String[] parts = statusLine.split(" ", 3);
    int status;
    try {
      status = Integer.parseInt(parts.length > 1 ? parts[1] : "");
    } catch (NumberFormatException e) {
      throw new FetchException("The client metadata server sent a malformed status line.");
    }
    Map<String, String> headers = new LinkedHashMap<>();
    for (int i = 0; ; i++) {
      if (i > MAX_HEADERS) {
        throw new FetchException("The client metadata response has too many headers.");
      }
      String header = line(in);
      if (header == null) {
        throw new FetchException("The client metadata response ended inside its headers.");
      }
      if (header.isEmpty()) {
        break;
      }
      int colon = header.indexOf(':');
      if (colon <= 0) {
        throw new FetchException("The client metadata response has a malformed header.");
      }
      String name = SafeHttpsFetcher.lower(header.substring(0, colon).strip());
      String value = header.substring(colon + 1).strip();
      headers.merge(name, value, (a, b) -> a + ", " + b);
    }
    if (status >= 300 && status < 400) {
      throw new FetchException("The client_id URL redirects (HTTP " + status + "); redirects are not followed.");
    }
    if (status != 200) {
      throw new FetchException("The client metadata document answered HTTP " + status + ".");
    }
    String encoding = headers.get("content-encoding");
    if (encoding != null && !encoding.equalsIgnoreCase("identity")) {
      throw new FetchException("The client metadata document uses an unsupported content encoding.");
    }
    byte[] body;
    String transfer = headers.get("transfer-encoding");
    if (transfer != null && SafeHttpsFetcher.lower(transfer).contains("chunked")) {
      body = chunked(in, maxBytes);
    } else if (headers.containsKey("content-length")) {
      long length;
      try {
        length = Long.parseLong(headers.get("content-length").strip());
      } catch (NumberFormatException e) {
        throw new FetchException("The client metadata response has a malformed Content-Length.");
      }
      if (length < 0 || length > maxBytes) {
        throw new FetchException("The client metadata document is larger than " + maxBytes + " bytes.");
      }
      body = exactly(in, (int) length);
    } else {
      body = untilClose(in, maxBytes);
    }
    return new CimdFetcher.Response(body, headers.get("cache-control"));
  }

  /** One CRLF- (or LF-) terminated ASCII line without its terminator; null at the end of the stream. */
  static String line(InputStream in) throws IOException, FetchException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1) {
      if (b == '\n') {
        byte[] bytes = out.toByteArray();
        int len = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
        return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
      }
      if (out.size() >= MAX_LINE) {
        throw new FetchException("The client metadata response has an over-long header line.");
      }
      out.write(b);
    }
    return out.size() == 0 ? null : out.toString(StandardCharsets.ISO_8859_1);
  }

  private static byte[] exactly(InputStream in, int length) throws IOException, FetchException {
    byte[] body = in.readNBytes(length);
    if (body.length != length) {
      throw new FetchException("The client metadata response ended early.");
    }
    return body;
  }

  private static byte[] untilClose(InputStream in, int maxBytes) throws IOException, FetchException {
    byte[] body = in.readNBytes(maxBytes + 1);
    if (body.length > maxBytes) {
      throw new FetchException("The client metadata document is larger than " + maxBytes + " bytes.");
    }
    return body;
  }

  private static byte[] chunked(InputStream in, int maxBytes) throws IOException, FetchException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    while (true) {
      String sizeLine = line(in);
      if (sizeLine == null) {
        throw new FetchException("The client metadata response ended inside a chunk.");
      }
      int semi = sizeLine.indexOf(';');
      String hex = (semi < 0 ? sizeLine : sizeLine.substring(0, semi)).strip();
      long size;
      try {
        size = Long.parseLong(hex, 16);
      } catch (NumberFormatException e) {
        throw new FetchException("The client metadata response has a malformed chunk.");
      }
      if (size < 0 || out.size() + size > maxBytes) {
        throw new FetchException("The client metadata document is larger than " + maxBytes + " bytes.");
      }
      if (size == 0) {
        // trailers until the empty line (or the end of the stream)
        for (int i = 0; i <= MAX_HEADERS; i++) {
          String trailer = line(in);
          if (trailer == null || trailer.isEmpty()) {
            return out.toByteArray();
          }
        }
        throw new FetchException("The client metadata response has too many trailers.");
      }
      out.write(exactly(in, (int) size));
      String crlf = line(in);
      if (crlf == null || !crlf.isEmpty()) {
        throw new FetchException("The client metadata response has a malformed chunk.");
      }
    }
  }
}
