package com.stocks.trading.order;

import com.stocks.common.domain.Crypto;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
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
import java.util.List;
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
    return evaluate(acc, kind, o, now, false);
  }

  /**
   * As {@link #evaluate(AccountRow, PlayerKind, NormalizedOrder, Instant)}; a {@code preview} does not require an AI
   * player's rationale (§20.3 {@code preview_order}: "{@code client_order_id} and {@code rationale} optional"), whose
   * length is still checked.
   */
  public Evaluated evaluate(AccountRow acc, PlayerKind kind, NormalizedOrder o, Instant now, boolean preview) {
    return evaluate(acc, kind, o, now, preview, 0);
  }

  /**
   * One live leg of a group (§11.9): as a single order, except that check 22 also counts the group's other
   * {@code extraOpenOrders} legs ({@code open + legs ≤ maxOpenOrders}).
   */
  public Evaluated evaluate(AccountRow acc, PlayerKind kind, NormalizedOrder o, Instant now, boolean preview,
                            int extraOpenOrders) {
    OrderValidator.requireShape(o);
    requireCryptoOpen(o);
    SessionState session = clock.session(o.symbol(), now);
    SessionOrderMatrix.Acceptance acceptance;
    ApiException sessionRejection = null;
    try {
      acceptance = SessionOrderMatrix.accept(clock.calendar(o.symbol()), o.type(), o.extendedHours(),
          o.timeInForce(), session, now);
    } catch (ApiException e) {
      if (e.code() != ErrorCode.SESSION_NOT_ALLOWED) {
        throw e;
      }
      // a trailing stop outside REGULAR: rejected at check 18, so checks 7–17 still win (§11.2 row order)
      sessionRejection = e;
      acceptance = new SessionOrderMatrix.Acceptance(now, WaitReason.LATENCY, false, List.of());
    }
    var expiry = ExpiryCalculator.compute(clock.calendar(o.symbol()), rules, now, o.extendedHours(),
        o.timeInForce());
    LocalDate today = Et.date(now);
    var market = context.market(o.symbol(), today);
    var snapshot = withExtraOpen(context.account(acc, o.symbol(), today, -1), extraOpenOrders);
    boolean gfvRisk = false;
    if (o.side() == Side.SELL && snapshot.positionQty() != null && market.asset() != null) {
      BigDecimal available = snapshot.positionQty().subtract(snapshot.reservedSellQty()).max(Qty.ZERO);
      BigDecimal sellQty = o.qty() != null ? o.qty() : holds.sellReservedQty(o, available, market.reference());
      gfvRisk = gfv.predict(acc.id(), o.symbol(), sellQty, snapshot.reservedSellQty(), expiry.sessionDate());
    }
    boolean requireRationale = !preview && settings.botsRequireRationale();
    var result = validator.validate(new OrderValidator.Input(o, kind, requireRationale, market, snapshot, acceptance,
        expiry.sessionDate(), gfvRisk, Money.ZERO, now, sessionRejection));
    return new Evaluated(acceptance, expiry, result, market);
  }

  /**
   * A held group child (§11.9): checks 7–20 against the group's {@code entryPrice} as the reference (a stop loss must
   * be below the entry, not below today's market), nothing reserved. Its acceptance and expiry are provisional: both
   * are computed again when its entry releases it.
   */
  public Evaluated evaluateHeldChild(AccountRow acc, PlayerKind kind, NormalizedOrder o, Instant now, boolean preview,
                                     BigDecimal entryPrice) {
    OrderValidator.requireShape(o);
    var expiry = ExpiryCalculator.compute(clock.calendar(o.symbol()), rules, now, false, o.timeInForce());
    LocalDate today = Et.date(now);
    var live = context.market(o.symbol(), today);
    var market = new OrderValidator.MarketSnapshot(live.asset(), live.corporateActionHold(), live.quote(), entryPrice);
    var snapshot = context.account(acc, o.symbol(), today, -1);
    var acceptance = new SessionOrderMatrix.Acceptance(now, WaitReason.AWAITING_PARENT, false, List.of());
    boolean requireRationale = !preview && settings.botsRequireRationale();
    var result = validator.validateHeldChild(new OrderValidator.Input(o, kind, requireRationale, market, snapshot,
        acceptance, expiry.sessionDate(), false, Money.ZERO, now, null));
    return new Evaluated(acceptance, expiry, result, live);
  }

  /** §29: while {@code crypto.enabled} is off, crypto buys are refused; sells still close positions. */
  private void requireCryptoOpen(NormalizedOrder o) {
    if (o.side() == Side.BUY && Crypto.isCrypto(o.symbol()) && !settings.cryptoEnabled()) {
      throw ApiException.of(ErrorCode.SYMBOL_NOT_TRADABLE, "Crypto trading is turned off; " + o.symbol()
              + " can only be sold.")
          .withHint("The admin turned crypto off (Admin → Settings). Existing crypto positions can still be sold.")
          .withField("symbol", ErrorCode.SYMBOL_NOT_TRADABLE, "Crypto is off");
    }
  }

  private static OrderValidator.AccountSnapshot withExtraOpen(OrderValidator.AccountSnapshot s, int extra) {
    if (extra == 0) {
      return s;
    }
    return new OrderValidator.AccountSnapshot(s.accountId(), s.restriction(), s.balances(), s.openOrders() + extra,
        s.positionQty(), s.reservedSellQty());
  }
}
