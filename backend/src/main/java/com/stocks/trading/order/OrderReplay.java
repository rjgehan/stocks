package com.stocks.trading.order;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.money.Prices;
import com.stocks.common.time.Et;
import com.stocks.contract.trading.dto.EstimateDto;
import com.stocks.contract.trading.dto.FeesDto;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.trading.order.mapper.OrderDtoMapper;
import com.stocks.trading.store.AccountStore;
import com.stocks.trading.store.OrderRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Order idempotency (§11.7): a repeated {@code clientOrderId} with the same request hash replays the stored order
 * ({@code replayed: true}, HTTP 200); with a different hash it is {@code 409 CLIENT_ORDER_ID_CONFLICT}. Used by the
 * request-thread fast path and by the authoritative check inside {@code PlaceOrderCmd}.
 */
@Component
public class OrderReplay {

  public static final Pattern CLIENT_ORDER_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

  private final OrderDtoMapper mapper;
  private final AccountStore accounts;
  private final OrderContext context;
  private final TradingClock clock;

  public OrderReplay(OrderDtoMapper mapper, AccountStore accounts, OrderContext context, TradingClock clock) {
    this.mapper = mapper;
    this.accounts = accounts;
    this.context = context;
    this.clock = clock;
  }

  /** Check 5 (§11.2): {@code CLIENT_ORDER_ID_INVALID}. */
  public static void requireClientOrderId(String id) {
    if (id == null || !CLIENT_ORDER_ID.matcher(id).matches()) {
      throw ApiException.of(ErrorCode.CLIENT_ORDER_ID_INVALID,
              "clientOrderId must match ^[A-Za-z0-9._:-]{1,64}$.")
          .withHint("Send a unique id per order, e.g. \"bot-20261009-0001\", and reuse it only to retry.")
          .withField("clientOrderId", ErrorCode.CLIENT_ORDER_ID_INVALID, "1–64 of [A-Za-z0-9._:-]");
    }
  }

  /** The stored order for a repeated {@code clientOrderId}, or 409 when the request differs. */
  public PlaceOrderResponse replay(OrderRow r, String requestHash) {
    if (!r.requestHash().equals(requestHash)) {
      throw ApiException.of(ErrorCode.CLIENT_ORDER_ID_CONFLICT, "clientOrderId " + r.clientOrderId()
              + " was already used for a different order (" + r.publicId() + ").")
          .withHint("Use a new clientOrderId for a new order; reuse it only to retry the same request.")
          .withField("clientOrderId", ErrorCode.CLIENT_ORDER_ID_CONFLICT, "Already used");
    }
    var acc = accounts.byId(r.accountId()).orElseThrow();
    BigDecimal bp = context.balances(acc, Et.date(clock.now())).buyingPower();
    BigDecimal price = r.avgFillPrice() != null
        ? Prices.normalize(r.avgFillPrice().setScale(Prices.SCALE, RoundingMode.HALF_UP)) : r.limitPrice();
    EstimateDto estimate = new EstimateDto(price, OrderValidator.qtyOut(r.qty() != null ? r.qty() : r.filledQty()),
        r.filledPrincipal().signum() > 0 ? r.filledPrincipal() : null, FeesDto.zero(), r.reservedCash(), null,
        DataQuality.NONE);
    return new PlaceOrderResponse(mapper.toDto(r), estimate, bp, bp, List.of(), true);
  }
}
