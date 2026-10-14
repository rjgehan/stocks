package com.stocks.league;

import com.stocks.common.domain.ActivityType;
import com.stocks.common.domain.Channel;
import com.stocks.common.domain.HistoryRange;
import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.domain.OrderGroupType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.money.Money;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.common.web.Text;
import com.stocks.contract.portfolio.PnlAnalysisQueries;
import com.stocks.contract.portfolio.PublicPortfolioQueries;
import com.stocks.contract.portfolio.dto.PnlAnalysisDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto.CalendarView;
import com.stocks.contract.portfolio.dto.PublicOrderDto;
import com.stocks.contract.portfolio.dto.PublicOrderDto.PublicFillDto;
import com.stocks.contract.portfolio.dto.PublicPortfolioDto;
import com.stocks.contract.portfolio.dto.PublicTradeDto.UntrustedRationaleDto;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.ActivityFilter;
import com.stocks.contract.trading.OrderStatusFilter;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.ActivityDetailDto;
import com.stocks.contract.trading.dto.ActivityDto;
import com.stocks.contract.trading.dto.ActorKind;
import com.stocks.contract.trading.dto.OrderGroupRefDto;
import com.stocks.contract.trading.dto.PositionDto;
import com.stocks.contract.trading.dto.ResetDetailDto;
import com.stocks.contract.trading.dto.RestrictionDetailDto;
import com.stocks.portfolio.PlayerDirectory;
import com.stocks.portfolio.PlayerDirectory.PlayerRow;
import com.stocks.portfolio.PublicProfileService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * {@link PublicPortfolioQueries} (public mode, §16.7): any listed player's live portfolio, every stored order with its
 * fills, the active run's activity and P&amp;L, for {@code /players/{handle}/…} and the MCP {@code get_player_*}
 * tools. Nothing is delayed. Never exposed: client order ids, account ids, API key and OAuth client ids, the MCP
 * client name, IPs, per-order holds, admin and player memos and restriction reasons (system ones are kept).
 * {@code ADMIN_VIEW} activity rows are left out. The rationale is the full stored text when
 * {@code trade_order.rationale_public} (every order since public mode), else null; MCP clips it (§17.8).
 *
 * <p>A leaf bean: it lives in {@code league} because it fronts {@code portfolio} and the account contract, and it is
 * never injected into {@code PerformanceQueriesImpl} or {@code AccountQueriesImpl} (which depends on
 * {@code PerformanceQueries}), so no bean cycle can form.
 */
@Service
public class PublicPortfolioService implements PublicPortfolioQueries {

  /** {@code trade_order.mcp_client} of a manual-console order (§20.8); group 1 is the session id. */
  static final Pattern MANUAL_CLIENT = Pattern.compile("^manual-console (mbs_[0-9A-HJKMNP-TV-Z]{26})$");
  static final String OPEN_STATUSES = "('PENDING','WORKING','PARTIALLY_FILLED')";
  /** Every activity type the public sees: {@code ADMIN_VIEW} (historical admin break-glass rows) never. */
  static final Set<ActivityType> PUBLIC_ACTIVITY = Collections.unmodifiableSet(
      EnumSet.complementOf(EnumSet.of(ActivityType.ADMIN_VIEW)));

  /** Activity types whose memo only the system writes (D5: admin and player memos stay private). */
  static final Set<ActivityType> SYSTEM_MEMO = Collections.unmodifiableSet(
      EnumSet.of(ActivityType.DIVIDEND, ActivityType.CASH_IN_LIEU));

  private static final String ORDER_SELECT = """
      SELECT o.id, o.public_id, a.run_no, o.symbol, o.side, o.order_type, o.qty, o.notional, o.fractional,
             o.limit_price, o.stop_price, o.trail_amount, o.trail_percent, o.time_in_force, o.extended_hours,
             o.status, o.wait_reason, o.status_reason, o.filled_qty, o.avg_fill_price, o.fees_total, o.received_at,
             o.expires_at, o.done_at, o.cancel_requested_at, o.channel, o.mcp_client, o.rationale, o.rationale_public,
             o.group_role, o.held, g.public_id AS group_public_id, g.group_type, po.public_id AS parent_public_id,
             ro.public_id AS replaces_public_id, rb.public_id AS replaced_by_public_id
        FROM trade_order o JOIN account a ON a.id = o.account_id
        LEFT JOIN order_group g ON g.id = o.group_id
        LEFT JOIN trade_order po ON po.id = o.parent_order_id
        LEFT JOIN trade_order ro ON ro.id = o.replaces_order_id
        LEFT JOIN trade_order rb ON rb.id = o.replaced_by_order_id
      """;

  private final PlayerDirectory players;
  private final AccountQueries accounts;
  private final PnlAnalysisQueries pnl;
  private final JdbcClient jdbc;
  private final TimeSource time;

