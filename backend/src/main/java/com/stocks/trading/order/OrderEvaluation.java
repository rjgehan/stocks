package com.stocks.trading.order;

import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.Side;
import com.stocks.common.money.Money;
import com.stocks.common.money.Qty;
import com.stocks.common.time.Et;
import com.stocks.contract.calendar.SessionState;
import com.stocks.trading.account.GfvService;
import com.stocks.trading.config.RulesProperties;
import com.stocks.trading.config.TradingSettings;
import com.stocks.trading.store.AccountRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Everything an order is checked against, in one place, so preview (request thread, committed state) and
 * {@code PlaceOrderCmd} (engine thread, the engine's state) evaluate identically (§11.2): session acceptance (§11.3),
 * {@code session_date}/{@code expires_at} (§11.4), the account and market snapshots, the GFV prediction on
 * {@code session_date} (§10.4) and checks 7–27.
 */
@Component
public class OrderEvaluation {

  /** A valid order with its acceptance, expiry and validation result. */
  public record Evaluated(SessionOrderMatrix.Acceptance acceptance, ExpiryCalculator.Expiry expiry,
                          OrderValidator.Result result, OrderValidator.MarketSnapshot market) {}

  private final TradingClock clock;
  private final RulesProperties rules;
  private final OrderContext context;
  private final OrderValidator validator;
  private final HoldCalculator holds;
  private final GfvService gfv;
  private final TradingSettings settings;

  public OrderEvaluation(TradingClock clock, RulesProperties rules, OrderContext context, OrderValidator validator,
                         HoldCalculator holds, GfvService gfv, TradingSettings settings) {
    this.clock = clock;
    this.rules = rules;
    this.context = context;
    this.validator = validator;
    this.holds = holds;
    this.gfv = gfv;
    this.settings = settings;
  }

  /** Throws the first failing check as an {@code ApiException}. */
  public Evaluated evaluate(AccountRow acc, PlayerKind kind, NormalizedOrder o, Instant now) {
    OrderValidator.requireShape(o);
    SessionState session = clock.session(now);
    var acceptance = SessionOrderMatrix.accept(clock.calendar(), o.type(), o.extendedHours(), o.timeInForce(),
        session, now);
    var expiry = ExpiryCalculator.compute(clock.calendar(), rules, now, o.extendedHours(), o.timeInForce());
    LocalDate today = Et.date(now);
    var market = context.market(o.symbol(), today);
    var snapshot = context.account(acc, o.symbol(), today, -1);
    boolean gfvRisk = false;
    if (o.side() == Side.SELL && snapshot.positionQty() != null && market.asset() != null) {
      BigDecimal available = snapshot.positionQty().subtract(snapshot.reservedSellQty()).max(Qty.ZERO);
      BigDecimal sellQty = o.qty() != null ? o.qty() : holds.sellReservedQty(o, available, market.reference());
      gfvRisk = gfv.predict(acc.id(), o.symbol(), sellQty, snapshot.reservedSellQty(), expiry.sessionDate());
    }
    var result = validator.validate(new OrderValidator.Input(o, kind, settings.botsRequireRationale(), market,
        snapshot, acceptance, expiry.sessionDate(), gfvRisk, Money.ZERO, now));
    return new Evaluated(acceptance, expiry, result, market);
  }
}
