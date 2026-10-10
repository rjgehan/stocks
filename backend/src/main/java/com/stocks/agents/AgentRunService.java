package com.stocks.agents;

import com.stocks.agents.AgentRunRepository.DayRow;
import com.stocks.agents.AgentRunRepository.RunRow;
import com.stocks.agents.AgentRunRepository.ToolCount;
import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.error.ProblemField;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.common.web.Text;
import com.stocks.contract.agents.dto.AdminBotDto;
import com.stocks.contract.agents.dto.AdminBotDto.BotDayDto;
import com.stocks.contract.agents.dto.AdminBotDto.BotTodayDto;
import com.stocks.contract.agents.dto.AdminBotDto.BotToolCodeDto;
import com.stocks.contract.agents.dto.AdminBotDto.BotToolDto;
import com.stocks.contract.agents.dto.AgentRunDto;
import com.stocks.contract.agents.dto.AgentRunDto.AgentRunTokensDto;
import com.stocks.contract.agents.dto.AgentRunDto.AgentToolCountDto;
import com.stocks.contract.agents.dto.AgentRunReportRequest;
import com.stocks.contract.agents.dto.AgentRunReportRequest.AgentRunTokensRequest;
import com.stocks.contract.agents.dto.AgentRunReportRequest.AgentRunToolRequest;
import com.stocks.contract.agents.dto.AgentRunStatus;
import com.stocks.contract.agents.dto.AgentRunSummaryDto;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import com.stocks.contract.security.PlayerPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Agent runs (§20.6): the runner's reports ({@code POST /agent-runs}, the bot's own key), the public "Bot activity"
 * summary of a profile and the admin Bots page. The only writer of {@code agent_run} and {@code agent_run_tool}.
 *
 * <p>A report is validated field by field (every problem is listed in one {@code VALIDATION_FAILED}), cleaned
 * (message without control characters and key-shaped strings, clipped to 500), and stored for the <b>key's</b>
 * player: the body cannot name another bot. The same {@code runRef} again returns the stored run (200), whatever the
 * second body says, so the runner can retry a report that timed out.
 */
@Service
public class AgentRunService {