  public PublicPortfolioService(PlayerDirectory players, AccountQueries accounts, PnlAnalysisQueries pnl,
                                JdbcClient jdbc, TimeSource time) {
    this.players = players;
    this.accounts = accounts;
    this.pnl = pnl;
    this.jdbc = jdbc;
    this.time = time;
  }

  // ------------------------------------------------------------------------------------------- players

  /** A listed (ACTIVE or FROZEN) player, else {@code PLAYER_NOT_FOUND} (the profile's message). */
  private PlayerRow listed(String handle) {
    return players.byHandle(handle).filter(PlayerRow::listed).orElseThrow(() -> notFound(handle));
  }

  private static ApiException notFound(String handle) {
    return ApiException.of(ErrorCode.PLAYER_NOT_FOUND, "No player '" + Text.clip(handle, 40) + "'.");
  }

  /** The player's active account id, or null without an active run. */
  private Long activeAccount(PlayerRow p) {
    try {
      return accounts.activeAccountId(p.id());
    } catch (ApiException e) {
      if (e.code() == ErrorCode.NOT_FOUND || e.code() == ErrorCode.ACCOUNT_NOT_FOUND) {
        return null;
      }
      throw e;
    }
  }

  private long requireActiveAccount(PlayerRow p) {
    Long id = activeAccount(p);
    if (id == null) {
      throw ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "@" + p.handle() + " has no active run.");
    }
    return id;
  }

  // ------------------------------------------------------------------------------------------- portfolio

  @Override
  public PublicPortfolioDto portfolio(String handle) {
    return portfolio(listed(handle));
  }

  @Override
  public PublicPortfolioDto portfolioAnyStatus(String handle) {
    return portfolio(players.byHandle(handle).orElseThrow(() -> notFound(handle)));
  }

  private PublicPortfolioDto portfolio(PlayerRow p) {
    Long accountId = activeAccount(p);
    if (accountId == null) {
      BigDecimal z = Money.ZERO;
      return new PublicPortfolioDto(p.summary(), 0, time.now(), null, z, z, z, z, z, z, z, z, z, z, null, z, null, z,
          null, null, 0, List.of(), List.of());
    }
    AccountDto a = accounts.account(accountId);
    List<PositionDto> positions = new ArrayList<>(accounts.positions(accountId));
    positions.sort(Comparator.comparing(PositionDto::marketValue, Comparator.nullsLast(Comparator.reverseOrder())));
    BigDecimal unrealized = Money.ZERO;
    for (PositionDto pos : positions) {
      if (pos.unrealizedPnl() != null) {
        unrealized = unrealized.add(pos.unrealizedPnl());
      }
    }
    List<PublicOrderDto> open = orderRows("o.account_id = ? AND o.status IN " + OPEN_STATUSES,
        List.of(accountId), null);
    return new PublicPortfolioDto(p.summary(), a.runNo(), a.asOf(), a.marksQuality(), a.equity(),
        a.longMarketValue(), a.dividendsReceivable(), a.cash().total(), a.cash().settled(), a.cash().unsettled(),
        a.buyingPower(), a.openBuyHolds(), a.netContributions(), a.totalPnl(), a.totalReturn(), a.dayPnl(),
        a.dayReturn(), unrealized.setScale(Money.SCALE, RoundingMode.HALF_EVEN), a.restriction(),
        a.restrictionUntil(), a.gfv() == null ? 0 : a.gfv().active(), positions, open);
  }

  // ------------------------------------------------------------------------------------------- orders

  @Override
  public Page<PublicOrderDto> orders(String handle, OrderStatusFilter status, String symbolOrNull, Cursor cursor,
                                     int limit) {
    PlayerRow p = listed(handle);
    int n = Math.max(1, Math.min(limit, Page.MAX_LIMIT));
    StringBuilder where = new StringBuilder("a.player_id = ?");
    List<Object> params = new ArrayList<>(List.of(p.id()));
    OrderStatusFilter s = status == null ? OrderStatusFilter.ALL : status;
    if (s == OrderStatusFilter.OPEN) {
      where.append(" AND o.status IN ").append(OPEN_STATUSES);
    } else if (s == OrderStatusFilter.CLOSED) {
      where.append(" AND o.status NOT IN ").append(OPEN_STATUSES);
    }
    String symbol = symbolOrNull == null || symbolOrNull.isBlank() ? null
        : symbolOrNull.trim().toUpperCase(Locale.ROOT);
    if (symbol != null) {
      where.append(" AND o.symbol = ?");
      params.add(symbol);
    }
    if (cursor != null) {
      where.append(" AND (o.received_at < ? OR (o.received_at = ? AND o.id < ?))");
      params.add(Jdbc.ts(cursor.at()));
      params.add(Jdbc.ts(cursor.at()));
      params.add(cursor.id());
    }
    List<OrderRow> rows = query(where.toString(), params, n + 1);
    Page<OrderRow> page = Page.fromLookahead(rows, n, r -> Cursor.of(r.receivedAt(), r.id()));
    Map<Long, List<PublicFillDto>> fills = fills(page.items());
    return page.map(r -> r.dto(fills.getOrDefault(r.id(), List.of())));
  }

  /** The orders matching {@code where} (newest first), with their fills; no limit when {@code limit} is null. */
  private List<PublicOrderDto> orderRows(String where, List<Object> params, Integer limit) {
    List<OrderRow> rows = query(where, params, limit);
    Map<Long, List<PublicFillDto>> fills = fills(rows);
    return rows.stream().map(r -> r.dto(fills.getOrDefault(r.id(), List.of()))).toList();
  }

  private List<OrderRow> query(String where, List<Object> params, Integer limit) {
    List<Object> all = new ArrayList<>(params);
    String sql = ORDER_SELECT + " WHERE " + where + " ORDER BY o.received_at DESC, o.id DESC";
    if (limit != null) {
      sql += " LIMIT ?";
      all.add(limit);
    }
    return jdbc.sql(sql).params(all).query(PublicPortfolioService::order).list();
  }

  /** The fills of {@code orders} by order id, oldest first; fees = SEC + TAF + CAT. */
  private Map<Long, List<PublicFillDto>> fills(List<OrderRow> orders) {
    Map<Long, List<PublicFillDto>> out = new LinkedHashMap<>();
    if (orders.isEmpty()) {
      return out;
    }
    List<Long> ids = orders.stream().map(OrderRow::id).toList();
    String in = String.join(",", Collections.nCopies(ids.size(), "?"));
    jdbc.sql("SELECT public_id, order_id, executed_at, qty, price, fee_sec + fee_taf + fee_cat AS fees, "
            + "realized_pnl FROM execution WHERE order_id IN (" + in + ") ORDER BY executed_at, id")
        .params(new ArrayList<Object>(ids))
        .query(rs -> {
          out.computeIfAbsent(rs.getLong("order_id"), k -> new ArrayList<>()).add(new PublicFillDto(
              rs.getString("public_id"), Jdbc.instant(rs, "executed_at"), qtyOut(rs.getBigDecimal("qty")),
              rs.getBigDecimal("price"), rs.getBigDecimal("fees"), rs.getBigDecimal("realized_pnl")));
        });
    return out;
  }

  private record OrderRow(long id, String publicId, int runNo, String symbol, Side side, OrderType type,
                          BigDecimal qty, BigDecimal notional, boolean fractional, BigDecimal limitPrice,
                          BigDecimal stopPrice, BigDecimal trailAmount, BigDecimal trailPercent, TimeInForce tif,
                          boolean extendedHours, OrderStatus status, WaitReason waitReason, String statusReason,
                          BigDecimal filledQty, BigDecimal avgFillPrice, BigDecimal fees, Instant receivedAt,
                          Instant expiresAt, Instant doneAt, Channel channel, String manualSessionId,
                          String rationale, OrderGroupRefDto group, String replacesPublicId,
                          String replacedByPublicId) {

    PublicOrderDto dto(List<PublicFillDto> fills) {
      return new PublicOrderDto(publicId, runNo, symbol, side, type, qty, notional, fractional, limitPrice, stopPrice,
          trailAmount, trailPercent, tif, extendedHours, status, waitReason, statusReason, filledQty, avgFillPrice,
          fees, receivedAt, expiresAt, doneAt, replacesPublicId, replacedByPublicId, group, channel,
          PublicProfileService.API_CHANNELS.contains(channel.name()), manualSessionId, fills,
          new UntrustedRationaleDto(rationale));
    }
  }

  private static OrderRow order(ResultSet rs, int n) throws SQLException {
    OrderStatus stored = OrderStatus.valueOf(rs.getString("status"));
    // a user cancel waiting out the cancel latency (§11.10) shows PENDING_CANCEL, as OrderDto does
    OrderStatus status = stored.isOpen() && Jdbc.instant(rs, "cancel_requested_at") != null
        ? OrderStatus.PENDING_CANCEL : stored;
    String wait = rs.getString("wait_reason");
    Channel channel = Channel.valueOf(rs.getString("channel"));
    String groupRole = rs.getString("group_role");
    OrderGroupRefDto group = groupRole == null ? null
        : new OrderGroupRefDto(rs.getString("group_public_id"), OrderGroupType.valueOf(rs.getString("group_type")),
            OrderGroupRole.valueOf(groupRole), rs.getString("parent_public_id"),
            rs.getBoolean("held") && stored.isOpen());
    return new OrderRow(rs.getLong("id"), rs.getString("public_id"), rs.getInt("run_no"), rs.getString("symbol"),
        Side.valueOf(rs.getString("side")), OrderType.valueOf(rs.getString("order_type")),
        qtyOut(rs.getBigDecimal("qty")), rs.getBigDecimal("notional"), rs.getBoolean("fractional"),
        rs.getBigDecimal("limit_price"), rs.getBigDecimal("stop_price"), rs.getBigDecimal("trail_amount"),
        rs.getBigDecimal("trail_percent"), TimeInForce.valueOf(rs.getString("time_in_force")),
        rs.getBoolean("extended_hours"), status, wait == null ? null : WaitReason.valueOf(wait),
        rs.getString("status_reason"), qtyOut(rs.getBigDecimal("filled_qty")), rs.getBigDecimal("avg_fill_price"),
        rs.getBigDecimal("fees_total"), Jdbc.instant(rs, "received_at"), Jdbc.instant(rs, "expires_at"),
        Jdbc.instant(rs, "done_at"), channel, manualSessionId(channel, rs.getString("mcp_client")),
        rs.getBoolean("rationale_public") ? rs.getString("rationale") : null, group,
        rs.getString("replaces_public_id"), rs.getString("replaced_by_public_id"));
  }

  /**
   * The manual-console session (§20.8) of a WEB order whose {@code mcp_client} names one, else null; an order from
   * any other channel never gets one (an MCP client cannot fake it). The raw client name is never returned.
   */
  static String manualSessionId(Channel channel, String mcpClient) {
    if (channel != Channel.WEB || mcpClient == null) {
      return null;
    }
    Matcher m = MANUAL_CLIENT.matcher(mcpClient);
    return m.matches() ? m.group(1) : null;
  }

  /** Quantities on the wire: trailing zeros stripped ({@code "0.95274"}, {@code "10"}), as {@code Order} does. */
  private static BigDecimal qtyOut(BigDecimal qty) {
    if (qty == null) {
      return null;
    }
    BigDecimal s = qty.stripTrailingZeros();
    return s.scale() < 0 ? s.setScale(0) : s;
  }

  // ------------------------------------------------------------------------------------------- activity

  @Override
  public Page<ActivityDto> activities(String handle, ActivityFilter filter, Cursor cursor, int limit) {
    PlayerRow p = listed(handle);
    Long accountId = activeAccount(p);
    if (accountId == null) {
      return Page.empty();
    }
    Set<ActivityType> requested = filter == null || filter.typesOrEmpty() == null || filter.typesOrEmpty().isEmpty()
        ? EnumSet.allOf(ActivityType.class) : EnumSet.copyOf(filter.typesOrEmpty());
    requested.retainAll(PUBLIC_ACTIVITY);
    if (requested.isEmpty()) {
      return Page.empty();
    }
    ActivityFilter f = new ActivityFilter(requested, filter == null ? null : filter.fromOrNull(),
        filter == null ? null : filter.toOrNull());
    return accounts.activities(accountId, f, cursor, limit).map(PublicPortfolioService::sanitize);
  }

  /**
   * The public shape of an activity row: memos only on the types whose memo the system always writes (an
   * INITIAL_FUNDING row is SYSTEM too, but after a reset it carries the resetting admin's or player's note); a reset
   * without account ids or memo; a restriction reason only when the system set it.
   */
  static ActivityDto sanitize(ActivityDto a) {
    boolean system = a.by() == ActorKind.SYSTEM;
    boolean systemMemo = system && SYSTEM_MEMO.contains(a.type());
    ActivityDetailDto detail = a.detail();
    if (detail instanceof ResetDetailDto r) {
      detail = new ResetDetailDto(r.side(), null, null, r.runNo(), r.finalEquity(), r.startingCash(), null);
    } else if (detail instanceof RestrictionDetailDto r && !system) {
      detail = new RestrictionDetailDto(r.from(), r.to(), r.until(), null);
    }
    return new ActivityDto(a.id(), a.at(), a.type(), a.symbol(), a.side(), a.qty(), a.price(), a.fees(), a.amount(),
        a.balanceAfter(), a.tradeDate(), a.settleDate(), systemMemo ? a.memo() : null, a.by(), a.orderId(),
        a.executionId(), a.transferId(), detail);
  }

  // ------------------------------------------------------------------------------------------- P&L

  @Override
  public PnlAnalysisDto pnlAnalysis(String handle, HistoryRange range) {
    long accountId = requireActiveAccount(listed(handle));
    return pnl.analysis(accountId, range == null ? HistoryRange.M1 : range);
  }

  @Override
  public PnlCalendarDto pnlCalendar(String handle, CalendarView view, YearMonth monthOrNull, Integer yearOrNull) {
    long accountId = requireActiveAccount(listed(handle));
    return pnl.calendar(accountId, view == null ? CalendarView.DAY : view, monthOrNull, yearOrNull);
  }
}
