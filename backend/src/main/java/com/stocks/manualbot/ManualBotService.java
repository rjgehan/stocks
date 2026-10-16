package com.stocks.manualbot;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.Channel;
import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardMetric;
import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.domain.SessionType;
import com.stocks.common.domain.Side;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.error.Warning;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.common.web.Text;
import com.stocks.contract.agents.JournalService;
import com.stocks.contract.agents.dto.AddJournalNoteRequest;
import com.stocks.contract.agents.dto.AddJournalNoteResultDto;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import com.stocks.contract.manualbot.dto.ManualActionDto;
import com.stocks.contract.manualbot.dto.ManualActionKind;
import com.stocks.contract.manualbot.dto.ManualActionResultDto;
import com.stocks.contract.manualbot.dto.ManualBotDto;
import com.stocks.contract.manualbot.dto.ManualExecuteRequest;
import com.stocks.contract.manualbot.dto.ManualIssueDto;
import com.stocks.contract.manualbot.dto.ManualIssueSeverity;
import com.stocks.contract.manualbot.dto.ManualLimitsDto;
import com.stocks.contract.manualbot.dto.ManualResultStatus;
import com.stocks.contract.manualbot.dto.ManualSessionDto;
import com.stocks.contract.manualbot.dto.ManualSessionStatus;
import com.stocks.contract.manualbot.dto.UntrustedManualTextDto;
import com.stocks.contract.manualbot.dto.UpdateManualBotRequest;
import com.stocks.contract.marketdata.dto.ClockDto;
import com.stocks.contract.portfolio.LeaderboardService;
import com.stocks.contract.portfolio.dto.LeaderboardDto;
import com.stocks.contract.portfolio.dto.LeaderboardRowDto;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.security.Scopes;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.ExecutionFilter;
import com.stocks.contract.trading.OrderFilter;
import com.stocks.contract.trading.OrderQueries;
import com.stocks.contract.trading.OrderService;
import com.stocks.contract.trading.OrderStatusFilter;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.EstimateDto;
import com.stocks.contract.trading.dto.ExecutionDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.contract.trading.dto.PositionDto;
import com.stocks.contract.trading.dto.PreviewDto;
import com.stocks.identity.PlayerRecord;
import com.stocks.identity.PlayerService;
import com.stocks.marketdata.mapper.ClockDtoMapper;
import com.stocks.realtime.SubscriptionTracker;
import com.stocks.rules.RulesService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual bots (§20.8): an AI bot whose owner (or the admin) drives it from its console by copy and paste. The owner
 * saves instructions, makes a prompt, pastes the AI's reply back, checks it (parse plus a live preview per row) and
 * places the ticked rows as the bot. The only writer of {@code manual_bot} and {@code manual_session} (through
 * {@link ManualBotRepository}).
 *
 * <p>Security: every owner route takes a human on a browser session; the bot must be AI and owned by that human
 * unless the human is the admin; writes also need both players ACTIVE. Orders go through {@link OrderService} with a
 * principal synthesized for the bot (never put in a {@code SecurityContext} or a session), so {@code OrderGate}
 * enforces the bot's status and write limits; {@code RequestMeta} says {@code WEB} and
 * {@code mcpClient = "manual-console <sessionId>"}, which lands in {@code trade_order.mcp_client} and the bot's ORDER
 * audit rows. A separate audit row names the human ({@code MANUAL_BOT_UPDATE}, {@code MANUAL_SESSION_EXECUTE}).
 *
 * <p>Execute is idempotent: per-session lock (one instance, D1), {@code executed_at} is set before the first order (so
 * no later reply can reuse the index-based client order ids {@code mbs-<ulid>-<n>}), rows already placed are skipped,
 * and a retried row replays its order. Rows run CANCEL, then SELL, then BUY; between groups a short settle wait lets
 * requested cancels and REGULAR-session MARKET sells finish ({@code settle-wait}).
 */
@Service
public class ManualBotService {

  private static final Logger log = LoggerFactory.getLogger(ManualBotService.class);

  /**
   * {@code AgentRunService.KEY_SHAPED} with {@code [ \t]+} after "bearer" instead of {@code \s+}: a match never spans a
   * line break, so redacting a reply before parsing keeps its line numbers.
   */
  static final Pattern KEY_SHAPED_INLINE = Pattern.compile(
      "stk_[A-Za-z0-9]{6,}_[A-Za-z0-9_\\-]{8,}|sk-[A-Za-z0-9_\\-]{12,}|(?i:bearer)[ \\t]+[A-Za-z0-9._~+/\\-]{16,}=*");
  static final String REDACTED = "[redacted]";
  static final Duration FILLS_WINDOW = Duration.ofDays(7);
  static final Duration POLL = Duration.ofMillis(200);
  static final int OPEN_ORDERS_READ = 200;
  static final int SUMMARIES_IN_PROMPT = 3;
  static final int LEADERBOARD_ROWS = 100;
  static final int JOURNAL_SYMBOLS = 5;
  static final int STORED_JSON_MAX = 100_000;
  static final int ROW_WARNINGS_WHEN_LARGE = 3;
  static final Set<ManualResultStatus> DONE = Set.of(ManualResultStatus.PLACED, ManualResultStatus.REPLAYED,
      ManualResultStatus.CANCELLED, ManualResultStatus.CANCEL_REQUESTED);

  /** What {@code manual_session.parsed_json} holds: the session-level issues and the rows (previews, results). */
  public record StoredParse(List<ManualIssueDto> issues, List<ManualActionDto> actions) {

    static final StoredParse EMPTY = new StoredParse(List.of(), List.of());
  }