  static final Pattern RUN_REF = Pattern.compile("^[A-Za-z0-9._:-]{8,64}$");
  static final Pattern SLOT = Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9]$");
  static final Pattern TOOL = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
  static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,47}$");
  /** {@code stk_<keyId>_<secret>}, OpenAI/Anthropic {@code sk-…} keys and bearer tokens. */
  static final Pattern KEY_SHAPED = Pattern.compile(
      "stk_[A-Za-z0-9]{6,}_[A-Za-z0-9_\\-]{8,}|sk-[A-Za-z0-9_\\-]{12,}|(?i:bearer)\\s+[A-Za-z0-9._~+/\\-]{16,}=*");
  static final long MAX_COUNT = 1_000_000_000L;
  static final BigDecimal MAX_USD = new BigDecimal("100000");
  static final int USD_SCALE = 6;
  static final int MAX_USD_DECIMALS = 12;
  static final Duration MAX_RUN = Duration.ofHours(6);
  static final Duration FUTURE_SLACK = Duration.ofMinutes(5);
  static final int MAX_TOOLS = 200;
  static final int MESSAGE_MAX = 500;
  static final int MODEL_MAX = 100;
  static final int RUNNER_VERSION_MAX = 40;
  /** Admin Bots page: the spend chart's days and the tool breakdown's days (§20.6). */
  static final int CHART_DAYS = 14;
  static final int TOOL_DAYS = 7;
  /** {@code AdminBot.lastProblem}. */
  static final Set<AgentRunStatus> PROBLEMS = Arrays.stream(AgentRunStatus.values()).filter(AgentRunStatus::problem)
      .collect(() -> EnumSet.noneOf(AgentRunStatus.class), Set::add, Set::addAll);

  /** A stored run and whether this call created it (201) or found it by {@code runRef} (200). */
  public record Reported(AgentRunDto run, boolean created) {}

  private final AgentRunRepository runs;
  private final AgentPlayers players;
  private final PublicId publicIds;
  private final TimeSource time;

  public AgentRunService(AgentRunRepository runs, AgentPlayers players, PublicId publicIds, TimeSource time) {
    this.runs = runs;
    this.players = players;
    this.publicIds = publicIds;
    this.time = time;
  }

  // ------------------------------------------------------------------------------------------- report

  /**
   * Stores one run for the caller. Only an AI player's API key may report ({@code FORBIDDEN} otherwise; the route
   * already requires the {@code trade} scope).
   */
  public Reported report(PlayerPrincipal caller, AgentRunReportRequest request) {
    requireBotKey(caller);
    if (request == null) {
      throw ApiException.validation("body", "A run report is required.");
    }
    List<ProblemField> errors = new ArrayList<>();
    String runRef = request.runRef() == null ? null : request.runRef().strip();
    if (runRef == null || !RUN_REF.matcher(runRef).matches()) {
      errors.add(field("runRef", "8-64 characters of letters, digits and . _ : -"));
    } else {
      Optional<RunRow> existing = runs.byRef(caller.playerId(), runRef);
      if (existing.isPresent()) {
        return new Reported(dto(existing.get()), false);
      }
    }
    if (request.trigger() == null) {
      errors.add(field("trigger", "Required: SCHEDULE or ONCE"));
    }
    String slot = Text.trimToNull(request.slot());
    if (slot != null && !SLOT.matcher(slot).matches()) {
      errors.add(field("slot", "HH:MM (ET) or null"));
    }
    String model = Text.trimToNull(Text.stripControl(request.model()));
    if (model == null || model.length() > MODEL_MAX) {
      errors.add(field("model", "1-" + MODEL_MAX + " characters"));
    }
    if (request.status() == null) {
      errors.add(field("status", "Required: one of the runner's statuses"));
    }
    Instant now = time.now();
    Instant started = request.startedAt();
    Instant ended = request.endedAt();
    if (started == null) {
      errors.add(field("startedAt", "Required"));
    }
    if (ended == null) {
      errors.add(field("endedAt", "Required"));
    }
    if (started != null && ended != null) {
      if (ended.isBefore(started)) {
        errors.add(field("endedAt", "Must not be before startedAt"));
      } else if (Duration.between(started, ended).compareTo(MAX_RUN) > 0) {
        errors.add(field("endedAt", "A run lasts at most 6 hours"));
      }
      if (ended.isAfter(now.plus(FUTURE_SLACK))) {
        errors.add(field("endedAt", "Is in the future"));
      }
    }
    int turns = count(request.turns(), "turns", errors);
    int toolCalls = count(request.toolCalls(), "toolCalls", errors);
    int toolErrors = count(request.toolErrors(), "toolErrors", errors);
    int orders = count(request.orders(), "orders", errors);
    AgentRunTokensRequest t = request.tokens();
    long tokensIn = tokens(t == null ? null : t.input(), "tokens.input", errors);
    long tokensCacheRead = tokens(t == null ? null : t.cacheRead(), "tokens.cacheRead", errors);
    long tokensCacheWrite = tokens(t == null ? null : t.cacheWrite(), "tokens.cacheWrite", errors);
    long tokensOut = tokens(t == null ? null : t.output(), "tokens.output", errors);
    BigDecimal cost = usd(request.costUsd(), "costUsd", errors);
    BigDecimal daySpent = usd(request.daySpentUsd(), "daySpentUsd", errors);
    BigDecimal dailyCap = usd(request.dailyCapUsd(), "dailyCapUsd", errors);
    List<ToolCount> tools = tools(request.tools(), errors);
    if (!errors.isEmpty()) {
      throw ApiException.of(ErrorCode.VALIDATION_FAILED, "Invalid run report: " + errors.get(0).field() + ": "
          + errors.get(0).message()).withFields(errors);
    }
    String message = cleanMessage(request.message());
    String runnerVersion = Text.trimToNull(Text.clip(request.runnerVersion(), RUNNER_VERSION_MAX));
    RunRow row = new RunRow(0, publicIds.next(Prefix.AGENT_RUN), caller.playerId(), caller.apiKeyId(), runRef,
        request.trigger(), slot, model, request.status(), started, ended, Et.date(started),
        Duration.between(started, ended).toMillis(), turns, toolCalls, toolErrors, orders, tokensIn, tokensCacheRead,
        tokensCacheWrite, tokensOut, cost, daySpent, dailyCap, message, runnerVersion, now);
    try {
      runs.insert(row, tools);
    } catch (DuplicateKeyException e) {
      // the same runRef raced in from a retry: the first one wins, this one answers like a repeat
      return runs.byRef(caller.playerId(), runRef).map(r -> new Reported(dto(r), false)).orElseThrow(() -> e);
    }
    return new Reported(dto(runs.byRef(caller.playerId(), runRef).orElseThrow()), true);
  }

  /** §20.6: only an API key of an AI player reports runs (a human's key or a browser session is FORBIDDEN). */
  public static void requireBotKey(PlayerPrincipal caller) {
    if (caller == null || caller.authMethod() != AuthMethod.API_KEY || caller.kind() != PlayerKind.AI) {
      throw ApiException.of(ErrorCode.FORBIDDEN, "Only an AI player's API key may report agent runs.");
    }
  }

  private static ProblemField field(String name, String message) {
    return new ProblemField(name, ErrorCode.VALIDATION_FAILED.name(), message);
  }

  private static int count(Integer value, String name, List<ProblemField> errors) {
    if (value == null) {
      return 0;
    }
    if (value < 0 || value > MAX_COUNT) {
      errors.add(field(name, "0-1000000000"));
      return 0;
    }
    return value;
  }

  private static long tokens(Long value, String name, List<ProblemField> errors) {
    if (value == null) {
      return 0;
    }
    if (value < 0 || value > MAX_COUNT) {
      errors.add(field(name, "0-1000000000"));
      return 0;
    }
    return value;
  }

  /** A USD amount 0-100,000 with at most 12 decimals, stored at scale 6 (HALF_UP). */
  static BigDecimal usd(BigDecimal value, String name, List<ProblemField> errors) {
    if (value == null) {
      errors.add(field(name, "Required: a decimal string such as \"0.1834\""));
      return BigDecimal.ZERO;
    }
    if (value.signum() < 0 || value.compareTo(MAX_USD) > 0 || value.stripTrailingZeros().scale() > MAX_USD_DECIMALS) {
      errors.add(field(name, "0-100000 with at most 12 decimals"));
      return BigDecimal.ZERO;
    }
    return value.setScale(USD_SCALE, RoundingMode.HALF_UP);
  }

  /** At most 200 entries; duplicates of one (tool, code) are summed; zero counts are dropped. */
  static List<ToolCount> tools(List<AgentRunToolRequest> tools, List<ProblemField> errors) {
    if (tools == null) {
      return List.of();
    }
    if (tools.size() > MAX_TOOLS) {
      errors.add(field("tools", "At most " + MAX_TOOLS + " entries"));
      return List.of();
    }
    Map<String, ToolCount> merged = new TreeMap<>();
    for (int i = 0; i < tools.size(); i++) {
      AgentRunToolRequest t = tools.get(i);
      String at = "tools[" + i + "]";
      if (t == null) {
        errors.add(field(at, "Required"));
        continue;
      }
      if (t.tool() == null || !TOOL.matcher(t.tool()).matches()) {
        errors.add(field(at + ".tool", "A tool name: ^[a-z][a-z0-9_]{0,63}$"));
        continue;
      }
      if (t.code() == null || !CODE.matcher(t.code()).matches()) {
        errors.add(field(at + ".code", "OK or an error code: ^[A-Z][A-Z0-9_]{0,47}$"));
        continue;
      }
      if (t.calls() == null || t.calls() < 0 || t.calls() > MAX_COUNT) {
        errors.add(field(at + ".calls", "0-1000000000"));
        continue;
      }
      if (t.calls() == 0) {
        continue;
      }
      merged.merge(t.tool() + " " + t.code(), new ToolCount(t.tool(), t.code(), t.calls()),
          (a, b) -> new ToolCount(a.tool(), a.code(), (int) Math.min(MAX_COUNT, (long) a.calls() + b.calls())));
    }
    return List.copyOf(merged.values());
  }

  /** Control characters out (newlines become spaces), key-shaped strings redacted, clipped to 500 characters. */
  static String cleanMessage(String message) {
    if (message == null) {
      return null;
    }
    String flat = message.replaceAll("[\\r\\n\\t]+", " ");
    String redacted = KEY_SHAPED.matcher(Text.stripControl(flat)).replaceAll("[redacted]");
    return Text.trimToNull(Text.clip(redacted.strip(), MESSAGE_MAX));
  }

  // ------------------------------------------------------------------------------------------- public summary

  /** §20.6 {@code AgentRunSummary} of a listed player: zeros and nulls for a player without runs. */
  public AgentRunSummaryDto summary(String handle) {
    AgentPlayers.PlayerRef p = players.requireListed(handle);
    Optional<RunRow> last = runs.latest(p.id());
    return new AgentRunSummaryDto(p.handle(), runs.count(p.id()),
        runs.countSince(p.id(), time.now().minus(Duration.ofDays(7))), last.map(RunRow::startedAt).orElse(null),
        last.map(RunRow::status).orElse(null), last.map(RunRow::orders).orElse(null),
        last.map(RunRow::toolCalls).orElse(null));
  }

  // ------------------------------------------------------------------------------------------- admin

  /** Every AI player (DISABLED ones last): last run, last problem, today's spend vs the cap, 14 days, 7-day tools. */
  public List<AdminBotDto> adminBots() {
    LocalDate today = time.todayEt();
    List<AgentPlayers.PlayerRef> bots = new ArrayList<>(players.aiPlayers());
    bots.sort(Comparator.comparing((AgentPlayers.PlayerRef p) -> !p.listed()).thenComparing(AgentPlayers.PlayerRef::handle));
    List<AdminBotDto> out = new ArrayList<>();
    for (AgentPlayers.PlayerRef bot : bots) {
      Optional<RunRow> last = runs.latest(bot.id());
      Optional<RunRow> problem = runs.latestWithStatus(bot.id(), PROBLEMS);
      Map<LocalDate, DayRow> byDay = new LinkedHashMap<>();
      for (DayRow d : runs.days(bot.id(), today.minusDays(CHART_DAYS - 1L), today)) {
        byDay.put(d.date(), d);
      }
      List<BotDayDto> days = new ArrayList<>();
      for (int i = CHART_DAYS - 1; i >= 0; i--) {
        LocalDate d = today.minusDays(i);
        DayRow row = byDay.get(d);
        days.add(row == null ? new BotDayDto(d, 0, BigDecimal.ZERO.setScale(USD_SCALE), null)
            : new BotDayDto(d, row.runs(), row.spentUsd(), row.capUsd()));
      }
      DayRow todayRow = byDay.get(today);
      BigDecimal spent = todayRow == null ? BigDecimal.ZERO.setScale(USD_SCALE) : todayRow.spentUsd();
      BigDecimal cap = todayRow != null ? todayRow.capUsd() : last.map(RunRow::dailyCapUsd).orElse(null);
      boolean capReached = cap != null && cap.signum() > 0 && spent.compareTo(cap) >= 0;
      BotTodayDto todayDto = new BotTodayDto(today, todayRow == null ? 0 : todayRow.runs(), spent, cap, capReached);
      List<RunRow> withTools = new ArrayList<>();
      last.ifPresent(withTools::add);
      problem.ifPresent(withTools::add);
      Map<Long, List<ToolCount>> tools = runs.tools(withTools.stream().map(RunRow::id).distinct().toList());
      out.add(new AdminBotDto(players.summary(bot.id()), bot.status(), last.map(r -> dto(r, tools)).orElse(null),
          problem.map(r -> dto(r, tools)).orElse(null), todayDto, days,
          toolBreakdown(runs.toolTotals(bot.id(), today.minusDays(TOOL_DAYS - 1L)))));
    }
    return out;
  }

  /** Per tool (most calls first): calls, errors (every code but OK) and the calls per code (most first). */
  static List<BotToolDto> toolBreakdown(List<ToolCount> totals) {
    Map<String, List<ToolCount>> byTool = new LinkedHashMap<>();
    for (ToolCount t : totals) {
      byTool.computeIfAbsent(t.tool(), k -> new ArrayList<>()).add(t);
    }
    List<BotToolDto> out = new ArrayList<>();
    for (Map.Entry<String, List<ToolCount>> e : byTool.entrySet()) {
      int calls = 0;
      int errors = 0;
      List<BotToolCodeDto> codes = new ArrayList<>();
      for (ToolCount t : e.getValue()) {
        calls += t.calls();
        if (!"OK".equals(t.code())) {
          errors += t.calls();
        }
        codes.add(new BotToolCodeDto(t.code(), t.calls()));
      }
      codes.sort(Comparator.comparingInt(BotToolCodeDto::calls).reversed().thenComparing(BotToolCodeDto::code));
      out.add(new BotToolDto(e.getKey(), calls, errors, codes));
    }
    out.sort(Comparator.comparingInt(BotToolDto::calls).reversed().thenComparing(BotToolDto::tool));
    return out;
  }

  /** {@code GET /admin/agent-runs}: newest first; {@code handle} (any status) and {@code status} narrow it. */
  public Page<AgentRunDto> adminRuns(String handle, String status, Cursor cursor, int limit) {
    Long playerId = null;
    if (handle != null && !handle.isBlank()) {
      playerId = players.byHandle(handle).map(AgentPlayers.PlayerRef::id)
          .orElseThrow(() -> ApiException.of(ErrorCode.PLAYER_NOT_FOUND, "No player '" + Text.clip(handle, 40) + "'."));
    }
    AgentRunStatus statusFilter = parseStatus(status);
    List<RunRow> rows = runs.page(playerId, statusFilter, cursor, limit + 1);
    Page<RunRow> page = Page.fromLookahead(rows, limit, r -> Cursor.of(r.startedAt(), r.id()));
    Map<Long, List<ToolCount>> tools = runs.tools(page.items().stream().map(RunRow::id).toList());
    return page.map(r -> dto(r, tools));
  }

  static AgentRunStatus parseStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return AgentRunStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("status", "Must be one of " + Arrays.toString(AgentRunStatus.values()));
    }
  }

  // ------------------------------------------------------------------------------------------- mapping

  AgentRunDto dto(RunRow r) {
    return dto(r, runs.tools(List.of(r.id())));
  }

  private AgentRunDto dto(RunRow r, Map<Long, List<ToolCount>> tools) {
    PlayerSummaryDto player = players.summary(r.playerId());
    List<AgentToolCountDto> counts = tools.getOrDefault(r.id(), List.of()).stream()
        .map(t -> new AgentToolCountDto(t.tool(), t.code(), t.calls())).toList();
    return new AgentRunDto(r.publicId(), player, r.runRef(), r.trigger(), r.slot(), r.model(), r.status(),
        r.startedAt(), r.endedAt(), r.tradeDate(), r.durationMs(), r.turns(), r.toolCalls(), r.toolErrors(),
        r.orders(), new AgentRunTokensDto(r.tokensInput(), r.tokensCacheRead(), r.tokensCacheWrite(),
        r.tokensOutput()), r.costUsd(), r.daySpentUsd(), r.dailyCapUsd(), r.message(), r.runnerVersion(), counts,
        r.receivedAt());
  }
}
