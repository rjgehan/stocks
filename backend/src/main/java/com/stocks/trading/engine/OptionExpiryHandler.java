package com.stocks.trading.engine;

import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.Channel;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.Liquidity;
import com.stocks.common.domain.OptionSymbol;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.events.OrderChanged;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.money.Money;
import com.stocks.common.money.Prices;
import com.stocks.common.money.Rounding;
import com.stocks.common.time.Et;
import com.stocks.contract.marketdata.ExecQuote;
import com.stocks.trading.engine.EngineCommand.SettleOptionExpiryCmd;
import com.stocks.trading.engine.write.OrderWriter;
import com.stocks.trading.store.OrderRow;
import com.stocks.trading.store.OrderStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * §30 expiration of long options ({@link SettleOptionExpiryCmd}, engine thread, one transaction): every open order on
 * the contract is cancelled ({@code OPTION_EXPIRED}); then each account still holding it gets a SYSTEM SELL order
 * filled at the contract's intrinsic value from the underlying's official close, liquidity {@code EXPIRY}:
 * {@code max(0, close − strike)} for a call, {@code max(0, strike − close)} for a put, × 100 × contracts in cash,
 * settling T+1 like any sale. An out-of-the-money contract "sells" at 0.00, so the position closes at a realized loss
 * of its cost. No shares are delivered, and a closed (reset) run keeps its final holdings untouched. The order's
 * client order id {@code expiry:<symbol>} makes the settlement once-only per account, and an account that no longer
 * holds the contract is skipped, so repeated or catch-up runs change nothing.
 */
@Component
public class OptionExpiryHandler {

  /** What one settlement did. {@code cash}: the total credited (0.00 when everything expired worthless). */
  public record Result(String symbol, BigDecimal underlyingClose, BigDecimal intrinsic, int accounts,
                       BigDecimal contracts, BigDecimal cash, int cancelledOrders) {}

  private record Holder(long accountId, long playerId, BigDecimal qty) {}

  private final EngineOrderOps ops;
  private final OrderWriter writer;
  private final OrderStore orders;
  private final PostingService posting;
  private final PublicId publicIds;
  private final JdbcClient jdbc;
  private final JsonMapper json;

  public OptionExpiryHandler(EngineOrderOps ops, OrderWriter writer, OrderStore orders, PostingService posting,
                             PublicId publicIds, JdbcClient jdbc, JsonMapper json) {
    this.ops = ops;
    this.writer = writer;
    this.orders = orders;
    this.posting = posting;
    this.publicIds = publicIds;
    this.jdbc = jdbc;
    this.json = json;
  }

  Result settle(SettleOptionExpiryCmd cmd, Instant now, EngineTx tx) {
    EngineThread.assertCurrent();
    OptionSymbol.Contract c = OptionSymbol.parse(cmd.symbol())
        .orElseThrow(() -> new IllegalArgumentException("not an option symbol: " + cmd.symbol()));
    if (cmd.underlyingClose() == null || cmd.underlyingClose().signum() <= 0) {
      throw new IllegalArgumentException("no underlying close for " + cmd.symbol());
    }
    if (Et.date(now).isBefore(c.expiration())) {      // OptionExpiryJob runs only after the expiration day's close
      throw new IllegalStateException(cmd.symbol() + " has not expired yet");
    }
    BigDecimal intrinsic = c.intrinsic(cmd.underlyingClose()).setScale(Prices.SCALE, RoundingMode.HALF_EVEN);
    int cancelled = ops.cancelAllOnSymbol(c.symbol(), CancelReason.OPTION_EXPIRED, 0, now, tx).size();
    ExecQuote ref = new ExecQuote(c.symbol(), null, 0, null, 0, cmd.underlyingClose(), now, now,
        DataQuality.DELAYED_15M, "option-expiry", false);
    int accounts = 0;
    BigDecimal contracts = BigDecimal.ZERO;
    BigDecimal cash = Money.ZERO;
    for (Holder h : holders(c.symbol())) {
      String clientOrderId = "expiry:" + c.symbol();
      if (orders.byClientOrderId(h.accountId(), clientOrderId).isPresent()) {
        continue;                                    // settled already (the position was re-read in the same tx)
      }
      String publicId = publicIds.next(Prefix.ORDER);
      String note = (intrinsic.signum() > 0 ? "Expired in the money: cash-settled at $" + money(intrinsic)
          : "Expired worthless") + " (" + c.underlying() + " closed at $" + money(cmd.underlyingClose()) + " on "
          + c.expiration() + ", strike $" + money(c.strike()) + ")";
      long orderId = writer.insert(new OrderWriter.NewOrder(publicId, h.accountId(), h.playerId(), clientOrderId,
          "expiry", c.symbol(), Side.SELL, OrderType.MARKET, h.qty(), null, false, null, TimeInForce.DAY, false, null,
          Money.ZERO, h.qty(), c.expiration(), now, now, now, now, null, Channel.SYSTEM, null, null, null, null, note,
          false));
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("reason", "OPTION_EXPIRY");
      detail.put("underlyingClose", cmd.underlyingClose());
      detail.put("strike", c.strike());
      detail.put("intrinsic", intrinsic);
      writer.event(orderId, OrderEventType.ACCEPTED, OrderStatus.PENDING, now, json.writeValueAsString(detail));
      writer.activate(orderId, now);
      writer.event(orderId, OrderEventType.WORKING, OrderStatus.WORKING, now, null);
      tx.publish(new OrderChanged(h.accountId(), h.playerId(), publicId, OrderEventType.ACCEPTED,
          OrderStatus.PENDING, now));
      OrderRow row = orders.byId(orderId).orElseThrow();
      PostingService.Outcome out = posting.post(row, List.of(new FillModel.Leg(h.qty(), intrinsic, Liquidity.EXPIRY)),
          ref, now, tx);
      if (out.status() != OrderStatus.FILLED) {
        throw new IllegalStateException("expiry of " + c.symbol() + " for account " + h.accountId() + " ended "
            + out.status());
      }
      accounts++;
      contracts = contracts.add(h.qty());
      cash = cash.add(Rounding.principal(c.symbol(), Side.SELL, h.qty(), intrinsic));
    }
    return new Result(c.symbol(), cmd.underlyingClose(), intrinsic, accounts, contracts, cash, cancelled);
  }

  private List<Holder> holders(String symbol) {
    return jdbc.sql("""
            SELECT p.account_id, a.player_id, p.qty FROM position p JOIN account a ON a.id = p.account_id
             WHERE p.symbol = ? AND p.qty > 0 AND a.status = 'ACTIVE' ORDER BY p.account_id""")
        .param(symbol)
        .query((rs, n) -> new Holder(rs.getLong("account_id"), rs.getLong("player_id"), rs.getBigDecimal("qty")))
        .list();
  }

  private static String money(BigDecimal v) {
    return v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
  }
}
