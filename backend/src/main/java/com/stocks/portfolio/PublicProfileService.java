package com.stocks.portfolio;

import com.stocks.common.domain.HoldingsVisibility;
import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardMetric;
import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TransferType;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.money.Money;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.common.web.Text;
import com.stocks.contract.portfolio.LeaderboardService;
import com.stocks.contract.portfolio.Valuation;
import com.stocks.contract.portfolio.ValuationService;
import com.stocks.contract.portfolio.dto.LeaderboardDto;
import com.stocks.contract.portfolio.dto.LeaderboardRowDto;
import com.stocks.contract.portfolio.dto.PlayerProfileDto;
import com.stocks.contract.portfolio.dto.PlayerProfileDto.DepositsSummaryDto;
import com.stocks.contract.portfolio.dto.PlayerProfileDto.ProfileHoldingDto;
import com.stocks.contract.portfolio.dto.PlayerProfileDto.ProfileStatDto;
import com.stocks.contract.portfolio.dto.PlayerProfileDto.ProfileStatsDto;
import com.stocks.contract.portfolio.dto.PublicTradeDto;
import com.stocks.contract.portfolio.dto.PublicTradeDto.UntrustedRationaleDto;
import com.stocks.portfolio.EodSnapshot.HoldingRow;
import com.stocks.portfolio.MetricsCalculator.Flow;
import com.stocks.portfolio.PlayerDirectory.PlayerRow;
import com.stocks.portfolio.PortfolioRepository.AccountRow;
import com.stocks.portfolio.PortfolioRepository.TransferRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Public player data (§16.7 charter, §18.2 {@code /players/{handle}}, {@code /players/{handle}/trades}): only what
 * every player may see.
 * <ul>
 *   <li>Trades appear only after {@code social.tradeFeedDelay} (15 min), labeled "via API" when an API key or MCP
 *       placed them; a rationale is public only when {@code trade_order.rationale_public} (AI, or a human's shared
 *       note), clipped to 280 characters and wrapped in {@code untrusted} (§17.8).</li>
 *   <li>Holdings are previous-EOD <b>weights</b> ({@code qty} only with {@code EOD_FULL}); with {@code HIDDEN} neither
 *       holdings nor equity are shown.</li>
 *   <li>Working orders and live positions are never shown.</li>
 * </ul>
 */
@Component
public class PublicProfileService {

  /** {@code channel} values of orders placed by a key (§16.7 "via API"). */
  public static final Set<String> API_CHANNELS = Set.of("REST_KEY", "MCP_KEY", "MCP_OAUTH");
  /** §17.8: rationales reach other players trimmed to 280 characters. */
  public static final int RATIONALE_MAX = 280;
  static final int RECENT_TRADES = 10;
  static final int WEIGHT_SCALE = 4;

  private final PlayerDirectory players;
  private final PortfolioRepository repository;
  private final EodSnapshotRepository snapshots;
  private final ValuationService valuation;
  private final LeaderboardService leaderboard;
  private final LeagueSettings settings;
  private final JdbcClient jdbc;
  private final TimeSource time;

  public PublicProfileService(PlayerDirectory players, PortfolioRepository repository,
                              EodSnapshotRepository snapshots, ValuationService valuation,
                              LeaderboardService leaderboard, LeagueSettings settings, JdbcClient jdbc,
                              TimeSource time) {
    this.players = players;
    this.repository = repository;
    this.snapshots = snapshots;
    this.valuation = valuation;
    this.leaderboard = leaderboard;
    this.settings = settings;
    this.jdbc = jdbc;
    this.time = time;
  }

  /** A listed (ACTIVE or FROZEN) player, else {@code PLAYER_NOT_FOUND}. */
  public PlayerRow player(String handle) {
    return players.byHandle(handle).filter(PlayerRow::listed)
        .orElseThrow(() -> ApiException.of(ErrorCode.PLAYER_NOT_FOUND, "No player '" + Text.clip(handle, 40) + "'."));
  }