  private final ManualBotRepository repo;
  private final PlayerService players;
  private final PlayerQueries playerQueries;
  private final AccountQueries accounts;
  private final OrderQueries orderQueries;
  private final OrderService orders;
  private final JournalService journal;
  private final LeaderboardService leaderboard;
  private final RulesService rules;
  private final ClockDtoMapper clock;
  private final SubscriptionTracker tracker;
  private final AuditService audit;
  private final PublicId publicIds;
  private final TimeSource time;
  private final JsonMapper json;
  private final ManualPromptBuilder prompts;
  private final ManualBotsProperties props;
  private final Map<Long, Object> locks = new ConcurrentHashMap<>();        // per session: reply and execute
  private final Map<Long, Object> botLocks = new ConcurrentHashMap<>();     // per bot: new prompts

  public ManualBotService(ManualBotRepository repo, PlayerService players, PlayerQueries playerQueries,
                          AccountQueries accounts, OrderQueries orderQueries, OrderService orders,
                          JournalService journal, LeaderboardService leaderboard, RulesService rules,
                          ClockDtoMapper clock, SubscriptionTracker tracker, AuditService audit, PublicId publicIds,
                          TimeSource time, JsonMapper json, ManualPromptBuilder prompts,
                          ManualBotsProperties properties) {
    this.repo = repo;
    this.players = players;
    this.playerQueries = playerQueries;
    this.accounts = accounts;
    this.orderQueries = orderQueries;
    this.orders = orders;
    this.journal = journal;
    this.leaderboard = leaderboard;
    this.rules = rules;
    this.clock = clock;
    this.tracker = tracker;
    this.audit = audit;
    this.publicIds = publicIds;
    this.time = time;
    this.json = json;
    this.prompts = prompts;
    this.props = properties == null ? ManualBotsProperties.defaults() : properties;
  }

  // ------------------------------------------------------------------------------------------- operator check

  /** The human operating a bot and the bot. */
  record Operator(PlayerPrincipal human, PlayerRecord bot) {}

  /**
   * The §20.8 operator check (it mirrors the OAuth grant's owner rule): a human on a browser session, an AI bot, its
   * owner or the admin; writes also need the human and the bot ACTIVE.
   */
  Operator operator(PlayerPrincipal human, String handle, boolean write) {
    if (human == null || human.authMethod() != AuthMethod.SESSION || human.kind() != PlayerKind.HUMAN) {
      throw ApiException.of(ErrorCode.FORBIDDEN, "The manual console needs a person signed in on a browser.");
    }
    PlayerRecord bot = players.byHandle(handle).orElseThrow(() -> ApiException.of(ErrorCode.PLAYER_NOT_FOUND,
        "No player '" + Text.clip(String.valueOf(handle), 40) + "'."));
    if (bot.kind() != PlayerKind.AI) {
      throw ApiException.of(ErrorCode.FORBIDDEN, "Only AI bots have a manual console.");
    }
    boolean owner = bot.ownerPlayerId() != null && bot.ownerPlayerId() == human.playerId();
    if (!owner && !human.isAdmin()) {
      throw ApiException.of(ErrorCode.FORBIDDEN, "Only @" + ownerHandle(bot) + " (or the admin) can operate @"
          + bot.handle() + ".");
    }
    if (write) {
      requireActive(playerQueries.status(human.playerId()), "Your player is");
      requireActive(playerQueries.status(bot.id()), "@" + bot.handle() + " is");
    }
    return new Operator(human, bot);
  }

  private static void requireActive(PlayerStatus status, String who) {
    if (status == PlayerStatus.FROZEN) {
      throw ApiException.of(ErrorCode.PLAYER_FROZEN, who + " frozen by the admin.");
    }
    if (status == PlayerStatus.DISABLED) {
      throw ApiException.of(ErrorCode.PLAYER_DISABLED, who + " disabled.");
    }
  }

  private String ownerHandle(PlayerRecord bot) {
    return bot.ownerPlayerId() == null ? "?" : players.byId(bot.ownerPlayerId()).map(PlayerRecord::handle).orElse("?");
  }

  /** The bot as the order services see it: AI, all scopes, SESSION (never stored in a SecurityContext). */
  private PlayerPrincipal asBot(PlayerRecord bot) {
    return new PlayerPrincipal(bot.id(), bot.publicId(), bot.handle(), PlayerKind.AI, PlayerRole.PLAYER, Scopes.ALL,
        AuthMethod.SESSION, null, null, time.now());
  }

  /** The bot's request metadata: WEB, with the session in {@code mcpClient} (→ {@code trade_order.mcp_client}). */
  static RequestMeta botMeta(RequestMeta human, String sessionId) {
    return new RequestMeta(human == null ? null : human.requestId(), Channel.WEB, human == null ? null : human.ip(),
        human == null ? null : human.userAgent(), "manual-console " + sessionId, null);
  }

  // ------------------------------------------------------------------------------------------- console state

  /** {@code GET /me/bots/{handle}/manual}: works for FROZEN and DISABLED bots too. */
  public ManualBotDto state(PlayerPrincipal human, String handle) {
    return dto(operator(human, handle, false).bot());
  }

  /**
   * {@code GET /me/bots}: the console state of every AI bot the signed-in person owns, by handle (the admin too gets
   * only the bots it owns: the list feeds the owner's prompts screen).
   */
  public List<ManualBotDto> mine(PlayerPrincipal human) {
    if (human == null || human.authMethod() != AuthMethod.SESSION || human.kind() != PlayerKind.HUMAN) {
      throw ApiException.of(ErrorCode.FORBIDDEN, "The manual console needs a person signed in on a browser.");
    }
    List<ManualBotDto> out = new ArrayList<>();
    for (long id : repo.botsOwnedBy(human.playerId())) {
      players.byId(id).ifPresent(bot -> out.add(dto(bot)));
    }
    return out;
  }

