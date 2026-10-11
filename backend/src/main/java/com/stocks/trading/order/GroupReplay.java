package com.stocks.trading.order;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.money.Prices;
import com.stocks.common.time.Et;
import com.stocks.contract.trading.dto.EstimateDto;
import com.stocks.contract.trading.dto.FeesDto;
import com.stocks.contract.trading.dto.OrderGroupDto;
import com.stocks.contract.trading.dto.PlaceOrderGroupResponse;
import com.stocks.trading.order.mapper.OrderGroupDtoMapper;
import com.stocks.trading.store.AccountStore;
import com.stocks.trading.store.OrderGroupRow;
import com.stocks.trading.store.OrderGroupStore;
import com.stocks.trading.store.OrderRow;
import com.stocks.trading.store.OrderStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Group idempotency (§11.9): every group {@code clientOrderId} (a placement's, or a group replace's) is recorded with
 * its request hash. The same id with the same hash replays the group ({@code replayed: true}, 200, free); with a
 * different hash, or used by the other kind of request, it is {@code 409 CLIENT_ORDER_ID_CONFLICT}. Used by the
 * request-thread fast path and by the authoritative check inside the engine commands.
 */
@Component
public class GroupReplay {

  public static final String PLACE = "PLACE";
  public static final String REPLACE = "REPLACE";

  private final OrderGroupStore groups;
  private final OrderStore orders;
  private final OrderGroupDtoMapper mapper;
  private final AccountStore accounts;
  private final OrderContext context;
  private final TradingClock clock;

  public GroupReplay(OrderGroupStore groups, OrderStore orders, OrderGroupDtoMapper mapper, AccountStore accounts,
                     OrderContext context, TradingClock clock) {
    this.groups = groups;
    this.orders = orders;
    this.mapper = mapper;
    this.accounts = accounts;
    this.context = context;
    this.clock = clock;
  }

  /**
   * The replay of a group request whose {@code clientOrderId} was used before, or empty when the id is new.
   * {@code groupIdOrNull}: a replace's target group (its id must match).
   */
  public Optional<PlaceOrderGroupResponse> lookup(long accountId, String clientOrderId, String hash, String kind,
                                                  Long groupIdOrNull) {
    Optional<OrderGroupStore.Request> req = groups.request(accountId, clientOrderId);
    if (req.isEmpty()) {
      return Optional.empty();
    }
    OrderGroupStore.Request r = req.get();
    boolean same = r.requestHash().equals(hash) && r.kind().equals(kind)
        && (groupIdOrNull == null || groupIdOrNull == r.groupId());
    if (!same) {
      throw ApiException.of(ErrorCode.CLIENT_ORDER_ID_CONFLICT, "clientOrderId " + clientOrderId
              + " was already used for a different group request.")
          .withHint("Use a new clientOrderId for a new group or replace; reuse it only to retry the same request.")
          .withField("clientOrderId", ErrorCode.CLIENT_ORDER_ID_CONFLICT, "Already used");
    }
    OrderGroupRow g = groups.byId(r.groupId()).orElseThrow();
    return Optional.of(response(g, true));
  }

  /** The group as it is now, as a placement response (replays, and the result of a replace). */
  public PlaceOrderGroupResponse response(OrderGroupRow g, boolean replayed) {
    List<OrderRow> legs = orders.legs(g.id());
    OrderGroupDto dto = mapper.toDto(g, legs);
    var acc = accounts.byId(g.accountId()).orElseThrow();
    BigDecimal bp = context.balances(acc, Et.date(clock.now())).buyingPower();
    OrderRow main = OrderGroupDtoMapper.current(legs).stream()
        .filter(l -> l.groupRole() == OrderGroupRole.ENTRY).findFirst()
        .orElse(OrderGroupDtoMapper.current(legs).stream().findFirst().orElse(null));
    EstimateDto estimate = main == null ? null : new EstimateDto(price(main),
        OrderValidator.qtyOut(main.qty() != null ? main.qty() : main.filledQty()),
        main.filledPrincipal().signum() > 0 ? main.filledPrincipal() : null, FeesDto.zero(), main.reservedCash(),
        null, DataQuality.NONE);
    return new PlaceOrderGroupResponse(dto, estimate, bp, bp, List.of(), replayed);
  }

  private static BigDecimal price(OrderRow r) {
    if (r.avgFillPrice() != null) {
      return Prices.normalize(r.avgFillPrice().setScale(Prices.SCALE, RoundingMode.HALF_UP));
    }
    return r.limitPrice() != null ? r.limitPrice() : r.stopPrice();
  }
}
