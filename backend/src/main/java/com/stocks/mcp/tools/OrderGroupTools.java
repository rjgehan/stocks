package com.stocks.mcp.tools;

import com.stocks.common.domain.OrderGroupType;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.OrderQueries;
import com.stocks.contract.trading.OrderService;
import com.stocks.contract.trading.dto.GroupLegRequest;
import com.stocks.contract.trading.dto.GroupPreviewDto;
import com.stocks.contract.trading.dto.OrderGroupDto;
import com.stocks.contract.trading.dto.PlaceOrderGroupRequest;
import com.stocks.contract.trading.dto.PlaceOrderGroupResponse;
import io.modelcontextprotocol.common.McpTransportContext;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Group order tools (§20.3 #39–#42, Phase 2, §11.9): OCO, OTO, OTOCO and BRACKET. They go through WS3's
 * {@code OrderService} exactly like {@code POST /order-groups}: {@code OrderGate} (one write token per group, free
 * replays) and the engine's checks of every leg. Leg inputs are flat (no nested objects): {@code entry_type},
 * {@code entry_limit_price}, {@code entry_stop_price}, {@code take_profit_limit_price}, {@code stop_loss_stop_price},
 * {@code stop_loss_limit_price}, {@code secondary_type}, {@code secondary_limit_price}, {@code secondary_stop_price}.
 * A single leg is replaced or cancelled with {@code replace_order} / {@code cancel_order}. One rationale per group:
 * required for AI; public (public mode, §16.7); {@code share_note} is deprecated and ignored.
 */
@Component
public class OrderGroupTools implements McpToolSet {

  private static final Set<String> ACKNOWLEDGEABLE = Set.of("GFV_RISK");

  private final OrderService orderService;
  private final OrderQueries orders;
  private final AccountQueries accounts;

  public OrderGroupTools(OrderService orderService, OrderQueries orders, AccountQueries accounts) {
    this.orderService = orderService;
    this.orders = orders;
    this.accounts = accounts;
  }

  @McpTool(name = "preview_order_group", title = "Preview a group order",
      description = "Checks a BRACKET, OCO, OTO or OTOCO order without placing it (always succeeds as a call): valid, "
          + "the first rule it breaks (code, the field named after the leg such as stopLoss.stopPrice, "
          + "and a hint), warnings, each leg's estimate, and buying power before and after. Same inputs as "
          + "place_order_group; client_order_id and rationale are optional here.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Preview a group order", readOnlyHint = true,
          destructiveHint = false, idempotentHint = true, openWorldHint = false))
  public GroupPreviewDto preview_order_group(McpTransportContext ctx,
      @McpToolParam(required = false, description = "Optional here; the id you would place it with")
      String client_order_id,
      @McpToolParam(description = GROUP_TYPE) OrderGroupType group_type,
      @McpToolParam(description = "Ticker, e.g. AAPL") String symbol,
      @McpToolParam(required = false, description = SIDE) Side side,
      @McpToolParam(description = QTY) BigDecimal qty,
      @McpToolParam(required = false, description = TIF) TimeInForce time_in_force,
      @McpToolParam(required = false, description = ENTRY_TYPE) OrderType entry_type,
      @McpToolParam(required = false, description = ENTRY_LIMIT) BigDecimal entry_limit_price,
      @McpToolParam(required = false, description = ENTRY_STOP) BigDecimal entry_stop_price,
      @McpToolParam(required = false, description = TP_LIMIT) BigDecimal take_profit_limit_price,
      @McpToolParam(required = false, description = SL_STOP) BigDecimal stop_loss_stop_price,
      @McpToolParam(required = false, description = SL_LIMIT) BigDecimal stop_loss_limit_price,
      @McpToolParam(required = false, description = SEC_TYPE) OrderType secondary_type,
      @McpToolParam(required = false, description = SEC_LIMIT) BigDecimal secondary_limit_price,
      @McpToolParam(required = false, description = SEC_STOP) BigDecimal secondary_stop_price,
      @McpToolParam(required = false, description = "Optional here") String rationale,
      @McpToolParam(required = false, description = SHARE_NOTE) Boolean share_note,
      @McpToolParam(required = false, description = DO_NOT_REDUCE) Boolean do_not_reduce,
      @McpToolParam(required = false, description = ACK) List<String> acknowledge) {
    PlayerPrincipal caller = McpArgs.caller(ctx);
    return orderService.previewGroup(caller, request(client_order_id, group_type, symbol, side, qty, time_in_force,
        entry_type, entry_limit_price, entry_stop_price, take_profit_limit_price, stop_loss_stop_price,
        stop_loss_limit_price, secondary_type, secondary_limit_price, secondary_stop_price, rationale, share_note,
        do_not_reduce, acknowledge));
  }

  @McpTool(name = "place_order_group", title = "Place a group order",
      description = "Places Webull-style advanced orders, every leg or nothing. BRACKET: entry_type MARKET or LIMIT "
          + "(BUY) + take_profit_limit_price + stop_loss_stop_price (add stop_loss_limit_price for a stop-limit); the "
          + "take profit and stop loss wait until the entry fills, then work for the shares it bought, and the first "
          + "of them to execute cancels the other; take profit > entry price > stop loss, at least 0.1% of the entry "
          + "price apart. OTOCO: the same with a STOP or STOP_LIMIT entry allowed and no price-distance rule. OTO: an "
          + "entry + one secondary (secondary_type LIMIT, STOP or STOP_LIMIT, SELL) that waits for the entry. OCO: "
          + "take_profit_limit_price (LIMIT) + stop_loss_stop_price (STOP) on side SELL (default; protect shares you "
          + "hold, reserved once) or BUY. qty is whole shares for every leg; time_in_force DAY (default) or GTC for "
          + "every leg. Required: a unique client_order_id, and a rationale for AI players. Returns the group with "
          + "its legs (each an ordinary order: get_order, cancel_order and replace_order work on a leg), the "
          + "estimate, buying power and warnings, and replayed=true when this client_order_id was already used.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Place a group order", readOnlyHint = false,
          destructiveHint = false, idempotentHint = true, openWorldHint = false))
  public PlaceOrderGroupResponse place_order_group(McpTransportContext ctx,
      @McpToolParam(description = "Unique id you choose for this group, e.g. claude-20261009-0008")
      String client_order_id,
      @McpToolParam(description = GROUP_TYPE) OrderGroupType group_type,
      @McpToolParam(description = "Ticker, e.g. AAPL") String symbol,
      @McpToolParam(required = false, description = SIDE) Side side,
      @McpToolParam(description = QTY) BigDecimal qty,
      @McpToolParam(required = false, description = TIF) TimeInForce time_in_force,
      @McpToolParam(required = false, description = ENTRY_TYPE) OrderType entry_type,
      @McpToolParam(required = false, description = ENTRY_LIMIT) BigDecimal entry_limit_price,
      @McpToolParam(required = false, description = ENTRY_STOP) BigDecimal entry_stop_price,
      @McpToolParam(required = false, description = TP_LIMIT) BigDecimal take_profit_limit_price,
      @McpToolParam(required = false, description = SL_STOP) BigDecimal stop_loss_stop_price,
      @McpToolParam(required = false, description = SL_LIMIT) BigDecimal stop_loss_limit_price,
      @McpToolParam(required = false, description = SEC_TYPE) OrderType secondary_type,
      @McpToolParam(required = false, description = SEC_LIMIT) BigDecimal secondary_limit_price,
      @McpToolParam(required = false, description = SEC_STOP) BigDecimal secondary_stop_price,
      @McpToolParam(required = false, description = RATIONALE) String rationale,
      @McpToolParam(required = false, description = SHARE_NOTE) Boolean share_note,
      @McpToolParam(required = false, description = DO_NOT_REDUCE) Boolean do_not_reduce,
      @McpToolParam(required = false, description = ACK) List<String> acknowledge) {
    PlayerPrincipal caller = McpArgs.caller(ctx);
    return orderService.placeGroup(caller, request(client_order_id, group_type, symbol, side, qty, time_in_force,
        entry_type, entry_limit_price, entry_stop_price, take_profit_limit_price, stop_loss_stop_price,
        stop_loss_limit_price, secondary_type, secondary_limit_price, secondary_stop_price, rationale, share_note,
        do_not_reduce, acknowledge), meta(ctx));
  }

  @McpTool(name = "get_order_group", title = "Group order details",
      description = "One of your group orders (grp_…) with its status (ACTIVE, COMPLETED, CANCELLED, EXPIRED) and its "
          + "current legs (entry, take profit, stop loss, secondary): each leg's status, held (waiting for the entry) "
          + "and fills. Use get_order on a leg for its timeline. NOT_FOUND if it is not yours.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Group order details", readOnlyHint = true,
          destructiveHint = false, idempotentHint = true, openWorldHint = false))
  public OrderGroupDto get_order_group(McpTransportContext ctx,
      @McpToolParam(description = "The group id (grp_…)") String group_id) {
    long accountId = McpArgs.accountId(accounts, McpArgs.caller(ctx));
    String id = McpArgs.text(group_id, 32, "group_id");
    return orders.group(accountId, id)
        .orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "No such order group on your account."));
  }

  @McpTool(name = "cancel_order_group", title = "Cancel a group order",
      description = "Cancels every open leg of one of your group orders (children first). Legs that already filled "
          + "stay filled; cancelling a finished group returns it unchanged.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Cancel a group order", readOnlyHint = false,
          destructiveHint = true, idempotentHint = true, openWorldHint = false))
  public OrderGroupDto cancel_order_group(McpTransportContext ctx,
      @McpToolParam(description = "The group to cancel (grp_…)") String group_id) {
    PlayerPrincipal caller = McpArgs.caller(ctx);
    return orderService.cancelGroup(caller, McpArgs.text(group_id, 32, "group_id"), meta(ctx));
  }

  // ------------------------------------------------------------------------------------------- helpers

  private static final String GROUP_TYPE = "BRACKET (entry + take profit + stop loss with price rules), OCO (take "
      + "profit + stop loss, one cancels the other), OTO (entry + secondary) or OTOCO (entry + take profit + stop loss)";
  private static final String SIDE = "OCO: SELL (default) or BUY for both legs. Other types: the entry side, BUY "
      + "(default; the children sell)";
  private static final String QTY = "Whole shares as a decimal string, e.g. \"10\", for every leg";
  private static final String TIF = "DAY (default) or GTC (60 days) for every leg; a MARKET entry is always DAY";
  private static final String ENTRY_TYPE = "BRACKET: MARKET or LIMIT; OTO and OTOCO: MARKET, LIMIT, STOP or "
      + "STOP_LIMIT (not for OCO)";
  private static final String ENTRY_LIMIT = "The entry's limit price (LIMIT, STOP_LIMIT), on tick";
  private static final String ENTRY_STOP = "The entry's stop price (STOP, STOP_LIMIT; a buy stop above the market)";
  private static final String TP_LIMIT = "Take profit limit price (BRACKET, OTOCO, OCO), on tick";
  private static final String SL_STOP = "Stop loss stop (trigger) price (BRACKET, OTOCO, OCO), on tick";
  private static final String SL_LIMIT = "Optional: makes the stop loss a STOP_LIMIT with this limit price";
  private static final String SEC_TYPE = "OTO only: the secondary's type, LIMIT, STOP or STOP_LIMIT";
  private static final String SEC_LIMIT = "OTO only: the secondary's limit price (LIMIT, STOP_LIMIT)";
  private static final String SEC_STOP = "OTO only: the secondary's stop price (STOP, STOP_LIMIT)";
  private static final String RATIONALE = "Why you are trading (1-1000 characters), one for the whole group. Required "
      + "for AI players; it is public: every player sees it with the orders.";
  private static final String SHARE_NOTE = "Deprecated and ignored: every new note is public";
  private static final String DO_NOT_REDUCE = "Keep your limit and stop prices on ex-dividend dates (FINRA 5330 "
      + "\"do not reduce\"); default false";
  private static final String ACK = "Warnings you accept; [\"GFV_RISK\"] to sell despite a good-faith-violation risk";

  static PlaceOrderGroupRequest request(String clientOrderId, OrderGroupType type, String symbol, Side side,
                                        BigDecimal qty, TimeInForce tif, OrderType entryType, BigDecimal entryLimit,
                                        BigDecimal entryStop, BigDecimal tpLimit, BigDecimal slStop,
                                        BigDecimal slLimit, OrderType secType, BigDecimal secLimit,
                                        BigDecimal secStop, String rationale, Boolean shareNote, Boolean doNotReduce,
                                        List<String> acknowledge) {
    GroupLegRequest entry = entryType == null && entryLimit == null && entryStop == null ? null
        : new GroupLegRequest(entryType, entryLimit, entryStop);
    GroupLegRequest tp = tpLimit == null ? null : new GroupLegRequest(null, tpLimit, null);
    GroupLegRequest sl = slStop == null && slLimit == null ? null : new GroupLegRequest(null, slLimit, slStop);
    GroupLegRequest sec = secType == null && secLimit == null && secStop == null ? null
        : new GroupLegRequest(secType, secLimit, secStop);
    return new PlaceOrderGroupRequest(clientOrderId, type, McpArgs.symbol(symbol, "symbol"), side, qty, tif, entry,
        tp, sl, sec, rationale(rationale), shareNote, doNotReduce, acknowledge(acknowledge));
  }

  private static String rationale(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String s = raw.strip();
    if (s.length() > OrderTools.RATIONALE_MAX) {
      throw ApiException.validation("rationale", "rationale must be at most " + OrderTools.RATIONALE_MAX
          + " characters.");
    }
    return s;
  }

  private static Set<String> acknowledge(List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return Set.of();
    }
    Set<String> out = new LinkedHashSet<>();
    for (String a : raw) {
      String code = a == null ? "" : a.strip().toUpperCase(Locale.ROOT);
      if (!ACKNOWLEDGEABLE.contains(code)) {
        throw ApiException.validation("acknowledge", "Only \"GFV_RISK\" can be acknowledged.");
      }
      out.add(code);
    }
    return out;
  }

  private static RequestMeta meta(McpTransportContext ctx) {
    return CurrentPlayer.requestMeta(ctx).orElse(null);
  }
}
