package com.stocks.trading.order;

import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.domain.OrderGroupType;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.web.Text;
import com.stocks.contract.trading.dto.GroupLegRequest;
import com.stocks.contract.trading.dto.PlaceOrderGroupRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A {@code PlaceOrderGroupRequest} after normalization (§11.9): the symbol upper-cased, the side defaulted (an OCO
 * sells by default; an entry buys), the time in force defaulted to DAY, and each leg typed (a take profit is a LIMIT;
 * a stop loss is a STOP, or a STOP_LIMIT when it has a limit price). Shape errors are left to {@link GroupRules}.
 */
public record NormalizedGroup(String clientOrderId, OrderGroupType type, String symbol, Side side, BigDecimal qty,
                              TimeInForce timeInForce, Leg entry, Leg takeProfit, Leg stopLoss, Leg secondary,
                              String rationale, boolean shareNote, boolean doNotReduce, Set<String> acknowledge) {

  /** One leg as requested: its type (null when missing) and prices. */
  public record Leg(OrderType type, BigDecimal limitPrice, BigDecimal stopPrice) {

    Leg withPrices(BigDecimal limit, BigDecimal stop) {
      return new Leg(type, limit, stop);
    }
  }

  public NormalizedGroup {
    acknowledge = acknowledge == null ? Set.of() : Set.copyOf(acknowledge);
  }

  public static NormalizedGroup of(PlaceOrderGroupRequest r) {
    OrderGroupType type = r.type();
    Side side = r.side() != null ? r.side() : type == OrderGroupType.OCO ? Side.SELL : Side.BUY;
    Set<String> ack = new java.util.LinkedHashSet<>();
    if (r.acknowledge() != null) {
      for (String a : r.acknowledge()) {
        if (a != null) {
          ack.add(a.trim().toUpperCase(Locale.ROOT));
        }
      }
    }
    return new NormalizedGroup(r.clientOrderId() == null ? null : r.clientOrderId().trim(), type,
        NormalizedOrder.normalizeSymbol(r.symbol()), side, r.qty(),
        r.timeInForce() == null ? TimeInForce.DAY : r.timeInForce(), leg(r.entry(), null),
        leg(r.takeProfit(), OrderType.LIMIT), stopLoss(r.stopLoss()), leg(r.secondary(), null),
        Text.trimToNull(r.rationale()), Boolean.TRUE.equals(r.shareNote()), Boolean.TRUE.equals(r.doNotReduce()),
        ack);
  }

  private static Leg leg(GroupLegRequest l, OrderType dflt) {
    if (l == null) {
      return null;
    }
    return new Leg(l.type() != null ? l.type() : dflt, l.limitPrice(), l.stopPrice());
  }

  private static Leg stopLoss(GroupLegRequest l) {
    if (l == null) {
      return null;
    }
    OrderType t = l.type() != null ? l.type() : l.limitPrice() != null ? OrderType.STOP_LIMIT : OrderType.STOP;
    return new Leg(t, l.limitPrice(), l.stopPrice());
  }

  /** The requested leg of a role (null when absent). */
  public Leg leg(OrderGroupRole role) {
    return switch (role) {
      case ENTRY -> entry;
      case TAKE_PROFIT -> takeProfit;
      case STOP_LOSS -> stopLoss;
      case SECONDARY -> secondary;
    };
  }

  /** The roles of this group's type, in leg order (§11.9 table). */
  public List<OrderGroupRole> roles() {
    if (type == null) {
      return List.of();
    }
    return switch (type) {
      case OCO -> List.of(OrderGroupRole.TAKE_PROFIT, OrderGroupRole.STOP_LOSS);
      case OTO -> List.of(OrderGroupRole.ENTRY, OrderGroupRole.SECONDARY);
      case OTOCO, BRACKET -> List.of(OrderGroupRole.ENTRY, OrderGroupRole.TAKE_PROFIT, OrderGroupRole.STOP_LOSS);
    };
  }

  /** The side of a leg: an OCO's legs take the group's side; an entry buys and its children sell (wave A). */
  public Side sideOf(OrderGroupRole role) {
    if (type == OrderGroupType.OCO || role == OrderGroupRole.ENTRY) {
      return side;
    }
    return side == Side.BUY ? Side.SELL : Side.BUY;
  }

  /** Children wait for the entry (§11.9): every leg but the entry of OTO, OTOCO and BRACKET. */
  public boolean held(OrderGroupRole role) {
    return type != null && type.hasEntry() && role != OrderGroupRole.ENTRY;
  }

  /** The time in force of a leg: the group's, except a MARKET entry, which is always DAY (§11.9). */
  public TimeInForce tifOf(OrderGroupRole role) {
    Leg l = leg(role);
    return l != null && l.type() == OrderType.MARKET ? TimeInForce.DAY : timeInForce;
  }

  /** One leg as an ordinary order (§11.9: every leg is a normal order for the engine). */
  public NormalizedOrder order(OrderGroupRole role, String legClientOrderId) {
    Leg l = leg(role);
    return new NormalizedOrder(legClientOrderId, symbol, sideOf(role), l.type(), qty, null, l.limitPrice(),
        l.stopPrice(), null, null, tifOf(role), false, rationale, shareNote, acknowledge, doNotReduce);
  }

  /** The same group with other prices on one leg (a replace, §11.9). */
  public NormalizedGroup withLeg(OrderGroupRole role, Leg leg) {
    return new NormalizedGroup(clientOrderId, type, symbol, side, qty, timeInForce,
        role == OrderGroupRole.ENTRY ? leg : entry, role == OrderGroupRole.TAKE_PROFIT ? leg : takeProfit,
        role == OrderGroupRole.STOP_LOSS ? leg : stopLoss, role == OrderGroupRole.SECONDARY ? leg : secondary,
        rationale, shareNote, doNotReduce, acknowledge);
  }

  public NormalizedGroup withQtyAndTif(BigDecimal newQty, TimeInForce tif) {
    return new NormalizedGroup(clientOrderId, type, symbol, side, newQty, tif, entry, takeProfit, stopLoss, secondary,
        rationale, shareNote, doNotReduce, acknowledge);
  }

  /** The group with a new note, which is public (public mode, §16.7). */
  public NormalizedGroup withRationale(String newRationale) {
    return new NormalizedGroup(clientOrderId, type, symbol, side, qty, timeInForce, entry, takeProfit, stopLoss,
        secondary, newRationale, true, doNotReduce, acknowledge);
  }

  public NormalizedGroup withClientOrderId(String id) {
    return new NormalizedGroup(id, type, symbol, side, qty, timeInForce, entry, takeProfit, stopLoss, secondary,
        rationale, shareNote, doNotReduce, acknowledge);
  }

  /**
   * The group's idempotency hash (§11.7, §11.9): hex SHA-256 of the canonical JSON of the business fields (type,
   * symbol, side, qty, timeInForce and every leg's type and prices), decimals stripped, keys sorted; the rationale,
   * the note flag and acknowledgements are excluded, {@code doNotReduce} only counts when set.
   */
  public String hash() {
    Map<String, Object> fields = new TreeMap<>();
    fields.put("groupType", type == null ? null : type.name());
    fields.put("symbol", symbol);
    fields.put("side", side == null ? null : side.name());
    fields.put("qty", dec(qty));
    fields.put("timeInForce", timeInForce == null ? null : timeInForce.name());
    for (OrderGroupRole role : OrderGroupRole.values()) {
      Leg l = leg(role);
      if (l != null) {
        String k = role.name();
        fields.put(k + ".type", l.type() == null ? null : l.type().name());
        fields.put(k + ".limitPrice", dec(l.limitPrice()));
        fields.put(k + ".stopPrice", dec(l.stopPrice()));
      }
    }
    if (doNotReduce) {
      fields.put("doNotReduce", true);
    }
    return RequestHasher.sha256Hex(RequestHasher.canonicalJson(fields));
  }

  /** The leg fields of a request in a stable map (audit detail). */
  public Map<String, Object> describe() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("groupType", String.valueOf(type));
    m.put("symbol", String.valueOf(symbol));
    m.put("side", String.valueOf(side));
    m.put("qty", qty == null ? "" : qty.toPlainString());
    List<String> legs = new ArrayList<>();
    for (OrderGroupRole role : roles()) {
      Leg l = leg(role);
      if (l != null) {
        legs.add(role + ":" + l.type() + (l.limitPrice() == null ? "" : " L" + l.limitPrice().toPlainString())
            + (l.stopPrice() == null ? "" : " S" + l.stopPrice().toPlainString()));
      }
    }
    m.put("legs", String.join(", ", legs));
    return m;
  }

  private static String dec(BigDecimal v) {
    if (v == null) {
      return null;
    }
    BigDecimal s = v.stripTrailingZeros();
    return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
  }
}
