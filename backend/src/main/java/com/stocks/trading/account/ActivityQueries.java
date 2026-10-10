package com.stocks.trading.account;

import com.stocks.common.domain.ActivityType;
import com.stocks.common.domain.LedgerEntryType;
import com.stocks.common.domain.Side;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.Et;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.trading.ActivityFilter;
import com.stocks.contract.trading.dto.ActivityDetailDto;
import com.stocks.contract.trading.dto.ActivityDto;
import com.stocks.contract.trading.dto.ActorKind;
import com.stocks.contract.trading.dto.FeesDto;
import com.stocks.contract.trading.dto.NameChangeDetailDto;
import com.stocks.contract.trading.dto.ResetDetailDto;
import com.stocks.contract.trading.dto.SplitDetailDto;
import com.stocks.trading.order.OrderValidator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /account/activities} (§18.2): {@code cash_ledger} rows (fills joined to their execution, transfers,
 * dividends, cash in lieu) merged with {@code account_event} rows, newest first by
 * {@code (occurred_at DESC, source, id DESC)} with source {@code E} before {@code L}. The opaque cursor carries that
 * triple: {@code Cursor.id = rowId × 2 + (source == L ? 1 : 0)}.
 */
@Component
public class ActivityQueries {

  private static final Set<ActivityType> LEDGER_TYPES = EnumSet.of(ActivityType.FILL, ActivityType.INITIAL_FUNDING,
      ActivityType.DEPOSIT, ActivityType.WITHDRAWAL, ActivityType.ADJUSTMENT, ActivityType.DIVIDEND,
      ActivityType.CASH_IN_LIEU);

  /** One merged row with its sort key. */
  private record Row(String source, long id, Instant at, ActivityDto dto) {}