  /** {@code PUT /me/bots/{handle}/manual}: instructions, the model label and/or the name (null = unchanged). */
  public ManualBotDto update(PlayerPrincipal human, String handle, UpdateManualBotRequest request, RequestMeta meta) {
    PlayerRecord bot = operator(human, handle, true).bot();
    String instructions = request == null || request.instructions() == null ? null
        : cleanInstructions(request.instructions());
    if (instructions != null && instructions.length() > props.maxInstructionsChars()) {
      throw ApiException.validation("instructions", "At most " + String.format(Locale.US, "%,d",
          props.maxInstructionsChars()) + " characters.");
    }
    String model = request == null || request.aiModel() == null ? null : PlayerService.validateAiModel(
        request.aiModel());
    String name = request == null || request.displayName() == null ? null : request.displayName().trim();
    if (name != null) {
      PlayerService.validateDisplayName(name);
      if (name.equals(bot.displayName())) {
        name = null;
      }
    }
    if (instructions == null && model == null && name == null) {
      return dto(bot);
    }
    if (instructions != null) {
      repo.saveInstructions(bot.id(), instructions, human.playerId(), time.now());
    }
    if (model != null) {
      players.setAiModel(bot.id(), model);
    }
    if (name != null) {
      players.updateDisplayName(bot.id(), name);
    }
    AuditEntry entry = AuditEntry.of(AuditCategory.ACCOUNT, "MANUAL_BOT_UPDATE", AuditOutcome.OK).withActor(human)
        .withMeta(meta).withTarget("player", bot.publicId());
    if (instructions != null) {
      entry = entry.withDetail("instructionsChars", instructions.length());
    }
    if (model != null) {
      entry = entry.withDetail("aiModel", model);
    }
    if (name != null) {
      entry = entry.withDetail("displayName", name);
    }
    audit.record(entry);
    return dto(players.byId(bot.id()).orElse(bot));
  }

  /**
   * Instructions as stored: CRLF/CR → LF, control and format characters removed per line (line breaks kept),
   * trimmed, key-shaped strings redacted.
   */
  static String cleanInstructions(String raw) {
    String lf = raw.replace("\r\n", "\n").replace('\r', '\n');
    String joined = Arrays.stream(lf.split("\n", -1)).map(Text::stripControl).collect(Collectors.joining("\n"));
    return KEY_SHAPED_INLINE.matcher(joined.strip()).replaceAll(REDACTED);
  }

  private ManualBotDto dto(PlayerRecord bot) {
    Instant now = time.now();
    Optional<ManualBotRepository.BotRow> row = repo.bot(bot.id());
    PlayerSummaryDto summary = playerQueries.byId(bot.id()).orElse(null);
    return new ManualBotDto(summary, ownerHandle(bot), bot.status(),
        row.map(ManualBotRepository.BotRow::instructions).orElse(""),
        row.map(ManualBotRepository.BotRow::updatedAt).orElse(null),
        row.map(ManualBotRepository.BotRow::updatedByHandle).orElse(null), repo.activeApiKeys(bot.id(), now),
        repo.activeConnectors(bot.id()), repo.sessionsSince(bot.id(), Et.startOfDay(Et.date(now))),
        props.sessionsPerDay(), limits());
  }

  private ManualLimitsDto limits() {
    return new ManualLimitsDto(props.maxInstructionsChars(), props.maxReplyChars(), props.maxOrders(),
        props.expireAfter().toString());
  }

  // ------------------------------------------------------------------------------------------- new prompt

  /** {@code POST /me/bots/{handle}/manual-sessions}: builds and stores a prompt (status PROMPTED). */
  public ManualSessionDto newSession(PlayerPrincipal human, String handle) {
    PlayerRecord bot = operator(human, handle, true).bot();
    synchronized (botLocks.computeIfAbsent(bot.id(), k -> new Object())) {     // the daily cap counts in the database
      return prompt(human, bot);
    }
  }

  private ManualSessionDto prompt(PlayerPrincipal human, PlayerRecord bot) {
    Instant now = time.now();
    LocalDate today = Et.date(now);
    if (repo.sessionsSince(bot.id(), Et.startOfDay(today)) >= props.sessionsPerDay()) {
      Instant free = Et.startOfDay(today.plusDays(1));
      long seconds = Math.max(1, (Duration.between(now, free).toMillis() + 999) / 1000);
      throw ApiException.of(ErrorCode.RATE_LIMITED, "At most " + props.sessionsPerDay() + " prompts per day (ET) for @"
          + bot.handle() + ". Retry in " + seconds + " s.").withRetryAfter(seconds);
    }
    long accountId = accounts.activeAccountId(bot.id());
    String sessionId = publicIds.next(Prefix.MANUAL_SESSION);
    ClockDto c = clock.clock(now);
    AccountDto account = accounts.account(accountId);
    List<PositionDto> positions = accounts.positions(accountId);
    List<OrderDto> open = orderQueries.list(accountId, new OrderFilter(OrderStatusFilter.OPEN, null, null, null), null,
        OPEN_ORDERS_READ).items();
    List<ExecutionDto> fills = props.recentFills() <= 0 ? List.of()
        : orderQueries.executions(accountId, new ExecutionFilter(null, now.minus(FILLS_WINDOW), null), null,
            props.recentFills()).items();
    LeaderboardRowDto standing = null;
    int ranked = 0;
    try {
      LeaderboardDto board = leaderboard.board(LeaderboardPeriod.ALL, LeaderboardMetric.RETURN, KindFilter.ALL,
          LEADERBOARD_ROWS, bot.id());
      standing = board.me();
      ranked = (int) board.rows().stream().filter(r -> r.rank() != null).count();
    } catch (RuntimeException e) {
      log.warn("manual prompt for @{}: no standing ({})", bot.handle(), e.toString());
    }
    String aiModel = Text.clip(bot.aiModel() == null || bot.aiModel().isBlank() ? "AI" : bot.aiModel(), 24);
    String instructions = repo.bot(bot.id()).map(ManualBotRepository.BotRow::instructions).orElse("");
    String prompt = prompts.build(new ManualPromptBuilder.Input(sessionId, now, bot.handle(), bot.displayName(),
        aiModel, ownerHandle(bot), instructions, c, rules.rules(), account, positions, open, fills, standing, ranked,
        repo.lastSummaries(bot.id(), SUMMARIES_IN_PROMPT)));
    if (prompt.length() > props.maxPromptChars()) {
      log.error("manual prompt for @{} is {} characters after every cut (max {})", bot.handle(), prompt.length(),
          props.maxPromptChars());
      throw ApiException.of(ErrorCode.INTERNAL_ERROR, "The prompt is too long to make; please report this.");
    }
    repo.insertSession(sessionId, bot.id(), accountId, human.playerId(), aiModel, c.session().name(), sha256(prompt),
        prompt, now);
    try {
      tracker.touchBotLeases(bot.id(), positions.stream().map(PositionDto::symbol).toList());
    } catch (RuntimeException e) {
      log.debug("touchBotLeases({}) failed: {}", bot.handle(), e.toString());
    }
    return dto(repo.session(sessionId).orElseThrow(), true);
  }

