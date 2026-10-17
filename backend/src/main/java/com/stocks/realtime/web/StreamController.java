package com.stocks.realtime.web;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.Crypto;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.realtime.dto.StreamByeDto;
import com.stocks.contract.realtime.dto.StreamTopic;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.realtime.stream.StreamConnection;
import com.stocks.realtime.stream.StreamHub;
import com.stocks.realtime.stream.StreamSink;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * {@code GET /api/v1/stream} (§19.3): live quotes, orders, account, session, leaderboard and feed as Server-Sent
 * Events, for API keys (a browser session has {@code /ws}: 403 {@code FORBIDDEN}). Each topic needs the scope REST
 * needs for the same data ({@code FORBIDDEN_SCOPE}). The response is unbuffered and uncacheable for proxies such as
 * Cloudflare: {@code text/event-stream}, {@code Cache-Control: no-store, no-transform}, {@code X-Accel-Buffering: no}.
 */
@RestController
@Tag(name = "stream", description = "Server-Sent Events for API clients (Phase 2)")
public class StreamController {

  public static final MediaType EVENT_STREAM = new MediaType("text", "event-stream", StandardCharsets.UTF_8);
  static final Pattern SYMBOL = Pattern.compile(Crypto.SYMBOL_REGEX);
  static final long ASYNC_SLACK_MS = 60_000;

  private final StreamHub hub;

  public StreamController(StreamHub hub) {
    this.hub = hub;
  }

  @GetMapping("/api/v1/stream")
  @Operation(summary = "Live events (Server-Sent Events)",
      description = "API keys only. topics: a comma list of quotes, session, orders, account, leaderboard, feed "
          + "(default: every topic your scopes allow; quotes only with symbols). symbols: up to 50 tickers for "
          + "quotes. Resume with the Last-Event-ID header (or lastEventId): order, notification and feed events "
          + "carry ids and are replayed from a 5-minute buffer. Control events: hello, reset, bye; a ': hb' "
          + "comment every 15 s. At most 3 streams per player; each lasts at most 30 minutes.",
      responses = @ApiResponse(responseCode = "200", description = "The event stream",
          content = @Content(mediaType = "text/event-stream", schema = @Schema(type = "string"))))
  public ResponseEntity<ResponseBodyEmitter> stream(
      @Parameter(description = "Comma list: quotes, session, orders, account, leaderboard, feed")
      @RequestParam(required = false) String topics,
      @Parameter(description = "Comma list of tickers (at most 50) for the quotes topic")
      @RequestParam(required = false) String symbols,
      @Parameter(description = "Resume after this event id (for clients that cannot send Last-Event-ID)")
      @RequestParam(required = false) String lastEventId,
      @Parameter(description = "Resume after this event id (standard SSE header)")
      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventIdHeader) {
    if (!hub.properties().enabled()) {
      throw ApiException.of(ErrorCode.NOT_FOUND, "The event stream is turned off on this server.");
    }
    PlayerPrincipal caller = CurrentPlayer.require();
    if (caller.authMethod() == AuthMethod.SESSION) {
      throw ApiException.of(ErrorCode.FORBIDDEN, "The event stream is for API keys; the web app uses /ws.");
    }
    List<String> symbolList = symbols(symbols, hub.properties().maxSymbols());
    Set<StreamTopic> topicSet = topics(topics, !symbolList.isEmpty(), caller);
    if (topicSet.contains(StreamTopic.QUOTES) && symbolList.isEmpty()) {
      throw ApiException.validation("symbols", "The quotes topic needs symbols, e.g. symbols=AAPL,MSFT.");
    }
    ResponseBodyEmitter emitter = new ResponseBodyEmitter(hub.properties().maxAge().toMillis() + ASYNC_SLACK_MS);
    EmitterSink sink = new EmitterSink(emitter);
    AtomicReference<StreamConnection> connection = new AtomicReference<>();
    Runnable end = () -> {
      sink.gone();                                    // the container is done with the response: write nothing more
      StreamConnection c = connection.get();
      if (c != null) {
        c.close(StreamByeDto.Reason.SHUTDOWN);       // the client went away or the async request timed out
      }
    };
    emitter.onCompletion(end);
    emitter.onTimeout(end);
    emitter.onError(e -> end.run());
    String resumeFrom = lastEventIdHeader != null && !lastEventIdHeader.isBlank() ? lastEventIdHeader : lastEventId;
    connection.set(hub.open(caller, topicSet, symbolList, resumeFrom, sink));
    return ResponseEntity.ok()
        .contentType(EVENT_STREAM)
        .header(HttpHeaders.CACHE_CONTROL, "no-store, no-transform")
        .header("X-Accel-Buffering", "no")
        .body(emitter);
  }

