package com.stocks.league;

import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TransferType;
import com.stocks.common.events.AccountChanged;
import com.stocks.common.events.CashTransferPosted;
import com.stocks.common.events.ExecutionCreated;
import com.stocks.common.events.JournalNotePosted;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.web.Text;
import com.stocks.contract.portfolio.dto.FeedItemDto.FeedItemKind;
import com.stocks.contract.portfolio.dto.FeedItemDto.FeedPayloadDto;
import com.stocks.contract.portfolio.dto.FeedItemDto.UntrustedFeedTextDto;
import com.stocks.portfolio.LeagueSettings;
import com.stocks.portfolio.PublicProfileService;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * <b>The only writer of {@code feed_event}</b> (§12.8). Turns engine events into league feed rows, idempotent through
 * {@code source_ref} (a re-delivered event is a no-op), and backfills a day's rows after EOD (events are lost if the
 * app crashes between commit and delivery):
 * <ul>
 *   <li>{@code ExecutionCreated} → {@code FILL}, or {@code POSITION_CLOSED} when the sell took the position to 0
 *       ({@code EXEC:<exe>}), visible after {@code social.tradeFeedDelay} (none by default); the rationale when it
 *       is public (every order since public mode, older AI orders, or a human's shared note), clipped to 280
 *       characters inside {@code untrusted};</li>
 *   <li>{@code CashTransferPosted} → {@code DEPOSIT} for deposits and withdrawals when
 *       {@code social.showDepositsInFeed} ({@code XFER:<xfr>}, visible at once; deposits are public, charter item 2);
 *       the INITIAL of a player's first run → {@code JOINED} ({@code JOIN:<ply>}), from that event or from
 *       {@code AccountChanged(reason=OPENED)};</li>
 *   <li>{@code AccountChanged(reason=RESET)} → {@code RESET} ({@code RESET:<new run's acc>});</li>
 *   <li>{@code JournalNotePosted} (Phase 2, §20.7) → {@code JOURNAL} ({@code JNL:<jnl>}), visible at the note's
 *       {@code visible_at} (written with the feed delay), the note clipped to 280 characters inside
 *       {@code untrusted};</li>
 *   <li>the EOD writer → {@code NEW_LEADER} ({@code LEADER:<date>}).</li>
 * </ul>
 * Listeners hand off to their own executor and never block the engine thread (§5.3 events).
 */
@Component
public class FeedEventListener {

  private static final Logger log = LoggerFactory.getLogger(FeedEventListener.class);

  static final int MEMO_MAX = 200;

  private final FeedRepository feed;
  private final JdbcClient jdbc;
  private final PublicId publicIds;
  private final JsonMapper json;
  private final LeagueSettings settings;
  private final ExecutorService executor;

  @Autowired
  public FeedEventListener(FeedRepository feed, JdbcClient jdbc, PublicId publicIds, JsonMapper json,
                           LeagueSettings settings) {
    this(feed, jdbc, publicIds, json, settings, Executors.newVirtualThreadPerTaskExecutor());
  }

  FeedEventListener(FeedRepository feed, JdbcClient jdbc, PublicId publicIds, JsonMapper json,
                    LeagueSettings settings, ExecutorService executor) {
    this.feed = feed;
    this.jdbc = jdbc;
    this.publicIds = publicIds;
    this.json = json;
    this.settings = settings;
    this.executor = executor;
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
  }

  // ------------------------------------------------------------------------------------------- events

  @EventListener
  public void on(ExecutionCreated e) {
    async(() -> recordExecution(e.executionId()));
  }

  @EventListener
  public void on(CashTransferPosted e) {
    async(() -> recordTransfer(e.transferId()));
  }

  @EventListener
  public void on(AccountChanged e) {
    String reason = e.reason() == null ? "" : e.reason().toUpperCase(Locale.ROOT);
    if (reason.startsWith("RESET")) {
      async(() -> recordReset(e.playerId(), e.at()));
    } else if (reason.startsWith("OPEN")) {
      async(() -> recordOpening(e.accountId()));      // WS3 publishes no CashTransferPosted for an INITIAL
    }
  }

  @EventListener
  public void on(JournalNotePosted e) {
    async(() -> recordJournal(e.noteId()));
  }

  private void async(Runnable work) {
    try {
      executor.execute(() -> {
        try {
          work.run();
        } catch (RuntimeException ex) {
          log.warn("Feed event not recorded: {}", ex.toString());
        }
      });
    } catch (RuntimeException ex) {
      log.warn("Feed executor rejected an event: {}", ex.toString());
    }
  }

  // ------------------------------------------------------------------------------------------- writers

  private record ExecRow(long id, String publicId, long accountId, long playerId, String symbol, Side side,
                         BigDecimal qty, BigDecimal price, BigDecimal realizedPnl, Instant executedAt,
                         String rationale, boolean rationalePublic) {}

  /** FILL or POSITION_CLOSED for one execution (by public id); true if a row was written. */
  public boolean recordExecution(String executionPublicId) {
    String ref = "EXEC:" + executionPublicId;
    if (feed.exists(ref)) {
      return false;
    }
    Optional<ExecRow> found = jdbc.sql("""
            SELECT e.id, e.public_id, e.account_id, a.player_id, e.symbol, e.side, e.qty, e.price, e.realized_pnl,
                   e.executed_at, o.rationale, o.rationale_public
              FROM execution e JOIN account a ON a.id = e.account_id JOIN trade_order o ON o.id = e.order_id
             WHERE e.public_id = ?""")
        .param(executionPublicId)
        .query((rs, n) -> new ExecRow(rs.getLong("id"), rs.getString("public_id"), rs.getLong("account_id"),
            rs.getLong("player_id"), rs.getString("symbol"), Side.valueOf(rs.getString("side")),
            rs.getBigDecimal("qty"), rs.getBigDecimal("price"), rs.getBigDecimal("realized_pnl"),
            Jdbc.instant(rs, "executed_at"), rs.getString("rationale"), rs.getBoolean("rationale_public")))
        .optional();
    if (found.isEmpty()) {
      return false;
    }
    ExecRow x = found.get();
    boolean closed = x.side() == Side.SELL && positionAfter(x).signum() <= 0;
    String rationale = x.rationalePublic() ? Text.clip(x.rationale(), PublicProfileService.RATIONALE_MAX) : null;
    FeedPayloadDto payload = new FeedPayloadDto(x.side(), x.qty(), x.price(),
        x.side() == Side.SELL ? x.realizedPnl() : null, null, null,
        rationale == null ? null : new UntrustedFeedTextDto(rationale, null, null));
    return feed.insertIfAbsent(publicIds.next(Prefix.FEED_EVENT), x.playerId(), x.accountId(),
        closed ? FeedItemKind.POSITION_CLOSED : FeedItemKind.FILL, x.symbol(), json.writeValueAsString(payload),
        ref, x.executedAt(), x.executedAt().plus(settings.tradeFeedDelay()));
  }

  /** The account's quantity of the symbol right after this execution (executions + adjustments up to it). */
  private BigDecimal positionAfter(ExecRow x) {
    BigDecimal fills = jdbc.sql("""
            SELECT COALESCE(SUM(CASE WHEN side = 'BUY' THEN qty ELSE -qty END), 0) FROM execution
             WHERE account_id = ? AND symbol = ? AND (executed_at < ? OR (executed_at = ? AND id <= ?))""")
        .params(x.accountId(), x.symbol(), Jdbc.ts(x.executedAt()), Jdbc.ts(x.executedAt()), x.id())
        .query(BigDecimal.class).single();
    BigDecimal adjustments = jdbc.sql("""
            SELECT COALESCE(SUM(CASE WHEN symbol_after = ? THEN qty_after ELSE 0 END)
                          - SUM(CASE WHEN symbol_before = ? THEN qty_before ELSE 0 END), 0)
              FROM position_adjustment WHERE account_id = ? AND effective_at <= ?""")
        .params(x.symbol(), x.symbol(), x.accountId(), Jdbc.ts(x.executedAt()))
        .query(BigDecimal.class).single();
    return fills.add(adjustments == null ? BigDecimal.ZERO : adjustments);
  }

  private record TransferInfo(String publicId, long accountId, long playerId, String playerPublicId, int runNo,
                              TransferType type, BigDecimal amount, String memo, Instant effectiveAt,
                              boolean applied) {}

  /** DEPOSIT (deposits and withdrawals, when shown) or JOINED (first run's INITIAL); true if a row was written. */
  public boolean recordTransfer(String transferPublicId) {
    Optional<TransferInfo> found = jdbc.sql("""
            SELECT t.public_id, t.account_id, a.player_id, p.public_id AS player_public_id, a.run_no,
                   t.transfer_type, t.amount, t.memo, t.effective_at, t.status
              FROM cash_transfer t JOIN account a ON a.id = t.account_id JOIN player p ON p.id = a.player_id
             WHERE t.public_id = ?""")
        .param(transferPublicId)
        .query((rs, n) -> new TransferInfo(rs.getString("public_id"), rs.getLong("account_id"),
            rs.getLong("player_id"), rs.getString("player_public_id"), rs.getInt("run_no"),
            TransferType.valueOf(rs.getString("transfer_type")), rs.getBigDecimal("amount"), rs.getString("memo"),
            Jdbc.instant(rs, "effective_at"), "APPLIED".equals(rs.getString("status"))))
        .optional();
    if (found.isEmpty() || !found.get().applied()) {
      return false;
    }
    TransferInfo t = found.get();
    if (t.type() == TransferType.INITIAL) {
      if (t.runNo() != 1) {
        return false;                                // a reset's INITIAL: the RESET event covers it
      }
      FeedPayloadDto payload = new FeedPayloadDto(null, null, null, null, null, null, null);
      return feed.insertIfAbsent(publicIds.next(Prefix.FEED_EVENT), t.playerId(), t.accountId(), FeedItemKind.JOINED,
          null, json.writeValueAsString(payload), "JOIN:" + t.playerPublicId(), t.effectiveAt(), t.effectiveAt());
    }
    if ((t.type() != TransferType.DEPOSIT && t.type() != TransferType.WITHDRAWAL) || !settings.showDepositsInFeed()) {
      return false;
    }
    String memo = Text.clip(t.memo(), MEMO_MAX);
    FeedPayloadDto payload = new FeedPayloadDto(null, null, null, null, t.amount(), null,
        memo == null ? null : new UntrustedFeedTextDto(null, memo, null));
    return feed.insertIfAbsent(publicIds.next(Prefix.FEED_EVENT), t.playerId(), t.accountId(), FeedItemKind.DEPOSIT,
        null, json.writeValueAsString(payload), "XFER:" + t.publicId(), t.effectiveAt(), t.effectiveAt());
  }

  /** JOINED for an account's opening (its INITIAL transfer; only run 1 yields a row); true if a row was written. */
  public boolean recordOpening(long accountId) {
    return jdbc.sql("""
            SELECT public_id FROM cash_transfer
             WHERE account_id = ? AND transfer_type = 'INITIAL' AND status = 'APPLIED' ORDER BY id LIMIT 1""")
        .param(accountId).query(String.class).optional()
        .map(this::recordTransfer).orElse(false);
  }

  /** RESET, keyed by the player's new ACTIVE run (both runs' events map to the same row). */
  public boolean recordReset(long playerId, Instant at) {
    Optional<ActiveRun> active = jdbc.sql("SELECT id, public_id, run_no FROM account WHERE player_id = ? "
            + "AND status = 'ACTIVE'")
        .param(playerId)
        .query((rs, n) -> new ActiveRun(rs.getLong("id"), rs.getString("public_id"), rs.getInt("run_no")))
        .optional();
    if (active.isEmpty() || active.get().runNo() <= 1) {
      return false;
    }
    FeedPayloadDto payload = new FeedPayloadDto(null, null, null, null, null, null, null);
    return feed.insertIfAbsent(publicIds.next(Prefix.FEED_EVENT), playerId, active.get().id(), FeedItemKind.RESET,
        null, json.writeValueAsString(payload), "RESET:" + active.get().publicId(), at, at);
  }

  private record ActiveRun(long id, String publicId, int runNo) {}

  /** NEW_LEADER of {@code period} on {@code date} (one per date). */
  public boolean recordNewLeader(LocalDate date, long playerId, LeaderboardPeriod period, Instant at) {
    FeedPayloadDto payload = new FeedPayloadDto(null, null, null, null, null, period.name(), null);
    return feed.insertIfAbsent(publicIds.next(Prefix.FEED_EVENT), playerId, null, FeedItemKind.NEW_LEADER, null,
        json.writeValueAsString(payload), "LEADER:" + date, at, at);
  }

  private record JournalRow(long playerId, String publicId, String body, String symbols, Instant createdAt,
                            Instant visibleAt) {}

  /** JOURNAL for one AI journal note (by public id, §20.7); true if a row was written. */
  public boolean recordJournal(String notePublicId) {
    String ref = "JNL:" + notePublicId;
    if (feed.exists(ref)) {
      return false;
    }
    Optional<JournalRow> found = jdbc.sql("""
            SELECT player_id, public_id, body, symbols, created_at, visible_at FROM journal_note WHERE public_id = ?""")
        .param(notePublicId)
        .query((rs, n) -> new JournalRow(rs.getLong("player_id"), rs.getString("public_id"), rs.getString("body"),
            rs.getString("symbols"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "visible_at")))
        .optional();
    if (found.isEmpty()) {
      return false;
    }
    JournalRow j = found.get();
    String note = Text.clip(j.body(), PublicProfileService.RATIONALE_MAX);
    String symbol = j.symbols() == null || j.symbols().isBlank() ? null : j.symbols().split(",")[0].strip();
    FeedPayloadDto payload = new FeedPayloadDto(null, null, null, null, null, null,
        new UntrustedFeedTextDto(null, null, note));
    return feed.insertIfAbsent(publicIds.next(Prefix.FEED_EVENT), j.playerId(), null, FeedItemKind.JOURNAL, symbol,
        json.writeValueAsString(payload), ref, j.createdAt(), j.visibleAt());
  }

  /**
   * Re-records every execution, transfer and (Phase 2) journal note in {@code (fromExclusive, toInclusive]}; returns
   * rows written.
   */
  public int backfill(Instant fromExclusive, Instant toInclusive) {
    int n = 0;
    List<String> executions = jdbc.sql("SELECT public_id FROM execution WHERE executed_at > ? AND executed_at <= ? "
            + "ORDER BY executed_at, id")
        .params(Jdbc.ts(fromExclusive), Jdbc.ts(toInclusive)).query(String.class).list();
    for (String id : executions) {
      n += recordExecution(id) ? 1 : 0;
    }
    List<String> transfers = jdbc.sql("SELECT public_id FROM cash_transfer WHERE status = 'APPLIED' "
            + "AND effective_at > ? AND effective_at <= ? ORDER BY effective_at, id")
        .params(Jdbc.ts(fromExclusive), Jdbc.ts(toInclusive)).query(String.class).list();
    for (String id : transfers) {
      n += recordTransfer(id) ? 1 : 0;
    }
    List<String> notes = jdbc.sql("SELECT public_id FROM journal_note WHERE created_at > ? AND created_at <= ? "
            + "ORDER BY created_at, id")
        .params(Jdbc.ts(fromExclusive), Jdbc.ts(toInclusive)).query(String.class).list();
    for (String id : notes) {
      n += recordJournal(id) ? 1 : 0;
    }
    return n;
  }
}
