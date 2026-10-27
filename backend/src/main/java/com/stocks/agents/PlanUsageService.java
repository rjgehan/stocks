package com.stocks.agents;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.error.ProblemField;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.agents.dto.AgentRunDto.AgentRunTokensDto;
import com.stocks.contract.agents.dto.AgentRunReportRequest.AgentRunTokensRequest;
import com.stocks.contract.agents.dto.PlanUsageDto;
import com.stocks.contract.agents.dto.PlanUsageKind;
import com.stocks.contract.agents.dto.PlanUsageReportRequest;
import com.stocks.contract.security.PlayerPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Claude plan usage snapshots: the claude-trader reads the owner's plan meters (Claude Code's {@code /usage}: the
 * 5-hour session and the weekly limit) around every check-in and every round of check-ins, and sends them with a bot's
 * own key ({@code POST /agent-runs/plan-usage}); the bots' owner, or the admin, reads them back
 * ({@code GET /me/bots/plan-usage}) for the Claude plan panel under Admin → Bots.
 */
@Service
public class PlanUsageService {

  /** New snapshots per bot in any 24 hours: a round sends about 2 + 2 per bot, a few rounds a day. */
  static final int MAX_PER_DAY = 500;
  static final Duration WINDOW = Duration.ofHours(24);
  static final Duration FUTURE_SLACK = Duration.ofMinutes(5);
  static final Duration MAX_AGE = Duration.ofDays(7);
  static final int MAX_DAYS = 14;
  static final int MAX_ROWS = 2000;
  static final BigDecimal HUNDRED = new BigDecimal("100");
  static final BigDecimal MAX_COST = new BigDecimal("1000");
  static final long MAX_TOKENS = 1_000_000_000_000L;

  private final PlanUsageRepository repo;
  private final AgentPlayers players;
  private final TimeSource time;

  public PlanUsageService(PlanUsageRepository repo, AgentPlayers players, TimeSource time) {
    this.repo = repo;
    this.players = players;
    this.time = time;
  }

  /** {@code POST /agent-runs/plan-usage}: records one snapshot for the key's bot. */
  public PlanUsageDto report(PlayerPrincipal caller, PlanUsageReportRequest r) {
    AgentRunService.requireBotKey(caller);
    if (r == null) {
      throw ApiException.validation("body", "A plan usage snapshot is required.");
    }
    List<ProblemField> errors = new ArrayList<>();
    Instant now = time.now();
    if (r.kind() == null) {
      errors.add(field("kind", "Required: CHECKIN_BEFORE, CHECKIN_AFTER, ROUND_BEFORE or ROUND_AFTER"));
    }
    if (r.source() == null) {
      errors.add(field("source", "Required: USAGE or CHECKIN"));
    }
    Instant taken = r.takenAt();
    if (taken == null) {
      errors.add(field("takenAt", "Required"));
    } else if (taken.isAfter(now.plus(FUTURE_SLACK))) {
      errors.add(field("takenAt", "Is in the future"));
    } else if (taken.isBefore(now.minus(MAX_AGE))) {
      errors.add(field("takenAt", "Older than 7 days"));
    }
    String roundRef = ref(r.roundRef(), "roundRef", errors);
    String runRef = ref(r.runRef(), "runRef", errors);
    BigDecimal session = percent(r.sessionPercent(), "sessionPercent", errors);
    BigDecimal weekly = percent(r.weeklyPercent(), "weeklyPercent", errors);
    if (r.sessionPercent() == null && r.weeklyPercent() == null) {
      errors.add(field("weeklyPercent", "At least one of sessionPercent and weeklyPercent"));
    }
    AgentRunTokensRequest t = r.tokens();
    Long in = tokens(t == null ? null : t.input(), "tokens.input", errors);
    Long cacheWrite = tokens(t == null ? null : t.cacheWrite(), "tokens.cacheWrite", errors);
    Long cacheRead = tokens(t == null ? null : t.cacheRead(), "tokens.cacheRead", errors);
    Long out = tokens(t == null ? null : t.output(), "tokens.output", errors);
    BigDecimal cost = r.costUsd();
    if (cost != null && (cost.signum() < 0 || cost.compareTo(MAX_COST) > 0)) {
      errors.add(field("costUsd", "0-1000"));
    }
    if (!errors.isEmpty()) {
      throw ApiException.of(ErrorCode.VALIDATION_FAILED, "Invalid plan usage snapshot: " + errors.get(0).field()
          + ": " + errors.get(0).message()).withFields(errors);
    }
    if (repo.receivedSince(caller.playerId(), now.minus(WINDOW)) >= MAX_PER_DAY) {
      throw ApiException.of(ErrorCode.RATE_LIMITED, "At most " + MAX_PER_DAY + " plan usage snapshots per bot in 24 "
          + "hours.").withRetryAfter(3600);
    }
    PlanUsageRepository.Row row = new PlanUsageRepository.Row(caller.playerId(), caller.handle(), r.kind(),
        r.source(), taken, Et.date(taken), roundRef, runRef, session, r.sessionResetsAt(), weekly,
        r.weeklyResetsAt(), in, cacheWrite, cacheRead, out, cost == null ? null : cost.setScale(4, RoundingMode.HALF_UP),
        now);
    repo.insert(row);
    return dto(row);
  }