  public PlayerProfileDto profile(String handle, Long viewerPlayerId) {
    PlayerRow p = player(handle);
    HoldingsVisibility visibility = settings.holdingsVisibility();
    boolean hidden = visibility == HoldingsVisibility.HIDDEN;
    List<AccountRow> runs = repository.runsOfPlayer(p.id());
    Optional<AccountRow> active = runs.stream().filter(AccountRow::active).findFirst();
    String ownerHandle = p.ownerPlayerId() == null ? null
        : players.byId(p.ownerPlayerId()).map(PlayerRow::handle).orElse(null);
    ProfileStatsDto stats = new ProfileStatsDto(stat(LeaderboardPeriod.TODAY, p, viewerPlayerId),
        stat(LeaderboardPeriod.WEEK, p, viewerPlayerId), stat(LeaderboardPeriod.MONTH, p, viewerPlayerId),
        stat(LeaderboardPeriod.YTD, p, viewerPlayerId), stat(LeaderboardPeriod.ALL, p, viewerPlayerId));
    Valuation v = active.map(a -> valuation.valueNow(a.id())).orElse(null);
    List<TransferRow> transfers = repository.appliedTransfersOfPlayer(p.id());
    int deposits = 0;
    BigDecimal depositTotal = Money.ZERO;
    List<Flow> flows = new ArrayList<>();
    for (TransferRow t : transfers) {
      if (t.type() == TransferType.DEPOSIT) {
        deposits++;
        depositTotal = depositTotal.add(t.amount());
      }
      if (t.contribution()) {
        flows.add(new Flow(t.effectiveAt(), t.amount()));
      }
    }
    TradeTotals totals = tradeTotals(p.id());
    BigDecimal mwr = null;
    if (!runs.isEmpty() && v != null) {
      BigDecimal pnlAll = v.equity().subtract(v.netContributions());
      for (AccountRow r : runs) {
        if (!r.active() && r.finalEquity() != null) {
          pnlAll = pnlAll.add(r.finalEquity().subtract(r.netContributions()));
        }
      }
      Instant a = transfers.isEmpty() ? runs.get(0).openedAt() : transfers.get(0).effectiveAt();
      mwr = MetricsCalculator.moneyWeightedReturn(pnlAll, Money.ZERO, flows, a, v.asOf());
    }
    List<ProfileHoldingDto> holdings = List.of();
    LocalDate holdingsAsOf = null;
    if (!hidden && active.isPresent()) {
      Optional<LocalDate> latest = snapshots.latestDate(active.get().id());
      if (latest.isPresent()) {
        EodSnapshot s = snapshots.find(active.get().id(), latest.get()).orElseThrow();
        holdingsAsOf = latest.get();
        holdings = weights(s, snapshots.holdings(s.accountId(), s.tradeDate()),
            visibility == HoldingsVisibility.EOD_FULL);
      }
    }
    return new PlayerProfileDto(p.summary(), ownerHandle, active.map(AccountRow::runNo).orElse(0), stats,
        hidden || v == null ? null : v.equity(), v == null ? Money.ZERO : v.netContributions(),
        new DepositsSummaryDto(deposits, depositTotal), totals.fills, totals.winRate(), mwr, holdingsAsOf, holdings,
        publicTrades(p, null, RECENT_TRADES).items());
  }

  /** Weights of the EOD holdings in that day's equity (cash is the rest), largest first. */
  static List<ProfileHoldingDto> weights(EodSnapshot s, List<HoldingRow> rows, boolean withQty) {
    List<ProfileHoldingDto> out = new ArrayList<>();
    for (HoldingRow h : rows) {
      BigDecimal w = s.equity().signum() > 0
          ? h.marketValue().divide(s.equity(), WEIGHT_SCALE, RoundingMode.HALF_EVEN) : BigDecimal.ZERO;
      out.add(new ProfileHoldingDto(h.symbol(), w, withQty ? h.qty() : null));
    }
    return out;
  }

  private ProfileStatDto stat(LeaderboardPeriod period, PlayerRow p, Long viewer) {
    LeaderboardDto board = leaderboard.board(period, LeaderboardMetric.RETURN, KindFilter.ALL, 100, viewer);
    for (LeaderboardRowDto row : board.rows()) {
      if (row.player().playerId().equals(p.publicId())) {
        return new ProfileStatDto(row.ret(), row.pnl(), row.rank());
      }
    }
    return new ProfileStatDto(BigDecimal.ZERO.setScale(MetricsCalculator.RETURN_SCALE), Money.ZERO, null);
  }

