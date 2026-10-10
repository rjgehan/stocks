package com.stocks.mcp;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.web.Text;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.security.RequestMetas;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Captures the {@code clientInfo} of MCP {@code initialize} requests (§20.1 "MCP client name"). The transport's
 * {@code contextExtractor} runs before the transport reads the JSON-RPC body, and STATELESS mode has no session, so
 * this servlet filter (after Spring Security, so the caller is known) reads a {@code POST} body to the MCP endpoint
 * once, up to {@value #MAX_BODY} bytes, and hands the transport a request that replays it unchanged. For an
 * authenticated {@code initialize} it
 * <ul>
 *   <li>remembers {@code clientInfo.name/version} for the caller's API key ({@link McpClientNames}, 24 h), so later
 *       {@code tools/call} requests without {@code X-Stocks-Client} are attributed to that client in the audit log;</li>
 *   <li>writes an {@code AUTH}/{@code MCP_INITIALIZE} audit row with the {@code clientInfo} and protocol version.</li>
 * </ul>
 * A larger body is streamed through untouched (prefix + rest) and not inspected; a body that is not JSON or not an
 * {@code initialize} is passed on as is. Inspection never fails the request.
 */
@Component
public class McpInitializeCapture extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(McpInitializeCapture.class);

  /** Largest body inspected; {@code initialize} requests are a few hundred bytes. */
  static final int MAX_BODY = 64 * 1024;
  private static final byte[] INITIALIZE = "\"initialize\"".getBytes(StandardCharsets.UTF_8);
  private static final int MAX_FIELD = 200;

  private final McpClientNames clientNames;
  private final AuditService audit;
  private final JsonMapper jsonMapper;
  private final String endpoint;

  public McpInitializeCapture(McpClientNames clientNames, AuditService audit, JsonMapper jsonMapper,
                              @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String endpoint) {
    this.clientNames = clientNames;
    this.audit = audit;
    this.jsonMapper = jsonMapper;
    this.endpoint = endpoint;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!"POST".equalsIgnoreCase(request.getMethod())) {
      return true;
    }
    String path = request.getRequestURI();
    String context = request.getContextPath();
    if (path != null && context != null && !context.isEmpty() && path.startsWith(context)) {
      path = path.substring(context.length());
    }
    return !endpoint.equals(path);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    PlayerPrincipal principal = CurrentPlayer.current().orElse(null);
    if (principal == null) {
      chain.doFilter(request, response);   // the security chain answers unauthenticated callers itself
      return;
    }
    InputStream in = request.getInputStream();
    byte[] head = in.readNBytes(MAX_BODY + 1);
    if (head.length > MAX_BODY) {
      chain.doFilter(new Replay(request, new SequenceInputStream(new ByteArrayInputStream(head), in)), response);
      return;
    }
    inspect(request, principal, head);
    chain.doFilter(new Replay(request, new ByteArrayInputStream(head)), response);
  }

  /** Remembers and audits the {@code clientInfo} of an {@code initialize}; never throws. */
  void inspect(HttpServletRequest request, PlayerPrincipal principal, byte[] body) {
    if (!contains(body, INITIALIZE)) {
      return;
    }
    try {
      JsonNode root = jsonMapper.readTree(body);
      if (root == null || !root.isObject() || !"initialize".equals(text(root.get("method")))) {
        return;
      }
      JsonNode params = root.get("params");
      JsonNode clientInfo = params == null ? null : params.get("clientInfo");
      String name = clientInfo == null ? null : text(clientInfo.get("name"));
      String version = clientInfo == null ? null : text(clientInfo.get("version"));
      String client = McpClientNames.clientName(name, version);
      clientNames.rememberInitialize(principal, client);

      Map<String, Object> info = new LinkedHashMap<>();
      info.put("name", Text.clip(name, MAX_FIELD));
      info.put("version", Text.clip(version, MAX_FIELD));
      if (clientInfo != null && clientInfo.get("title") != null) {
        info.put("title", Text.clip(text(clientInfo.get("title")), MAX_FIELD));
      }
      RequestMeta base = RequestMetas.of(request, principal);
      RequestMeta meta = new RequestMeta(base.requestId(), base.channel(), base.ip(), base.userAgent(),
          clientNames.resolve(request, principal), null);
      audit.record(AuditEntry.of(AuditCategory.AUTH, "MCP_INITIALIZE", AuditOutcome.OK)
          .withActor(principal)
          .withMeta(meta)
          .withDetail("clientInfo", info)
          .withDetail("protocolVersion", params == null ? null
              : Text.clip(text(params.get("protocolVersion")), MAX_FIELD)));
    } catch (RuntimeException e) {
      log.debug("Could not inspect an MCP initialize body: {}", e.toString());
    }
  }

  private static String text(JsonNode node) {
    return node == null || !node.isString() ? null : node.asString();
  }

  static boolean contains(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  /** The original request with its body replaced by {@code body} (the bytes already read, then the rest). */
  static final class Replay extends HttpServletRequestWrapper {

    private final ServletInputStream stream;

    Replay(HttpServletRequest request, InputStream body) {
      super(request);
      this.stream = new ServletInputStream() {
        private boolean finished;

        @Override
        public int read() throws IOException {
          int b = body.read();
          finished = b < 0;
          return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
          int n = body.read(buffer, off, len);
          finished = n < 0;
          return n;
        }

        @Override
        public boolean isFinished() {
          return finished;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("The MCP request body is read synchronously");
        }
      };
    }

    @Override
    public ServletInputStream getInputStream() {
      return stream;
    }

    @Override
    public BufferedReader getReader() {
      String encoding = getCharacterEncoding();
      Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
      return new BufferedReader(new InputStreamReader(stream, charset));
    }
  }
}
