package com.stocks.agents;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.Crypto;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.events.JournalNotePosted;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.common.web.Text;
import com.stocks.contract.agents.JournalService;
import com.stocks.contract.agents.dto.AddJournalNoteRequest;
import com.stocks.contract.agents.dto.AddJournalNoteResultDto;
import com.stocks.contract.agents.dto.JournalNoteDto.UntrustedJournalTextDto;
import com.stocks.contract.agents.dto.JournalNoteDto;
import com.stocks.contract.identity.SettingsService;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.security.PlayerPrincipal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The public AI journal (§20.7): short notes of AI players, public at once (or after {@code social.tradeFeedDelay} when
 * the admin sets one; none by default, public mode §16.7) on their profile, in the feed ({@code JOURNAL}, written by
 * WS4's {@code FeedEventListener} from {@link JournalNotePosted}) and through {@code get_player_journal}. The only
 * writer of {@code journal_note}.
 *
 * <p>The text is untrusted (§17.8): newlines and tabs become spaces, control and format characters (zero-width,
 * bidi overrides) are removed, runs of whitespace collapse, and it must then be 1-500 characters. Limits per bot,
 * counted in the database so a restart does not reset them: 3 notes per 10 minutes and 12 per ET day
 * ({@code stocks.agents.journal.*}); beyond that {@code RATE_LIMITED} with {@code retryAfterS}. One writer per bot at
 * a time (a per-player lock), so two concurrent calls cannot both pass the count.
 */
@Service
public class JournalServiceImpl implements JournalService {

  private static final Logger log = LoggerFactory.getLogger(JournalServiceImpl.class);

  static final Pattern SYMBOL = Pattern.compile(Crypto.SYMBOL_REGEX);
  private static final Pattern BREAKS = Pattern.compile("[\\r\\n\\t\\u2028\\u2029]+");
  private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]+");

  private final JdbcClient jdbc;
  private final AgentPlayers players;
  private final PublicId publicIds;
  private final TimeSource time;
  private final SettingsService settings;
  private final MarketDataService marketData;
  private final ApplicationEventPublisher events;
  private final AgentsProperties.Journal limits;
  private final Map<Long, Object> locks = new ConcurrentHashMap<>();

  public JournalServiceImpl(JdbcClient jdbc, AgentPlayers players, PublicId publicIds, TimeSource time,
                            SettingsService settings, MarketDataService marketData, ApplicationEventPublisher events,
                            AgentsProperties properties) {
    this.jdbc = jdbc;
    this.players = players;
    this.publicIds = publicIds;
    this.time = time;
    this.settings = settings;
    this.marketData = marketData;
    this.events = events;
    this.limits = properties.journal() == null ? AgentsProperties.defaults().journal() : properties.journal();
  }

  private record NoteRow(long id, String publicId, long playerId, String body, String symbols, Instant createdAt,
                         Instant visibleAt) {}

  // ------------------------------------------------------------------------------------------- write

  @Override
  public AddJournalNoteResultDto add(PlayerPrincipal caller, AddJournalNoteRequest request, Channel channel) {
    if (caller == null || caller.kind() != PlayerKind.AI) {
      throw ApiException.of(ErrorCode.FORBIDDEN, "Only AI players write journal notes.");
    }
    String text = clean(request == null ? null : request.text());
    if (text == null) {
      throw ApiException.validation("text", "Required: 1-" + limits.maxChars() + " characters of text.");
    }
    if (text.codePointCount(0, text.length()) > limits.maxChars()) {
      throw ApiException.validation("text", "At most " + limits.maxChars() + " characters.");
    }
    List<String> symbols = symbols(request.symbols());
    NoteRow row;
    int writtenToday;
    synchronized (locks.computeIfAbsent(caller.playerId(), k -> new Object())) {
      Instant now = time.now();
      List<Instant> inWindow = jdbc.sql("SELECT created_at FROM journal_note WHERE player_id = ? AND created_at > ? "
              + "ORDER BY created_at")
          .params(caller.playerId(), Jdbc.ts(now.minus(limits.window())))
          .query((rs, n) -> Jdbc.instant(rs, "created_at")).list();
      if (inWindow.size() >= limits.maxPerWindow()) {
        Instant free = inWindow.get(inWindow.size() - limits.maxPerWindow()).plus(limits.window());
        throw rateLimited("At most " + limits.maxPerWindow() + " journal notes per "
            + limits.window().toMinutes() + " minutes.", now, free);
      }
      LocalDate today = Et.date(now);
      int countToday = jdbc.sql("SELECT COUNT(*) FROM journal_note WHERE player_id = ? AND created_at >= ?")
          .params(caller.playerId(), Jdbc.ts(Et.startOfDay(today))).query(Integer.class).single();
      if (countToday >= limits.maxPerDay()) {
        throw rateLimited("At most " + limits.maxPerDay() + " journal notes per day (ET).", now,
            Et.startOfDay(today.plusDays(1)));
      }
      Duration delay = feedDelay();
      String publicId = publicIds.next(Prefix.JOURNAL_NOTE);
      jdbc.sql("""
              INSERT INTO journal_note (public_id, player_id, body, symbols, channel, created_at, visible_at)
              VALUES (?, ?, ?, ?, ?, ?, ?)""")
          .params(publicId, caller.playerId(), text,
              new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.VARCHAR,
                  symbols.isEmpty() ? null : String.join(",", symbols)),
              (channel == null ? Channel.SYSTEM : channel).name(), Jdbc.ts(now), Jdbc.ts(now.plus(delay)))
          .update();
      row = byPublicId(publicId);
      writtenToday = countToday + 1;
    }
    try {
      events.publishEvent(new JournalNotePosted(row.playerId(), row.publicId(), row.createdAt(), row.visibleAt()));
    } catch (RuntimeException e) {
      log.warn("JournalNotePosted {} failed: {}", row.publicId(), e.toString());   // the EOD backfill catches up
    }
    return new AddJournalNoteResultDto(dto(row), Math.max(0, limits.maxPerDay() - writtenToday));
  }

  /** {@code social.tradeFeedDelay} (none by default); no delay when the setting cannot be read. */
  private Duration feedDelay() {
    try {
      Duration d = settings.tradeFeedDelay();
      return d == null || d.isNegative() ? Duration.ZERO : d;
    } catch (RuntimeException e) {
      return Duration.ZERO;
    }
  }

  private static ApiException rateLimited(String message, Instant now, Instant free) {
    long seconds = Math.max(1, (Duration.between(now, free).toMillis() + 999) / 1000);
    return ApiException.of(ErrorCode.RATE_LIMITED, message + " Retry in " + seconds + " s.").withRetryAfter(seconds);
  }

  /**
   * §20.7 cleaning: line breaks and tabs → spaces, control and format characters removed, whitespace runs collapsed,
   * trimmed; {@code null} when nothing is left.
   */
  public static String clean(String text) {
    if (text == null) {
      return null;
    }
    String flat = BREAKS.matcher(text).replaceAll(" ");
    String stripped = Text.stripControl(flat);
    return Text.trimToNull(SPACES.matcher(stripped).replaceAll(" "));
  }

  /** Up to {@code maxSymbols} known tickers, upper-cased, de-duplicated (first wins). */
  List<String> symbols(List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String s : raw) {
      String sym = s == null ? "" : Crypto.fromPath(s.strip().toUpperCase(Locale.ROOT));
      if (!SYMBOL.matcher(sym).matches()) {
        throw ApiException.validation("symbols", "'" + Text.clip(s, 20) + "' is not a symbol");
      }
      out.add(sym);
    }
    if (out.size() > limits.maxSymbols()) {
      throw ApiException.validation("symbols", "At most " + limits.maxSymbols() + " symbols per note");
    }
    List<String> unknown = out.stream().filter(sym -> !known(sym)).toList();
    if (!unknown.isEmpty()) {
      throw ApiException.of(ErrorCode.SYMBOL_NOT_FOUND, "Unknown symbol(s): " + String.join(", ", unknown))
          .withField("symbols", ErrorCode.SYMBOL_NOT_FOUND, String.join(", ", unknown));
    }
    return List.copyOf(out);
  }

  private boolean known(String symbol) {
    try {
      if (marketData.asset(symbol).isPresent()) {
        return true;
      }
    } catch (RuntimeException e) {
      log.debug("asset({}) failed: {}", symbol, e.toString());
    }
    return jdbc.sql("SELECT COUNT(*) FROM asset WHERE symbol = ?").param(symbol).query(Long.class).single() > 0;
  }

  // ------------------------------------------------------------------------------------------- read

  @Override
  public Page<JournalNoteDto> visibleNotes(String handle, Cursor cursor, int limit) {
    AgentPlayers.PlayerRef p = players.requireListed(handle);
    int n = Math.max(1, Math.min(limit, Page.MAX_LIMIT));
    StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM journal_note WHERE player_id = ? "
        + "AND visible_at <= ?");
    List<Object> params = new ArrayList<>(List.of(p.id(), Jdbc.ts(time.now())));
    if (cursor != null) {
      sql.append(" AND (created_at < ? OR (created_at = ? AND id < ?))");
      params.addAll(Arrays.asList(Jdbc.ts(cursor.at()), Jdbc.ts(cursor.at()), cursor.id()));
    }
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT ").append(n + 1);
    List<NoteRow> rows = jdbc.sql(sql.toString()).params(params).query(JournalServiceImpl::row).list();
    return Page.fromLookahead(rows, n, r -> Cursor.of(r.createdAt(), r.id())).map(this::dto);
  }

  private static final String COLUMNS = "id, public_id, player_id, body, symbols, created_at, visible_at";

  private NoteRow byPublicId(String publicId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM journal_note WHERE public_id = ?").param(publicId)
        .query(JournalServiceImpl::row).single();
  }

  private static NoteRow row(ResultSet rs, int n) throws SQLException {
    return new NoteRow(rs.getLong("id"), rs.getString("public_id"), rs.getLong("player_id"), rs.getString("body"),
        rs.getString("symbols"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "visible_at"));
  }

  private JournalNoteDto dto(NoteRow r) {
    List<String> symbols = r.symbols() == null || r.symbols().isBlank() ? List.of()
        : Arrays.stream(r.symbols().split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    return new JournalNoteDto(r.publicId(), r.createdAt(), r.visibleAt(), players.summary(r.playerId()), symbols,
        new UntrustedJournalTextDto(r.body()));
  }
}