  /**
   * {@code GET /me/bots/plan-usage}: the snapshots of the last {@code days} days sent with the keys of the bots the
   * signed-in person owns (every AI player's for the admin), newest first. A browser session only.
   */
  public List<PlanUsageDto> list(PlayerPrincipal human, Integer days) {
    if (human == null || human.authMethod() != AuthMethod.SESSION || human.kind() != PlayerKind.HUMAN) {
      throw ApiException.of(ErrorCode.FORBIDDEN, "Claude plan usage needs a person signed in on a browser.");
    }
    int d = days == null ? 7 : days;
    if (d < 1 || d > MAX_DAYS) {
      throw ApiException.validation("days", "1-" + MAX_DAYS);
    }
    List<Long> bots = human.isAdmin()
        ? players.aiPlayers().stream().map(AgentPlayers.PlayerRef::id).toList()
        : players.botsOwnedBy(human.playerId());
    return repo.since(bots, time.now().minus(Duration.ofDays(d)), MAX_ROWS).stream()
        .map(PlanUsageService::dto).toList();
  }

  static PlanUsageDto dto(PlanUsageRepository.Row r) {
    boolean anyTokens = r.tokensInput() != null || r.tokensCacheWrite() != null || r.tokensCacheRead() != null
        || r.tokensOutput() != null;
    AgentRunTokensDto tokens = anyTokens ? new AgentRunTokensDto(zero(r.tokensInput()), zero(r.tokensCacheRead()),
        zero(r.tokensCacheWrite()), zero(r.tokensOutput())) : null;
    return new PlanUsageDto(r.handle(), r.kind(), r.source(), r.takenAt(), r.tradeDate(), r.roundRef(), r.runRef(),
        r.sessionPercent(), r.sessionResetsAt(), r.weeklyPercent(), r.weeklyResetsAt(), tokens, r.costUsd());
  }

  private static long zero(Long v) {
    return v == null ? 0 : v;
  }

  private static String ref(String raw, String name, List<ProblemField> errors) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String v = raw.strip();
    if (!AgentRunService.RUN_REF.matcher(v).matches()) {
      errors.add(field(name, "8-64 characters of letters, digits and . _ : -"));
      return null;
    }
    return v;
  }

  private static BigDecimal percent(BigDecimal v, String name, List<ProblemField> errors) {
    if (v == null) {
      return null;
    }
    if (v.signum() < 0 || v.compareTo(HUNDRED) > 0) {
      errors.add(field(name, "0-100"));
      return null;
    }
    return v.setScale(1, RoundingMode.HALF_UP);
  }

  private static Long tokens(Long v, String name, List<ProblemField> errors) {
    if (v == null) {
      return null;
    }
    if (v < 0 || v > MAX_TOKENS) {
      errors.add(field(name, "0-" + MAX_TOKENS));
      return null;
    }
    return v;
  }

  private static ProblemField field(String name, String message) {
    return new ProblemField(name, ErrorCode.VALIDATION_FAILED.name(), message);
  }
}
