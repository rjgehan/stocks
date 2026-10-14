package com.stocks.admin;

import com.stocks.audit.AuditQueries;
import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.error.ApiException;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.admin.dto.AdminPlayerDetailDto;
import com.stocks.contract.admin.dto.AdminPlayerDto;
import com.stocks.contract.admin.dto.EodHoldingDto;
import com.stocks.contract.portfolio.AccountPnl;
import com.stocks.contract.portfolio.PerformanceQueries;
import com.stocks.contract.portfolio.Valuation;
import com.stocks.contract.portfolio.ValuationService;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.CashTransferService;
import com.stocks.contract.trading.TransferFilter;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.RunDto;
import com.stocks.contract.trading.dto.TransferRowDto;
import com.stocks.identity.ApiKeyService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Builds the §21.1 admin views of players. Identity fields come from {@code player}; the account columns
 * ({@code accountId}, {@code runNo}, cash, net contributions, restriction) and the counts of open orders and live
 * keys are read-only lookups of the owning tables; {@code equity} comes from WS4's {@link ValuationService} and
 * {@code pnl}/{@code return} from {@link PerformanceQueries#accountPnl} (run-relative, §16.2). While those
 * contracts are still Day-0 stubs, the valued fields are null ({@code pnl} falls back to
 * {@code equity − netContributions} when only the valuation exists).
 *
 * <p>The detail never contains live positions or orders; live data: GET /admin/players/{h}/portfolio (public mode,
 * §16.7, §21.3).
 */
@Component
public class AdminPlayerViews {

  private static final Logger log = LoggerFactory.getLogger(AdminPlayerViews.class);
  static final int RECENT_AUDIT = 25;
  static final int RECENT_TRANSFERS = 25;

  /** One {@code player} row joined with its ACTIVE account (if any). */
  public record PlayerRow(long id, String publicId, String handle, String displayName, PlayerKind kind, String aiModel,
                          String avatarSeed, PlayerRole role, PlayerStatus status, String ownerHandle,
                          boolean apiTradingAllowed, Instant lastSeenAt, Instant createdAt, Long accountId,
                          String accountPublicId, int runNo, int maxRun, BigDecimal cash, BigDecimal netContributions,
                          AccountRestriction restriction, int openOrders, int activeKeys) {}

  private static final String SELECT = """
      SELECT p.id, p.public_id, p.handle, p.display_name, p.kind, p.ai_model, p.avatar_seed, p.role, p.status,
             p.api_trading_allowed, p.last_seen_at, p.created_at, o.handle AS owner_handle,
             a.id AS account_id, a.public_id AS account_public_id, a.run_no, a.cash_balance, a.net_contributions,
             a.restriction,
             (SELECT MAX(x.run_no) FROM account x WHERE x.player_id = p.id) AS max_run,
             (SELECT COUNT(*) FROM trade_order t
               WHERE t.account_id = a.id AND t.status IN ('PENDING','WORKING','PARTIALLY_FILLED')) AS open_orders,
             (SELECT COUNT(*) FROM api_key k
               WHERE k.player_id = p.id AND k.revoked_at IS NULL AND (k.expires_at IS NULL OR k.expires_at > ?))
               AS active_keys
        FROM player p
        LEFT JOIN player o ON o.id = p.owner_player_id
        LEFT JOIN account a ON a.player_id = p.id AND a.status = 'ACTIVE'""";

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final ValuationService valuations;
  private final PerformanceQueries performance;
  private final AccountQueries accountQueries;
  private final CashTransferService transfers;
  private final ApiKeyService apiKeys;
  private final AuditQueries audit;

  public AdminPlayerViews(JdbcClient jdbc, TimeSource time, ValuationService valuations,
                          PerformanceQueries performance, AccountQueries accountQueries, CashTransferService transfers,
                          ApiKeyService apiKeys, AuditQueries audit) {
    this.jdbc = jdbc;
    this.time = time;
    this.valuations = valuations;
    this.performance = performance;
    this.accountQueries = accountQueries;
    this.transfers = transfers;
    this.apiKeys = apiKeys;
    this.audit = audit;
  }

  // ------------------------------------------------------------------------------------------- rows

  public List<PlayerRow> rows(PlayerKind kindOrNull, PlayerStatus statusOrNull) {
    StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
    List<Object> params = new ArrayList<>();
    params.add(Jdbc.ts(time.now()));
    if (kindOrNull != null) {
      sql.append(" AND p.kind = ?");
      params.add(kindOrNull.name());
    }
    if (statusOrNull != null) {
      sql.append(" AND p.status = ?");
      params.add(statusOrNull.name());
    }
    sql.append(" ORDER BY p.handle");
    return jdbc.sql(sql.toString()).params(params).query(AdminPlayerViews::row).list();
  }

  public Optional<PlayerRow> row(long playerId) {
    return jdbc.sql(SELECT + " WHERE p.id = ?").params(Jdbc.ts(time.now()), playerId)
        .query(AdminPlayerViews::row).optional();
  }

  // ------------------------------------------------------------------------------------------- DTOs

  /** {@code GET /admin/players}: every player matching the filters, valued with one valuation pass. */
  public List<AdminPlayerDto> list(PlayerKind kindOrNull, PlayerStatus statusOrNull) {
    List<PlayerRow> rows = rows(kindOrNull, statusOrNull);
    Map<Long, Valuation> values = rows.stream().anyMatch(r -> r.accountId() != null) ? allValuations() : Map.of();
    return rows.stream().map(r -> dto(r, r.accountId() == null ? null : values.get(r.accountId()))).toList();
  }

  /** One player (valued on its own). */
  public AdminPlayerDto player(long playerId) {
    PlayerRow r = row(playerId).orElseThrow();
    return dto(r, r.accountId() == null ? null : valuation(r.accountId()));
  }

  /** {@code GET /admin/players/{handle}} (§21.1): no live positions or orders. */
  public AdminPlayerDetailDto detail(long playerId) {
    PlayerRow r = row(playerId).orElseThrow();
    AdminPlayerDto player = dto(r, r.accountId() == null ? null : valuation(r.accountId()));
    List<RunDto> runs = soft(() -> accountQueries.runs(playerId), List.of());
    AccountDto balances = r.accountId() == null ? null : soft(() -> accountQueries.account(r.accountId()), null);
    Optional<LocalDate> holdingsAsOf = r.accountId() == null ? Optional.empty() : latestSnapshotDate(r.accountId());
    List<EodHoldingDto> holdings = holdingsAsOf.map(d -> eodHoldings(r.accountId(), d)).orElse(List.of());
    List<TransferRowDto> rows = soft(() -> transfers.list(new TransferFilter(r.handle(), null, null, null), null,
        RECENT_TRANSFERS).items(), List.of());
    return new AdminPlayerDetailDto(player, r.apiTradingAllowed(), runs, balances, holdingsAsOf.orElse(null),
        holdings, rows, apiKeys.list(playerId), audit.recentFor(playerId, r.publicId(), r.handle(), RECENT_AUDIT));
  }

  AdminPlayerDto dto(PlayerRow r, Valuation v) {
    BigDecimal equity = v == null ? null : v.equity();
    BigDecimal pnl = null;
    BigDecimal ret = null;
    if (r.accountId() != null) {
      AccountPnl accountPnl = soft(() -> performance.accountPnl(r.accountId()), null);
      if (accountPnl != null) {
        pnl = accountPnl.totalPnl();
        ret = accountPnl.totalReturn();
      } else if (equity != null && r.netContributions() != null) {
        pnl = equity.subtract(r.netContributions());
      }
    }
    return new AdminPlayerDto(r.publicId(), r.handle(), r.displayName(), r.kind(), r.aiModel(), r.avatarSeed(),
        r.createdAt(), Math.max(0, r.maxRun() - 1), r.role(), r.status(), r.ownerHandle(), r.accountPublicId(),
        r.runNo(), equity, r.cash(), r.netContributions(), pnl, ret, r.openOrders(), r.activeKeys(),
        r.restriction() == null ? AccountRestriction.NONE : r.restriction(), r.lastSeenAt(), r.createdAt());
  }

  private Map<Long, Valuation> allValuations() {
    return soft(valuations::valueAllActive, Map.of());
  }

  private Valuation valuation(long accountId) {
    return soft(() -> valuations.valueNow(accountId), null);
  }

  private Optional<LocalDate> latestSnapshotDate(long accountId) {
    return jdbc.sql("SELECT MAX(trade_date) FROM snapshot_holding WHERE account_id = ?").param(accountId)
        .query((rs, n) -> Optional.ofNullable(Jdbc.date(rs, 1))).list().stream()
        .flatMap(Optional::stream).findFirst();
  }

  private List<EodHoldingDto> eodHoldings(long accountId, LocalDate tradeDate) {
    return jdbc.sql("""
            SELECT symbol, qty, mark, market_value FROM snapshot_holding
             WHERE account_id = ? AND trade_date = ? ORDER BY market_value DESC, symbol""")
        .params(accountId, tradeDate)
        .query((rs, n) -> new EodHoldingDto(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
            rs.getBigDecimal(4)))
        .list();
  }

  /**
   * A value from another workstream's contract for a read-only view: any failure gives the fallback (logged), so one
   * broken figure never hides the whole admin page.
   */
  static <T> T soft(java.util.function.Supplier<T> call, T fallback) {
    try {
      return call.get();
    } catch (ApiException e) {
      log.debug("Admin view: {} -> fallback", e.toString());
      return fallback;
    } catch (RuntimeException e) {
      log.warn("Admin view: {} -> fallback", e.toString());
      return fallback;
    }
  }

  static PlayerRow row(ResultSet rs, int n) throws SQLException {
    long accountId = rs.getLong("account_id");
    boolean hasAccount = !rs.wasNull();
    String restriction = rs.getString("restriction");
    return new PlayerRow(rs.getLong("id"), rs.getString("public_id"), rs.getString("handle"),
        rs.getString("display_name"), PlayerKind.valueOf(rs.getString("kind")), rs.getString("ai_model"),
        rs.getString("avatar_seed"), PlayerRole.valueOf(rs.getString("role")),
        PlayerStatus.valueOf(rs.getString("status")), rs.getString("owner_handle"),
        rs.getBoolean("api_trading_allowed"), Jdbc.instant(rs, "last_seen_at"), Jdbc.instant(rs, "created_at"),
        hasAccount ? accountId : null, rs.getString("account_public_id"), hasAccount ? rs.getInt("run_no") : 0,
        rs.getInt("max_run"), rs.getBigDecimal("cash_balance"), rs.getBigDecimal("net_contributions"),
        restriction == null ? null : AccountRestriction.valueOf(restriction), rs.getInt("open_orders"),
        rs.getInt("active_keys"));
  }
}