  private static final Comparator<Row> ORDER = Comparator.comparing(Row::at).reversed()
      .thenComparing(Row::source).thenComparing(Comparator.comparingLong(Row::id).reversed());

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public ActivityQueries(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Page<ActivityDto> page(long accountId, ActivityFilter filter, Cursor cursor, int limit) {
    int n = Page.clampLimit(limit);
    Set<ActivityType> types = filter == null || filter.typesOrEmpty() == null || filter.typesOrEmpty().isEmpty()
        ? EnumSet.allOf(ActivityType.class) : EnumSet.copyOf(filter.typesOrEmpty());
    LocalDate from = filter == null ? null : filter.fromOrNull();
    LocalDate to = filter == null ? null : filter.toOrNull();
    String cSrc = cursor == null ? null : (cursor.id() % 2 == 1 ? "L" : "E");
    long cId = cursor == null ? 0 : cursor.id() / 2;
    List<Row> rows = new ArrayList<>();
    Set<String> ledgerTypes = ledgerEntryTypes(types);
    if (!ledgerTypes.isEmpty()) {
      rows.addAll(ledger(accountId, ledgerTypes, from, to, cursor, cSrc, cId, n + 1));
    }
    Set<String> eventTypes = types.stream().filter(t -> !LEDGER_TYPES.contains(t)).map(Enum::name)
        .collect(Collectors.toSet());
    if (!eventTypes.isEmpty()) {
      rows.addAll(events(accountId, eventTypes, from, to, cursor, cSrc, cId, n + 1));
    }
    rows.sort(ORDER);
    List<Row> head = rows.size() > n + 1 ? rows.subList(0, n + 1) : rows;
    return Page.fromLookahead(head, n, r -> Cursor.of(r.at(), r.id() * 2 + ("L".equals(r.source()) ? 1 : 0)))
        .map(Row::dto);
  }

  private static Set<String> ledgerEntryTypes(Set<ActivityType> types) {
    Set<String> out = new java.util.HashSet<>();
    for (ActivityType t : types) {
      if (t == ActivityType.FILL) {
        out.add(LedgerEntryType.TRADE_BUY.name());
        out.add(LedgerEntryType.TRADE_SELL.name());
      } else if (LEDGER_TYPES.contains(t)) {
        out.add(t.name());
      }
    }
    return out;
  }

  private static String in(Set<String> values) {
    return values.stream().sorted().map(v -> "'" + v + "'").collect(Collectors.joining(","));
  }

  private List<Row> ledger(long accountId, Set<String> entryTypes, LocalDate from, LocalDate to, Cursor cursor,
                           String cSrc, long cId, int max) {
    StringBuilder sql = new StringBuilder("""
        SELECT l.id, l.public_id, l.entry_type, l.amount, l.balance_after, l.trade_date, l.settle_date, l.posted_at,
               l.symbol, l.memo, e.public_id AS exe_id, e.side, e.qty, e.price, e.fee_sec, e.fee_taf, e.fee_cat,
               o.public_id AS ord_id, t.public_id AS xfr_id
          FROM cash_ledger l
          LEFT JOIN execution e ON e.id = l.execution_id
          LEFT JOIN trade_order o ON o.id = e.order_id
          LEFT JOIN cash_transfer t ON t.id = l.transfer_id
         WHERE l.account_id = ? AND l.entry_type IN (""").append(in(entryTypes)).append(")");
    List<Object> params = new ArrayList<>();
    params.add(accountId);
    if (from != null) {
      sql.append(" AND l.trade_date >= ?");
      params.add(from);
    }
    if (to != null) {
      sql.append(" AND l.trade_date <= ?");
      params.add(to);
    }
    if (cursor != null) {
      if ("E".equals(cSrc)) {
        sql.append(" AND l.posted_at <= ?");
        params.add(Jdbc.ts(cursor.at()));
      } else {
        sql.append(" AND (l.posted_at < ? OR (l.posted_at = ? AND l.id < ?))");
        params.add(Jdbc.ts(cursor.at()));
        params.add(Jdbc.ts(cursor.at()));
        params.add(cId);
      }
    }
    sql.append(" ORDER BY l.posted_at DESC, l.id DESC LIMIT ?");
    params.add(max);
    return jdbc.sql(sql.toString()).params(params).query((rs, i) -> {
      LedgerEntryType type = LedgerEntryType.valueOf(rs.getString("entry_type"));
      boolean fill = type == LedgerEntryType.TRADE_BUY || type == LedgerEntryType.TRADE_SELL;
      FeesDto fees = null;
      if (fill) {
        BigDecimal sec = rs.getBigDecimal("fee_sec");
        BigDecimal taf = rs.getBigDecimal("fee_taf");
        BigDecimal cat = rs.getBigDecimal("fee_cat");
        fees = new FeesDto(sec, taf, cat, sec.add(taf).add(cat));
      }
      String side = rs.getString("side");
      Instant at = Jdbc.instant(rs, "posted_at");
      ActivityDto dto = new ActivityDto(rs.getString("public_id"), at, activityType(type), rs.getString("symbol"),
          side == null ? null : Side.valueOf(side), OrderValidator.qtyOut(rs.getBigDecimal("qty")),
          rs.getBigDecimal("price"), fees, rs.getBigDecimal("amount"), rs.getBigDecimal("balance_after"),
          Jdbc.date(rs, "trade_date"), Jdbc.date(rs, "settle_date"), rs.getString("memo"), actor(type),
          rs.getString("ord_id"), rs.getString("exe_id"), rs.getString("xfr_id"), null);
      return new Row("L", rs.getLong("id"), at, dto);
    }).list();
  }

  private List<Row> events(long accountId, Set<String> eventTypes, LocalDate from, LocalDate to, Cursor cursor,
                           String cSrc, long cId, int max) {
    StringBuilder sql = new StringBuilder("""
        SELECT id, public_id, event_type, occurred_at, symbol, actor_kind, payload FROM account_event
         WHERE account_id = ? AND event_type IN (""").append(in(eventTypes)).append(")");
    List<Object> params = new ArrayList<>();
    params.add(accountId);
    if (from != null) {
      sql.append(" AND occurred_at >= ?");
      params.add(Jdbc.ts(Et.startOfDay(from)));
    }
    if (to != null) {
      sql.append(" AND occurred_at < ?");
      params.add(Jdbc.ts(Et.startOfDay(to.plusDays(1))));
    }
    if (cursor != null) {
      if ("E".equals(cSrc)) {
        sql.append(" AND (occurred_at < ? OR (occurred_at = ? AND id < ?))");
        params.add(Jdbc.ts(cursor.at()));
        params.add(Jdbc.ts(cursor.at()));
        params.add(cId);
      } else {
        sql.append(" AND occurred_at < ?");
        params.add(Jdbc.ts(cursor.at()));
      }
    }
    sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ?");
    params.add(max);
    return jdbc.sql(sql.toString()).params(params).query((rs, i) -> {
      Instant at = Jdbc.instant(rs, "occurred_at");
      ActivityDetailDto detail = json.readValue(rs.getString("payload"), ActivityDetailDto.class);
      BigDecimal qty = switch (detail) {
        case SplitDetailDto s -> s.qtyAfter();
        case NameChangeDetailDto c -> c.qty();
        default -> null;
      };
      String memo = detail instanceof ResetDetailDto r ? r.memo() : null;
      ActivityDto dto = new ActivityDto(rs.getString("public_id"), at,
          ActivityType.valueOf(rs.getString("event_type")), rs.getString("symbol"), null, qty, null, null, null, null,
          Et.date(at), null, memo, ActorKind.valueOf(rs.getString("actor_kind")), null, null, null, detail);
      return new Row("E", rs.getLong("id"), at, dto);
    }).list();
  }

  static ActivityType activityType(LedgerEntryType t) {
    return switch (t) {
      case TRADE_BUY, TRADE_SELL -> ActivityType.FILL;
      case INITIAL_FUNDING -> ActivityType.INITIAL_FUNDING;
      case DEPOSIT -> ActivityType.DEPOSIT;
      case WITHDRAWAL -> ActivityType.WITHDRAWAL;
      case ADJUSTMENT -> ActivityType.ADJUSTMENT;
      case DIVIDEND -> ActivityType.DIVIDEND;
      case CASH_IN_LIEU -> ActivityType.CASH_IN_LIEU;
    };
  }

  static ActorKind actor(LedgerEntryType t) {
    return switch (t) {
      case TRADE_BUY, TRADE_SELL -> ActorKind.SELF;
      case DEPOSIT, WITHDRAWAL, ADJUSTMENT -> ActorKind.ADMIN;
      case INITIAL_FUNDING, DIVIDEND, CASH_IN_LIEU -> ActorKind.SYSTEM;
    };
  }
}