  // ------------------------------------------------------------------------------------------- reply

  /**
   * {@code POST …/manual-sessions/{sessionId}/reply}: redacts, parses and previews a pasted reply (status REPLIED).
   * Repeatable until the first execute.
   */
  public ManualSessionDto reply(PlayerPrincipal human, String handle, String sessionId, String reply) {
    PlayerRecord bot = operator(human, handle, true).bot();
    if (reply == null || reply.isBlank()) {
      throw ApiException.validation("reply", "Paste the AI's whole reply.");
    }
    if (reply.length() > props.maxReplyChars()) {
      throw ApiException.validation("reply", "The reply is longer than " + String.format(Locale.US, "%,d",
          props.maxReplyChars()) + " characters; paste only the part with the JSON block.");
    }
    ManualBotRepository.SessionRow first = sessionOf(bot, sessionId);
    synchronized (lock(first.id())) {
      ManualBotRepository.SessionRow s = repo.session(first.publicId()).orElseThrow();
      if (s.executedAt() != null) {
        throw ApiException.of(ErrorCode.VALIDATION_FAILED, "This session already placed orders; make a new prompt.");
      }
      Instant now = time.now();
      requireFresh(s, now);
      requireSameRun(bot, s);
      String redacted = KEY_SHAPED_INLINE.matcher(reply).replaceAll(REDACTED);
      ManualReplyParser.ParsedReply parsed = ManualReplyParser.parse(redacted, s.publicId(),
          ManualReplyParser.Limits.of(props));
      List<ManualIssueDto> issues = new ArrayList<>(parsed.issues());
      List<ManualActionDto> actions = validate(bot, s, parsed.actions(), issues, now);
      String sha = sha256(redacted);
      repo.executedWithReply(bot.id(), sha, s.id()).ifPresent(other -> issues.add(warning("DUPLICATE_REPLY",
          "This reply was already placed in session " + other + "; placing it again duplicates those orders.")));
      repo.storeReply(s.id(), redacted, sha, parsed.summary(), store(issues, actions), now);
      return dto(repo.session(s.publicId()).orElseThrow(), true);
    }
  }