  private record TradeTotals(int fills, int sells, int wins) {
    BigDecimal winRate() {
      return MetricsCalculator.winRate(wins, sells);
    }
  }

  /** All-time filled orders, closing sells and winning sells across the player's runs. */
  private TradeTotals tradeTotals(long playerId) {
    int fills = jdbc.sql("""
            SELECT COUNT(*) FROM trade_order o JOIN account a ON a.id = o.account_id
             WHERE a.player_id = ? AND o.filled_qty > 0""")
        .param(playerId).query(Integer.class).single();
    int[] sw = new int[2];
    jdbc.sql("""
            SELECT COUNT(*) AS sells, COALESCE(SUM(CASE WHEN e.realized_pnl > 0 THEN 1 ELSE 0 END), 0) AS wins
              FROM execution e JOIN account a ON a.id = e.account_id
             WHERE a.player_id = ? AND e.side = 'SELL' AND e.realized_pnl IS NOT NULL""")
        .param(playerId)
        .query(rs -> {
          sw[0] = rs.getInt("sells");
          sw[1] = rs.getInt("wins");
        });
    return new TradeTotals(fills, sw[0], sw[1]);
  }

  // ------------------------------------------------------------------------------------------- trades

  public Page<PublicTradeDto> publicTrades(String handle, Cursor cursor, int limit) {
    return publicTrades(player(handle), cursor, limit);
  }

  /** The player's executions older than the feed delay, newest first ({@code (executed_at, id)} cursor). */
  public Page<PublicTradeDto> publicTrades(PlayerRow p, Cursor cursor, int limit) {
    int n = Math.max(1, Math.min(limit, Page.MAX_LIMIT));
    Instant visibleBefore = time.now().minus(settings.tradeFeedDelay());
    List<Object> params = new ArrayList<>(List.of(p.id(), Jdbc.ts(visibleBefore)));
    String page = "";
    if (cursor != null) {
      page = " AND (e.executed_at < ? OR (e.executed_at = ? AND e.id < ?))";
      params.add(Jdbc.ts(cursor.at()));
      params.add(Jdbc.ts(cursor.at()));
      params.add(cursor.id());
    }
    params.add(n + 1);
    String sql = "SELECT e.id, e.executed_at, e.symbol, e.side, e.qty, e.price, e.realized_pnl, o.channel, "
        + "o.rationale, o.rationale_public FROM execution e JOIN trade_order o ON o.id = e.order_id "
        + "JOIN account a ON a.id = e.account_id WHERE a.player_id = ? AND e.executed_at <= ?" + page
        + " ORDER BY e.executed_at DESC, e.id DESC LIMIT ?";
    List<TradeRow> rows = jdbc.sql(sql).params(params).query(PublicProfileService::trade).list();
    return Page.fromLookahead(rows, n, r -> Cursor.of(r.executedAt, r.id)).map(TradeRow::dto);
  }

  private record TradeRow(long id, Instant executedAt, String symbol, Side side, BigDecimal qty, BigDecimal price,
                          BigDecimal realizedPnl, boolean viaApi, String rationale) {
    PublicTradeDto dto() {
      return new PublicTradeDto(executedAt, symbol, side, qty, price, realizedPnl, viaApi,
          new UntrustedRationaleDto(rationale));
    }
  }

  private static TradeRow trade(ResultSet rs, int n) throws SQLException {
    String rationale = rs.getBoolean("rationale_public") ? Text.clip(rs.getString("rationale"), RATIONALE_MAX) : null;
    return new TradeRow(rs.getLong("id"), Jdbc.instant(rs, "executed_at"), rs.getString("symbol"),
        Side.valueOf(rs.getString("side")), rs.getBigDecimal("qty"), rs.getBigDecimal("price"),
        rs.getBigDecimal("realized_pnl"), API_CHANNELS.contains(rs.getString("channel")), rationale);
  }
}
