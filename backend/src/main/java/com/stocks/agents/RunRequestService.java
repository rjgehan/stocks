package com.stocks.agents;

import com.stocks.agents.RunRequestRepository.RequestRow;
import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Text;
import com.stocks.contract.agents.dto.FinishRunRequest;
import com.stocks.contract.agents.dto.RunNowRequest;
import com.stocks.contract.agents.dto.RunRequestListDto;
import com.stocks.contract.agents.dto.RunRequestDto;
import com.stocks.contract.agents.dto.RunRequestStatus;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * "Run now" (§20.9): the owner of an AI player, or the admin, asks for one of the bot's normal check-ins from the app;
 * the claude-trader service (docs/agents.md §2.11) claims the request with the bot's own key, runs the check-in and
 * reports back. The only writer of {@code agent_run_request}.
 *
 * <ul>
 *   <li>A bot has at most one open (PENDING or CLAIMED) request: another one is 409 {@code RUN_REQUEST_CONFLICT}
 *       (the table's open-request constraint also catches two racing in). At most
 *       {@code stocks.agents.run-requests.max-per-day} (20) requests per bot in any 24 hours, else 429
 *       {@code RATE_LIMITED}.</li>
 *   <li>Expiry is lazy, applied to a bot whenever its requests are created, listed or claimed: PENDING longer than
 *       {@code pending-ttl} (30 min) becomes EXPIRED; CLAIMED longer than {@code claim-ttl} (30 min) without a report
 *       becomes FAILED.</li>
 *   <li>A bot that is not ACTIVE cannot be run: its PENDING requests become EXPIRED ("Not run: @bot is FROZEN …")
 *       at the same lazy points, and a claim for it gets nothing. (A FROZEN or DISABLED bot's key is refused before
 *       it reaches the claim anyway; this also covers a status that changes while the request waits.)</li>
 *   <li>Claiming is an UPDATE guarded by {@code status = 'PENDING'}, so two claims never both win (PostgreSQL and
 *       H2). Finishing moves CLAIMED to DONE or FAILED; the same final status again answers the stored request.</li>
 *   <li>Every claim records when a trader last asked for the bot ({@code traderSeenAt} of the list), in memory: the
 *       button tells the owner when no trader serves the bot (one instance, §5; empty for up to a minute after a
 *       restart, until the trader's next claim).</li>
 * </ul>
 * Every accepted request is audited ({@code ACCOUNT BOT_RUN_REQUEST}: who asked, for which bot), and so is one refused
 * by the daily limit.
 */
@Service
public class RunRequestService {

  static final int LIST_DEFAULT = 10;
  static final int LIST_MAX = 50;
  static final Duration LIMIT_WINDOW = Duration.ofHours(24);
  private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

  private final RunRequestRepository requests;
  private final AgentPlayers players;
  private final PublicId publicIds;
  private final TimeSource time;
  private final AuditService audit;
  private final AgentsProperties.RunRequests settings;
  /** The bot's player id → when a trader last claimed (or asked for) its requests. */
  private final Map<Long, Instant> traderSeen = new ConcurrentHashMap<>();

  public RunRequestService(RunRequestRepository requests, AgentPlayers players, PublicId publicIds, TimeSource time,
                           AuditService audit, AgentsProperties properties) {
    this.requests = requests;
    this.players = players;
    this.publicIds = publicIds;
    this.time = time;
    this.audit = audit;
    this.settings = (properties == null ? AgentsProperties.defaults() : properties).runRequests();
  }

  // ------------------------------------------------------------------------------------------- the owner

  /** {@code POST /me/bots/{handle}/run-requests}: a new PENDING request for the caller's (or, as admin, any) bot. */
  public RunRequestDto request(PlayerPrincipal caller, String handle, RunNowRequest body, RequestMeta meta) {
    AgentPlayers.PlayerRef bot = botFor(caller, handle);
    if (bot.status() != PlayerStatus.ACTIVE) {
      throw ApiException.of(ErrorCode.PLAYER_FROZEN, "@" + bot.handle() + " is " + bot.status()
          + ": it cannot trade, so it cannot be run.");
    }
    boolean dryRun = body != null && Boolean.TRUE.equals(body.dryRun());
    Instant now = time.now();
    expire(bot, now);
    Optional<RequestRow> open = requests.open(bot.id());
    if (open.isPresent()) {
      throw conflict(open.get());
    }
    requireBudget(caller, bot, now, meta);
    String id = publicIds.next(Prefix.AGENT_RUN_REQUEST);
    try {
      requests.insert(id, bot.id(), caller.playerId(), now, dryRun);
    } catch (DuplicateKeyException e) {
      // another request for the bot raced in between the check and the insert
      throw requests.open(bot.id()).map(RunRequestService::conflict).orElseThrow(() -> e);
    }
    audit.record(AuditEntry.of(AuditCategory.ACCOUNT, "BOT_RUN_REQUEST", AuditOutcome.OK).withActor(caller)
        .withMeta(meta).withTarget("player", bot.handle()).withDetail("requestId", id).withDetail("dryRun", dryRun));
    return dto(requests.byPublicId(id).orElseThrow());
  }

  /**
   * {@code GET /me/bots/{handle}/run-requests}: the bot's latest requests, newest first (any status of the bot), its
   * status and when a trader last asked for its requests.
   */
  public RunRequestListDto list(PlayerPrincipal caller, String handle, Integer limit) {
    AgentPlayers.PlayerRef bot = botFor(caller, handle);
    expire(bot, time.now());
    int n = limit == null || limit < 1 ? LIST_DEFAULT : Math.min(limit, LIST_MAX);
    return new RunRequestListDto(bot.status(), traderSeen.get(bot.id()),
        requests.latest(bot.id(), n).stream().map(RunRequestService::dto).toList());
  }

  /** A listed AI player that {@code caller} owns, or any as an ACTIVE admin. */
  private AgentPlayers.PlayerRef botFor(PlayerPrincipal caller, String handle) {
    AgentPlayers.PlayerRef bot = players.requireListed(handle);
    if (bot.kind() != PlayerKind.AI) {
      throw ApiException.of(ErrorCode.FORBIDDEN, "@" + bot.handle() + " is not an AI player; only bots can be run.");
    }
    boolean owner = Objects.equals(bot.ownerId(), caller.playerId());
    boolean admin = caller.isAdmin() && players.byId(caller.playerId())
        .map(p -> p.status() == PlayerStatus.ACTIVE).orElse(false);
    if (!owner && !admin) {
      throw ApiException.of(ErrorCode.FORBIDDEN, "Only @" + bot.handle() + "'s owner or the admin can run it.");
    }
    return bot;
  }

  /** At most {@code max-per-day} requests per bot in any 24 hours; beyond that 429 until the oldest leaves. */
  private void requireBudget(PlayerPrincipal caller, AgentPlayers.PlayerRef bot, Instant now, RequestMeta meta) {
    int max = settings.maxPerDay();
    if (max <= 0) {
      return;
    }
    RunRequestRepository.Made made = requests.madeSince(bot.id(), now.minus(LIMIT_WINDOW));
    if (made.count() < max) {
      return;
    }
    Instant free = made.oldest() == null ? now.plus(LIMIT_WINDOW) : made.oldest().plus(LIMIT_WINDOW);
    long seconds = Math.max(1, (Duration.between(now, free).toMillis() + 999) / 1000);
    audit.record(AuditEntry.of(AuditCategory.ACCOUNT, "BOT_RUN_REQUEST", AuditOutcome.RATE_LIMITED)
        .withActor(caller).withMeta(meta).withTarget("player", bot.handle()).withDetail("code", "RATE_LIMITED"));
    throw ApiException.of(ErrorCode.RATE_LIMITED, "At most " + max + " run requests per bot in 24 hours. Retry in "
        + seconds + " s.").withRetryAfter(seconds);
  }

  private static ApiException conflict(RequestRow open) {
    String detail = open.status() == RunRequestStatus.CLAIMED
        ? "@" + open.handle() + " is already running a requested check-in (picked up at " + hhmm(open.claimedAt())
            + " ET); wait until it reports back."
        : "@" + open.handle() + " already has a run request waiting for the trader (requested at "
            + hhmm(open.requestedAt()) + " ET by @" + open.requestedBy() + "); wait until it is picked up or expires.";
    return ApiException.of(ErrorCode.RUN_REQUEST_CONFLICT, detail);
  }

  // ------------------------------------------------------------------------------------------- the trader

  /**
   * {@code POST /agent-runs/requests/claim}: the calling bot's oldest PENDING request, now CLAIMED by the caller's
   * key; empty when there is none (or another claim won it) and when the bot is not ACTIVE.
   */
  public Optional<RunRequestDto> claim(PlayerPrincipal caller) {
    requireBotKey(caller);
    Instant now = time.now();
    traderSeen.put(caller.playerId(), now);
    AgentPlayers.PlayerRef bot = players.byId(caller.playerId()).orElse(null);
    if (bot == null) {
      return Optional.empty();
    }
    expire(bot, now);
    if (bot.status() != PlayerStatus.ACTIVE) {
      return Optional.empty();
    }
    for (int attempt = 0; attempt < 3; attempt++) {
      Optional<RequestRow> next = requests.oldestPending(caller.playerId());
      if (next.isEmpty()) {
        return Optional.empty();
      }
      if (requests.claim(next.get().id(), now, caller.apiKeyId()) == 1) {
        return requests.byPublicId(next.get().publicId()).map(RunRequestService::dto);
      }
    }
    return Optional.empty();
  }

  /**
   * {@code POST /agent-runs/requests/{id}/finish}: CLAIMED → DONE or FAILED, by the bot it belongs to. The same final
   * status again answers the stored request (a retried report); any other state is 409 {@code RUN_REQUEST_CONFLICT}.
   */
  public RunRequestDto finish(PlayerPrincipal caller, String requestId, FinishRunRequest body) {
    requireBotKey(caller);
    RunRequestStatus status = body == null ? null : body.status();
    if (status != RunRequestStatus.DONE && status != RunRequestStatus.FAILED) {
      throw ApiException.validation("status", "status must be DONE or FAILED");
    }
    RequestRow r = Optional.ofNullable(requestId).filter(id -> PublicId.isValid(id, Prefix.AGENT_RUN_REQUEST))
        .flatMap(requests::byPublicId).filter(x -> x.playerId() == caller.playerId())
        .orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "No run request '" + Text.clip(requestId, 40)
            + "' for @" + caller.handle() + "."));
    if (r.status() == RunRequestStatus.CLAIMED) {
      String message = AgentRunService.cleanMessage(body.message());
      requests.finish(r.id(), status, time.now(), message);
      r = requests.byPublicId(r.publicId()).orElseThrow();
    }
    if (r.status() == status) {
      return dto(r);
    }
    throw ApiException.of(ErrorCode.RUN_REQUEST_CONFLICT, "The run request is " + r.status()
        + "; only a CLAIMED request can be finished" + (r.status().open() ? "." : ", and only once."));
  }

  /** Only an AI player's API key claims and finishes run requests (a human's key or a browser session is FORBIDDEN). */
  public static void requireBotKey(PlayerPrincipal caller) {
    if (caller == null || caller.authMethod() != AuthMethod.API_KEY || caller.kind() != PlayerKind.AI) {
      throw ApiException.of(ErrorCode.FORBIDDEN, "Only the bot's own API key may claim and finish its run requests.");
    }
  }

  // ------------------------------------------------------------------------------------------- expiry and mapping

  /** The lazy expiry of one bot's requests (see the class comment). */
  void expire(AgentPlayers.PlayerRef bot, Instant now) {
    if (bot.status() != PlayerStatus.ACTIVE) {
      requests.expireAllPending(bot.id(), now, "Not run: @" + bot.handle() + " is " + bot.status()
          + ", so it cannot trade. Request the run again once it is ACTIVE.");
    }
    requests.expirePending(bot.id(), now.minus(settings.pendingTtl()), now, "Expired: the trader did not pick it up "
        + "within " + minutes(settings.pendingTtl()) + ". Is the claude-trader service running, with @" + bot.handle()
        + " enabled in its agents file?");
    requests.failStaleClaims(bot.id(), now.minus(settings.claimTtl()), now, "The trader did not report back within "
        + minutes(settings.claimTtl()) + " of picking it up.");
  }

  private static String minutes(Duration d) {
    long m = d.toMinutes();
    return m == 1 ? "1 minute" : m + " minutes";
  }

  private static String hhmm(Instant at) {
    return at == null ? "?" : Et.time(at).format(HH_MM);
  }

  static RunRequestDto dto(RequestRow r) {
    return new RunRequestDto(r.publicId(), r.handle(), r.requestedBy(), r.requestedAt(), r.dryRun(), r.status(),
        r.claimedAt(), r.finishedAt(), r.message());
  }
}