  /**
   * The requested topics, each covered by the caller's scopes ({@code FORBIDDEN_SCOPE} names the missing one);
   * without {@code raw}, every topic the scopes allow ({@code quotes} only with symbols).
   */
  static Set<StreamTopic> topics(String raw, boolean haveSymbols, PlayerPrincipal caller) {
    Set<StreamTopic> out = EnumSet.noneOf(StreamTopic.class);
    if (raw == null || raw.isBlank()) {
      for (StreamTopic t : StreamTopic.values()) {
        if (caller.hasScope(t.scope()) && (t != StreamTopic.QUOTES || haveSymbols)) {
          out.add(t);
        }
      }
      if (out.isEmpty()) {
        throw ApiException.of(ErrorCode.FORBIDDEN_SCOPE,
            "This API key has no read scope (market:read, account:read or league:read) for any stream topic.");
      }
      return out;
    }
    for (String part : raw.split(",")) {
      if (part.isBlank()) {
        continue;
      }
      StreamTopic t = StreamTopic.parse(part);
      if (t == null) {
        throw ApiException.validation("topics", "Unknown topic '" + clip(part.strip()) + "'; use quotes, session, "
            + "orders, account, leaderboard or feed.");
      }
      if (!caller.hasScope(t.scope())) {
        throw ApiException.of(ErrorCode.FORBIDDEN_SCOPE, "This API key lacks the '" + t.scope() + "' scope that the "
            + t.name().toLowerCase(Locale.ROOT) + " topic needs.");
      }
      out.add(t);
    }
    if (out.isEmpty()) {
      throw ApiException.validation("topics", "Name at least one topic.");
    }
    return out;
  }

  /** Upper-cased, de-duplicated tickers, at most {@code max}. */
  static List<String> symbols(String raw, int max) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String part : raw.split(",")) {
      if (part.isBlank()) {
        continue;
      }
      String s = Crypto.fromPath(part.strip().toUpperCase(Locale.ROOT));
      if (!SYMBOL.matcher(s).matches()) {
        throw ApiException.validation("symbols", "'" + clip(part.strip()) + "' is not a symbol.");
      }
      out.add(s);
    }
    if (out.size() > max) {
      throw ApiException.validation("symbols", "At most " + max + " symbols per stream.");
    }
    return new ArrayList<>(out);
  }

  private static String clip(String s) {
    return s.length() <= 20 ? s : s.substring(0, 20) + "…";
  }

  /**
   * Frames to the HTTP response, flushed one by one (the emitter flushes after every send). Once the container has
   * finished the response (the client went away, an error or the async timeout), nothing is written or completed any
   * more: completing an errored response would only dispatch a second "response not usable" error.
   */
  static final class EmitterSink implements StreamSink {

    private final ResponseBodyEmitter emitter;
    private volatile boolean gone;

    EmitterSink(ResponseBodyEmitter emitter) {
      this.emitter = emitter;
    }

    /** The container finished the response; the sender then stops at its next write. */
    void gone() {
      gone = true;
    }

    @Override
    public void send(String frame) throws IOException {
      if (gone) {
        throw new IOException("The client is gone");
      }
      emitter.send(frame, EVENT_STREAM);
    }

    @Override
    public void close() {
      if (gone) {
        return;
      }
      try {
        emitter.complete();
      } catch (RuntimeException e) {
        // already completed
      }
    }
  }
}