  /**
   * §20.8 row validation: CANCEL targets must be open orders of the bot; BUY/SELL rows without parse errors get a
   * live preview (its errors and warnings become row issues, with the batch-dependency downgrades); then the
   * session-level warnings.
   */
  private List<ManualActionDto> validate(PlayerRecord bot, ManualBotRepository.SessionRow s,
                                         List<ManualActionDto> parsed, List<ManualIssueDto> issues, Instant now) {
    long accountId = s.accountId();
    List<String> symbols = parsed.stream().filter(a -> isTrade(a) && a.symbol() != null).map(ManualActionDto::symbol)
        .distinct().toList();
    try {
      tracker.touchBotLeases(bot.id(), symbols);
    } catch (RuntimeException e) {
      log.debug("touchBotLeases({}) failed: {}", bot.handle(), e.toString());
    }
    List<ManualActionDto> rows = new ArrayList<>(parsed);
    // CANCEL rows first: the batch checks below need their targets
    List<OrderDto> cancelled = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      ManualActionDto a = rows.get(i);
      if (a.action() != ManualActionKind.CANCEL || hasError(a)) {
        continue;
      }
      Optional<OrderDto> target = PublicId.isValid(a.orderId(), Prefix.ORDER)
          ? orderQueries.byPublicId(accountId, a.orderId(), false) : Optional.empty();
      List<ManualIssueDto> more = new ArrayList<>();
      String symbol = null;
      if (target.isEmpty()) {
        more.add(rowIssue(ManualIssueSeverity.ERROR, "ORDER_NOT_FOUND", "order_id", a, "No open order "
            + Text.clip(a.orderId(), 40) + " of @" + bot.handle(), "Use an id from \"Your open orders\"."));
      } else if (!target.get().status().isOpen()) {
        symbol = target.get().symbol();
        more.add(rowIssue(ManualIssueSeverity.ERROR, "ORDER_NOT_CANCELLABLE", "order_id", a, a.orderId()
            + " is already " + target.get().status(), null));
      } else {
        symbol = target.get().symbol();
        cancelled.add(target.get());
      }
      rows.set(i, with(a, symbol, more, a.estimate()));
    }
    boolean anySell = rows.stream().anyMatch(a -> a.action() == ManualActionKind.SELL && !hasError(a));
    boolean cancelFreesBuy = cancelled.stream().anyMatch(o -> o.side() == Side.BUY);
    PlayerPrincipal asBot = asBot(bot);
    BigDecimal buyingPower = null;
    for (int i = 0; i < rows.size(); i++) {
      ManualActionDto a = rows.get(i);
      if (!isTrade(a) || hasError(a)) {
        continue;
      }
      PreviewDto pv;
      try {
        pv = orders.preview(asBot, request(a, null));
      } catch (RuntimeException e) {
        log.warn("manual preview of row {} for @{} failed: {}", a.index(), bot.handle(), e.toString());
        rows.set(i, with(a, a.symbol(), List.of(rowIssue(ManualIssueSeverity.ERROR, "INTERNAL_ERROR", null, a,
            "This order could not be checked right now; check the reply again.", null)), null));
        continue;
      }
      if (buyingPower == null && pv.buyingPowerBefore() != null) {
        buyingPower = pv.buyingPowerBefore();
      }
      List<ManualIssueDto> more = new ArrayList<>();
      for (PreviewDto.PreviewErrorDto e : pv.errors() == null ? List.<PreviewDto.PreviewErrorDto>of() : pv.errors()) {
        String symbol = a.symbol();
        if (a.action() == ManualActionKind.SELL && ErrorCode.INSUFFICIENT_POSITION.name().equals(e.code())
            && cancelled.stream().anyMatch(o -> o.side() == Side.SELL && o.symbol().equals(symbol))) {
          more.add(new ManualIssueDto(ManualIssueSeverity.WARNING, "DEPENDS_ON_CANCEL", e.field(), a.line(), null,
              "may fail: the shares are held by an open order that a CANCEL above frees", null));
        } else if (a.action() == ManualActionKind.BUY && ErrorCode.INSUFFICIENT_BUYING_POWER.name().equals(e.code())
            && (cancelFreesBuy || anySell)) {
          more.add(new ManualIssueDto(ManualIssueSeverity.WARNING, "DEPENDS_ON_EARLIER_ROWS", e.field(), a.line(),
              null, "may fail: buying power depends on the cancels or sells above, which run first; sale proceeds "
                  + "count only once the sell fills", null));
        } else {
          more.add(new ManualIssueDto(ManualIssueSeverity.ERROR, e.code(), e.field(), a.line(), null, e.message(),
              e.hint()));
        }
      }
      for (Warning w : pv.warnings() == null ? List.<Warning>of() : pv.warnings()) {
        more.add(new ManualIssueDto(ManualIssueSeverity.WARNING, w.code(), null, a.line(), null, w.message(), null));
      }
      rows.set(i, with(a, a.symbol(), more, pv.estimate()));
    }
    batchWarnings(bot, accountId, rows, cancelled, buyingPower, issues);
    Duration age = Duration.between(s.createdAt(), now);
    if (age.compareTo(props.staleAfter()) > 0) {
      issues.add(warning("STALE_PROMPT", "The prompt is " + ManualPromptBuilder.age(age) + " old; prices and the "
          + "session may have changed (the previews are live)."));
    }
    SessionType current = clock.clock(now).session();
    if (!current.name().equals(s.marketSession())) {
      issues.add(warning("SESSION_CHANGED", "The market was " + s.marketSession() + " when the prompt was made and "
          + "is " + current + " now."));
    }
    if (age.compareTo(props.expireAfter()) > 0) {
      issues.add(new ManualIssueDto(ManualIssueSeverity.ERROR, "PROMPT_EXPIRED", null, null, null, "The prompt is "
          + "older than " + props.expireAfter().toHours() + " h; make a new one.", null));
    }
    return rows;
  }

  /** {@code BATCH_BUYING_POWER} and {@code BATCH_SELL_QTY}: rows are previewed one by one, these look at all. */
  private void batchWarnings(PlayerRecord bot, long accountId, List<ManualActionDto> rows, List<OrderDto> cancelled,
                             BigDecimal buyingPower, List<ManualIssueDto> issues) {
    BigDecimal holds = BigDecimal.ZERO;
    boolean anyBuy = false;
    for (ManualActionDto a : rows) {
      if (a.action() == ManualActionKind.BUY && a.executable() && a.estimate() != null
          && a.estimate().hold() != null) {
        holds = holds.add(a.estimate().hold());
        anyBuy = true;
      }
    }
    if (anyBuy && buyingPower != null && holds.compareTo(buyingPower) > 0) {
      issues.add(warning("BATCH_BUYING_POWER", "Together the buys hold " + ManualPromptBuilder.money(holds)
          + "; buying power is " + ManualPromptBuilder.money(buyingPower) + ". Later buys may be rejected."));
    }
    Map<String, BigDecimal> sells = new LinkedHashMap<>();
    for (ManualActionDto a : rows) {
      if (a.action() == ManualActionKind.SELL && a.executable() && a.symbol() != null) {
        BigDecimal q = a.qty() != null ? a.qty() : a.estimate() == null ? null : a.estimate().qty();
        if (q != null) {
          sells.merge(a.symbol(), q, BigDecimal::add);
        }
      }
    }
    if (sells.isEmpty()) {
      return;
    }
    Map<String, BigDecimal> available = new LinkedHashMap<>();
    for (PositionDto p : accounts.positions(accountId)) {
      available.put(p.symbol(), p.availableQty() == null ? BigDecimal.ZERO : p.availableQty());
    }
    for (OrderDto o : cancelled) {
      if (o.side() == Side.SELL) {
        available.merge(o.symbol(), remaining(o), BigDecimal::add);
      }
    }
    sells.forEach((symbol, total) -> {
      BigDecimal can = available.getOrDefault(symbol, BigDecimal.ZERO);
      if (total.compareTo(can) > 0) {
        issues.add(warning("BATCH_SELL_QTY", "The sells of " + symbol + " total " + ManualPromptBuilder.qty(total)
            + " shares; @" + bot.handle() + " can sell " + ManualPromptBuilder.qty(can) + "."));
      }
    });
  }

  /** The shares an open sell still holds. */
  private static BigDecimal remaining(OrderDto o) {
    if (o.qty() != null) {
      return o.qty().subtract(o.filledQty() == null ? BigDecimal.ZERO : o.filledQty()).max(BigDecimal.ZERO);
    }
    return o.reservedQty() == null ? BigDecimal.ZERO : o.reservedQty();
  }

  // ------------------------------------------------------------------------------------------- execute

  /**
   * {@code POST …/manual-sessions/{sessionId}/execute}: places the ticked rows as the bot (CANCEL, SELL, BUY), posts
   * the summary to the journal once, and audits. An empty list only posts the summary.
   */
  public ManualSessionDto execute(PlayerPrincipal human, String handle, String sessionId, ManualExecuteRequest request,
                                  RequestMeta meta) {
    PlayerRecord bot = operator(human, handle, true).bot();
    if (request == null || request.actions() == null) {
      throw ApiException.validation("actions", "Required: the rows to place ([] posts the summary only).");
    }
    ManualBotRepository.SessionRow first = sessionOf(bot, sessionId);
    synchronized (lock(first.id())) {
      ManualBotRepository.SessionRow s = repo.session(first.publicId()).orElseThrow();
      if (ManualSessionStatus.PROMPTED.name().equals(s.status())) {
        throw ApiException.of(ErrorCode.VALIDATION_FAILED, "Check a reply first.");
      }
      StoredParse parsed = read(s.parsedJson());
      if (parsed.issues().stream().anyMatch(i -> i.severity() == ManualIssueSeverity.ERROR)) {
        throw ApiException.of(ErrorCode.VALIDATION_FAILED, "Fix the reply first.");
      }
      Instant now = time.now();
      requireFresh(s, now);
      requireSameRun(bot, s);
      List<ManualActionDto> rows = new ArrayList<>(parsed.actions());
      Set<Integer> ticked = new LinkedHashSet<>();
      for (Integer index : request.actions()) {
        ManualActionDto row = index == null ? null : rows.stream().filter(r -> r.index() == index).findFirst()
            .orElse(null);
        if (row == null || !row.executable()) {
          throw ApiException.validation("actions", "Row " + index + " is unknown or cannot be placed.");
        }
        ticked.add(index);
      }
      if (s.executedAt() == null) {
        repo.markExecuted(s.id(), now);     // before the first order: no later reply can reuse these client ids
      }
      PlayerPrincipal asBot = asBot(bot);
      RequestMeta botMeta = botMeta(meta, s.publicId());
      List<ManualIssueDto> issues = new ArrayList<>(parsed.issues());
      Run run = new Run(s, rows, issues, asBot, botMeta);

      List<String> requested = run.group(ticked, ManualActionKind.CANCEL);
      boolean laterRows = rows.stream().anyMatch(r -> ticked.contains(r.index())
          && r.action() != ManualActionKind.CANCEL && !done(r));
      if (laterRows) {
        settle(s.accountId(), requested);
      }
      List<String> marketSells = run.group(ticked, ManualActionKind.SELL);
      boolean buysFollow = rows.stream().anyMatch(r -> ticked.contains(r.index())
          && r.action() == ManualActionKind.BUY && !done(r));
      if (buysFollow && clock.clock(time.now()).session() == SessionType.REGULAR) {
        settle(s.accountId(), marketSells);
      }
      run.group(ticked, ManualActionKind.BUY);

      ManualBotRepository.SessionRow latest = repo.session(s.publicId()).orElseThrow();
      if (latest.journalNoteId() == null && latest.summary() != null && !latest.summary().isBlank()) {
        List<String> symbols = rows.stream().filter(r -> r.result() != null && (r.result().status()
                == ManualResultStatus.PLACED || r.result().status() == ManualResultStatus.REPLAYED))
            .map(ManualActionDto::symbol).filter(x -> x != null).distinct().limit(JOURNAL_SYMBOLS).toList();
        issues.removeIf(i -> "JOURNAL_SKIPPED".equals(i.code()));
        try {
          AddJournalNoteResultDto note = journal.add(asBot, new AddJournalNoteRequest(latest.summary(), symbols),
              Channel.WEB);
          repo.storeJournalNote(s.id(), note.note().id());
        } catch (ApiException e) {
          issues.add(warning("JOURNAL_SKIPPED", "Journal note skipped: " + e.detail()));
        } catch (RuntimeException e) {
          log.error("manual session {}: journal note failed", s.publicId(), e);
          issues.add(warning("JOURNAL_SKIPPED", "Journal note skipped: unexpected error; place again to retry."));
        }
        repo.storeParsed(s.id(), store(issues, rows));
      }
      audit.record(AuditEntry.of(AuditCategory.ORDER, "MANUAL_SESSION_EXECUTE", AuditOutcome.OK).withActor(human)
          .withMeta(botMeta).withTarget("manual_session", s.publicId()).withDetail("bot", bot.handle())
          .withDetail("placed", run.placed).withDetail("failed", run.failed).withDetail("orders", run.orderIds));
      return dto(repo.session(s.publicId()).orElseThrow(), true);
    }
  }

  /** One execute: places rows one group at a time and persists after each row. */
  private final class Run {

    final ManualBotRepository.SessionRow session;
    final List<ManualActionDto> rows;
    final List<ManualIssueDto> issues;
    final PlayerPrincipal asBot;
    final RequestMeta meta;
    final List<String> orderIds = new ArrayList<>();
    int placed;
    int failed;

    Run(ManualBotRepository.SessionRow session, List<ManualActionDto> rows, List<ManualIssueDto> issues,
        PlayerPrincipal asBot, RequestMeta meta) {
      this.session = session;
      this.rows = rows;
      this.issues = issues;
      this.asBot = asBot;
      this.meta = meta;
    }

    /**
     * Runs the ticked rows of one kind in reply order (rows already done are skipped). Returns the orders to settle:
     * CANCEL_REQUESTED ones for the CANCEL group, placed MARKET sells for the SELL group.
     */
    List<String> group(Set<Integer> ticked, ManualActionKind kind) {
      List<String> settle = new ArrayList<>();
      for (int i = 0; i < rows.size(); i++) {
        ManualActionDto a = rows.get(i);
        if (a.action() != kind || !ticked.contains(a.index()) || done(a)) {
          continue;
        }
        ManualActionResultDto result = kind == ManualActionKind.CANCEL ? cancel(a) : place(a);
        rows.set(i, withResult(a, result));
        if (result.status() == ManualResultStatus.FAILED) {
          failed++;
        } else {
          placed++;
          orderIds.add(result.orderId());
          if (result.status() == ManualResultStatus.CANCEL_REQUESTED
              || (kind == ManualActionKind.SELL && a.type() == OrderType.MARKET
                  && result.status() == ManualResultStatus.PLACED)) {
            settle.add(result.orderId());
          }
        }
        repo.storeParsed(session.id(), store(issues, rows));
      }
      return settle;
    }

    private ManualActionResultDto cancel(ManualActionDto a) {
      try {
        OrderDto d = orders.cancel(asBot, a.orderId(), meta);
        return new ManualActionResultDto(d.status() == OrderStatus.CANCELLED
            ? ManualResultStatus.CANCELLED : ManualResultStatus.CANCEL_REQUESTED, d.orderId(), d.status(), null, null,
            time.now());
      } catch (RuntimeException e) {
        return failure(a, e);
      }
    }

    private ManualActionResultDto place(ManualActionDto a) {
      try {
        PlaceOrderResponse r = orders.place(asBot, request(a, clientOrderId(session.publicId(), a.index())), meta);
        return new ManualActionResultDto(r.replayed() ? ManualResultStatus.REPLAYED : ManualResultStatus.PLACED,
            r.order().orderId(), r.order().status(), null, null, time.now());
      } catch (RuntimeException e) {
        return failure(a, e);
      }
    }

    private ManualActionResultDto failure(ManualActionDto a, RuntimeException e) {
      if (e instanceof ApiException api) {
        return new ManualActionResultDto(ManualResultStatus.FAILED, null, null, api.code().name(), api.detail(),
            time.now());
      }
      log.error("manual session {} row {} failed", session.publicId(), a.index(), e);
      return new ManualActionResultDto(ManualResultStatus.FAILED, null, null, ErrorCode.INTERNAL_ERROR.name(),
          "Unexpected error; try placing this row again.", time.now());
    }
  }

  /** The order a BUY/SELL row stands for; identical on every retry, so a retry replays instead of duplicating. */
  static PlaceOrderRequest request(ManualActionDto a, String clientOrderId) {
    return new PlaceOrderRequest(clientOrderId, a.symbol(), a.action() == ManualActionKind.BUY ? Side.BUY : Side.SELL,
        a.type(), a.qty(), a.notional(), a.limitPrice(), a.stopPrice(), a.trailAmount(), a.trailPercent(),
        a.timeInForce(), a.extendedHours(), a.rationale(), null,
        a.acknowledge() == null ? Set.of() : Set.copyOf(a.acknowledge()), null);
  }

  /** {@code mbs-<ulid>-<index>}. */
  static String clientOrderId(String sessionId, int index) {
    return "mbs-" + sessionId.substring(Prefix.MANUAL_SESSION.code().length() + 1) + "-" + index;
  }

  /** Polls the orders every 200 ms until none is open, for at most {@code settle-wait}. */
  private void settle(long accountId, List<String> orderIds) {
    long polls = props.settleWait() == null ? 0 : (props.settleWait().toMillis() + POLL.toMillis() - 1)
        / POLL.toMillis();
    for (long i = 0; i < polls && !orderIds.isEmpty(); i++) {
      boolean open = orderIds.stream().anyMatch(id -> orderQueries.byPublicId(accountId, id, false)
          .map(o -> o.status().isOpen()).orElse(false));
      if (!open) {
        return;
      }
      try {
        Thread.sleep(POLL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  // ------------------------------------------------------------------------------------------- public reads

  /** {@code GET /players/{handle}/manual-sessions}: list shape, newest first. */
  public Page<ManualSessionDto> sessions(String handle, Cursor cursor, int limit) {
    PlayerRecord p = listed(handle);
    return repo.sessions(p.id(), cursor, limit).map(r -> dto(r, false));
  }

  /** {@code GET /players/{handle}/manual-sessions/{sessionId}}: detail shape. */
  public ManualSessionDto session(String handle, String sessionId) {
    return dto(sessionOf(listed(handle), sessionId), true);
  }

  private PlayerRecord listed(String handle) {
    return players.byHandle(handle).filter(p -> p.status() != PlayerStatus.DISABLED)
        .orElseThrow(() -> ApiException.of(ErrorCode.PLAYER_NOT_FOUND, "No player '"
            + Text.clip(String.valueOf(handle), 40) + "'."));
  }

  // ------------------------------------------------------------------------------------------- helpers

  private Object lock(long sessionId) {
    return locks.computeIfAbsent(sessionId, k -> new Object());
  }

  /** The session if it belongs to {@code bot}; NOT_FOUND otherwise (another bot's id is not found either). */
  private ManualBotRepository.SessionRow sessionOf(PlayerRecord bot, String sessionId) {
    Optional<ManualBotRepository.SessionRow> s = PublicId.isValid(sessionId, Prefix.MANUAL_SESSION)
        ? repo.session(sessionId) : Optional.empty();
    return s.filter(r -> r.playerId() == bot.id()).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND,
        "No session " + Text.clip(String.valueOf(sessionId), 40) + " for @" + bot.handle() + "."));
  }

  private void requireFresh(ManualBotRepository.SessionRow s, Instant now) {
    if (Duration.between(s.createdAt(), now).compareTo(props.expireAfter()) > 0) {
      throw ApiException.of(ErrorCode.VALIDATION_FAILED, "This prompt is older than " + props.expireAfter().toHours()
          + " h; make a new one.");
    }
  }

  /** The reset check: the bot's active run must still be the prompt's. */
  private void requireSameRun(PlayerRecord bot, ManualBotRepository.SessionRow s) {
    long active;
    try {
      active = accounts.activeAccountId(bot.id());
    } catch (ApiException e) {
      active = -1;
    }
    if (active != s.accountId()) {
      throw ApiException.of(ErrorCode.VALIDATION_FAILED, "@" + bot.handle() + " was reset since this prompt; make a "
          + "new prompt.");
    }
  }

  private static boolean isTrade(ManualActionDto a) {
    return a.action() == ManualActionKind.BUY || a.action() == ManualActionKind.SELL;
  }

  private static boolean hasError(ManualActionDto a) {
    return a.issues() != null && a.issues().stream().anyMatch(i -> i.severity() == ManualIssueSeverity.ERROR);
  }

  private static boolean done(ManualActionDto a) {
    return a.result() != null && DONE.contains(a.result().status());
  }

  private static ManualIssueDto warning(String code, String message) {
    return new ManualIssueDto(ManualIssueSeverity.WARNING, code, null, null, null, message, null);
  }

  private static ManualIssueDto rowIssue(ManualIssueSeverity severity, String code, String field, ManualActionDto a,
                                         String message, String hint) {
    return new ManualIssueDto(severity, code, field, a.line(), null, message, hint);
  }

  /** The row with more issues, a symbol and an estimate; still executable only if it was and no ERROR came in. */
  private static ManualActionDto with(ManualActionDto a, String symbol, List<ManualIssueDto> more,
                                      EstimateDto estimate) {
    List<ManualIssueDto> issues = new ArrayList<>(a.issues() == null ? List.of() : a.issues());
    issues.addAll(more);
    boolean executable = a.executable() && more.stream().noneMatch(i -> i.severity() == ManualIssueSeverity.ERROR);
    return new ManualActionDto(a.index(), a.line(), a.action(), symbol, a.type(), a.qty(), a.notional(),
        a.limitPrice(), a.stopPrice(), a.trailAmount(), a.trailPercent(), a.timeInForce(), a.extendedHours(),
        a.acknowledge(), a.orderId(), a.rationale(), executable, List.copyOf(issues), estimate, a.result());
  }

  private static ManualActionDto withResult(ManualActionDto a, ManualActionResultDto result) {
    return new ManualActionDto(a.index(), a.line(), a.action(), a.symbol(), a.type(), a.qty(), a.notional(),
        a.limitPrice(), a.stopPrice(), a.trailAmount(), a.trailPercent(), a.timeInForce(), a.extendedHours(),
        a.acknowledge(), a.orderId(), a.rationale(), a.executable(), a.issues(), a.estimate(), result);
  }

  private static ManualActionDto withIssues(ManualActionDto a, List<ManualIssueDto> issues) {
    return new ManualActionDto(a.index(), a.line(), a.action(), a.symbol(), a.type(), a.qty(), a.notional(),
        a.limitPrice(), a.stopPrice(), a.trailAmount(), a.trailPercent(), a.timeInForce(), a.extendedHours(),
        a.acknowledge(), a.orderId(), a.rationale(), a.executable(), issues, a.estimate(), a.result());
  }

  /**
   * Serializes the parse under the storage caps: at most {@code max-stored-rows} rows and {@code max-issues}
   * issues; over 100,000 characters, row WARNINGs beyond the first 3 per row are dropped; still over: refused.
   */
  String store(List<ManualIssueDto> issues, List<ManualActionDto> actions) {
    List<ManualIssueDto> kept = issues.size() > props.maxIssues() ? issues.subList(0, props.maxIssues()) : issues;
    List<ManualActionDto> rows = actions.size() > props.maxStoredRows() ? actions.subList(0, props.maxStoredRows())
        : actions;
    String text = write(new StoredParse(List.copyOf(kept), List.copyOf(rows)));
    if (text.length() <= STORED_JSON_MAX) {
      return text;
    }
    List<ManualActionDto> trimmed = rows.stream().map(a -> withIssues(a, trimWarnings(a.issues()))).toList();
    text = write(new StoredParse(List.copyOf(kept), trimmed));
    if (text.length() <= STORED_JSON_MAX) {
      return text;
    }
    throw ApiException.of(ErrorCode.VALIDATION_FAILED, "The reply is too complex to store; ask the AI for fewer "
        + "orders.");
  }

  private static List<ManualIssueDto> trimWarnings(List<ManualIssueDto> issues) {
    List<ManualIssueDto> out = new ArrayList<>();
    int warnings = 0;
    for (ManualIssueDto i : issues) {
      if (i.severity() == ManualIssueSeverity.WARNING && ++warnings > ROW_WARNINGS_WHEN_LARGE) {
        continue;
      }
      out.add(i);
    }
    return List.copyOf(out);
  }

  private String write(StoredParse p) {
    try {
      return json.writeValueAsString(p);
    } catch (JacksonException e) {
      throw new IllegalStateException("manual session parse not serializable", e);
    }
  }

  StoredParse read(String parsedJson) {
    if (parsedJson == null || parsedJson.isBlank()) {
      return StoredParse.EMPTY;
    }
    try {
      StoredParse p = json.readValue(parsedJson, StoredParse.class);
      return new StoredParse(p.issues() == null ? List.of() : p.issues(),
          p.actions() == null ? List.of() : p.actions());
    } catch (JacksonException e) {
      log.error("manual session parse unreadable: {}", e.toString());
      return StoredParse.EMPTY;
    }
  }

  private ManualSessionDto dto(ManualBotRepository.SessionRow r, boolean detail) {
    StoredParse p = read(r.parsedJson());
    return new ManualSessionDto(r.publicId(), r.botHandle(), r.operatorHandle(), ManualSessionStatus.valueOf(r.status()),
        r.aiModel(), SessionType.valueOf(r.marketSession()), r.createdAt(), r.repliedAt(), r.executedAt(),
        r.promptSha256(), r.promptChars(), r.replyChars(),
        new UntrustedManualTextDto(r.summary(), detail ? r.promptText() : null, detail ? r.replyText() : null),
        p.issues(), p.actions(), r.journalNoteId());
  }

  static String sha256(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
