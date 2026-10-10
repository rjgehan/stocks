package com.stocks.mcp.tools;

import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.web.Page;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.OrderFilter;
import com.stocks.contract.trading.OrderQueries;
import com.stocks.contract.trading.OrderService;
import com.stocks.contract.trading.OrderStatusFilter;
import com.stocks.contract.trading.dto.CancelledOrdersDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.contract.trading.dto.PreviewDto;
import com.stocks.contract.trading.dto.ReplaceOrderRequest;
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
 * Order tools (§20.3 #15, #16, #23, #31–#35). Reads need {@code account:read}; writes need {@code trade} and go
 * through WS3's {@code OrderService} exactly like REST: {@code OrderGate} (engine state, player status, client order
 * id, idempotency fast path, one write token) and the engine's {@code OrderValidator} (rationale for AI players,
 * symbol, quantity, prices, session, buying power, GFV …). Synchronous rejections come back as error results with the
 * same {@code ErrorCode}, {@code hint} and {@code fields} as REST's 422s and are never persisted. Every write passes the
 * call's {@code RequestMeta} (channel {@code MCP_KEY}, MCP client, tool name), so it is audited like a REST order.
 *
 * <p>Idempotency (§11.7): {@code place_order}, {@code replace_order} and {@code close_position} take a
 * {@code client_order_id}; repeating a call with the same id and the same order returns the existing order with
 * {@code replayed: true} (free: no write token). After {@code ENGINE_TIMEOUT}, retry with the same id.
 */
@Component
public class OrderTools implements McpToolSet {

  static final int RATIONALE_MAX = 1000;
  private static final Set<String> ACKNOWLEDGEABLE = Set.of("GFV_RISK");

  private final OrderService orderService;
  private final OrderQueries orders;
  private final AccountQueries accounts;

  public OrderTools(OrderService orderService, OrderQueries orders, AccountQueries accounts) {
    this.orderService = orderService;
    this.orders = orders;
    this.accounts = accounts;
  }

  // ------------------------------------------------------------------------------------------- reads

  @McpTool(name = "get_orders", title = "Orders",
      description = "Your orders, newest first: OPEN (pending, working, partially filled; the default), CLOSED "
          + "(filled, cancelled, expired, replaced) or ALL, optionally for one symbol. Each order shows its status, "
          + "waitReason (e.g. QUEUED_FOR_OPEN, LIMIT_NOT_REACHED), filled quantity and average price, holds and "
          + "expiry. For the next page, pass nextCursor as cursor.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Orders", readOnlyHint = true, destructiveHint = false,
          idempotentHint = true, openWorldHint = false))
  public Page<OrderDto> get_orders(McpTransportContext ctx,
      @McpToolParam(required = false, description = "OPEN (default), CLOSED or ALL") OrderStatusFilter status,
      @McpToolParam(required = false, description = "Only this ticker") String symbol,
      @McpToolParam(required = false, description = "1-100, default 50") Integer limit,
      @McpToolParam(required = false, description = "nextCursor of the previous page") String cursor) {
    long accountId = McpArgs.accountId(accounts, McpArgs.caller(ctx));
    int n = McpArgs.limit(limit, 50, 1, 100, "limit");
    OrderFilter filter = new OrderFilter(status == null ? OrderStatusFilter.OPEN : status,
        McpArgs.optionalSymbol(symbol, "symbol"), null, null);
    return orders.list(accountId, filter, McpArgs.cursor(cursor), n);
  }

  @McpTool(name = "get_order", title = "Order details",
      description = "One of your orders with its executions and its event timeline (accepted, queued, working, "
          + "paused, fills, cancel, expiry …). Pass exactly one of order_id (ord_…) and client_order_id. "
          + "ORDER_NOT_FOUND if it is not yours or does not exist.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Order details", readOnlyHint = true, destructiveHint = false,
          idempotentHint = true, openWorldHint = false))
  public OrderDto get_order(McpTransportContext ctx,
      @McpToolParam(required = false, description = "The order id (ord_…)") String order_id,
      @McpToolParam(required = false, description = "Your client_order_id of the order") String client_order_id) {
    boolean byId = order_id != null && !order_id.isBlank();
    boolean byClientId = client_order_id != null && !client_order_id.isBlank();
    if (byId == byClientId) {
      throw ApiException.validation(byId ? "client_order_id" : "order_id",
          "Pass exactly one of order_id and client_order_id.");
    }
    long accountId = McpArgs.accountId(accounts, McpArgs.caller(ctx));
    return (byId ? orders.byPublicId(accountId, order_id.strip(), true)
        : orders.byClientOrderId(accountId, client_order_id.strip(), true))
        .orElseThrow(() -> ApiException.of(ErrorCode.ORDER_NOT_FOUND, "No such order on your account."));
  }

  @McpTool(name = "preview_order", title = "Preview an order",
      description = "Checks an order without placing it (always succeeds as a call): valid, every rule it would "
          + "break (errors with code and hint), warnings (e.g. GFV_RISK, QUEUED_FOR_OPEN, FAT_FINGER), the "
          + "estimated price, quantity, principal, fees and hold, and buying power before and after. Same inputs as "
          + "place_order; client_order_id and rationale are optional here. Use it before large or unusual orders.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Preview an order", readOnlyHint = true, destructiveHint = false,
          idempotentHint = true, openWorldHint = false))
  public PreviewDto preview_order(McpTransportContext ctx,
      @McpToolParam(required = false, description = "Optional here; the id you would place it with")
      String client_order_id,
      @McpToolParam(description = "Ticker, e.g. AAPL") String symbol,
      @McpToolParam(description = "BUY or SELL") Side side,
      @McpToolParam(description = "Order type") OrderType type,
      @McpToolParam(required = false, description = QTY) BigDecimal qty,
      @McpToolParam(required = false, description = NOTIONAL) BigDecimal notional,
      @McpToolParam(required = false, description = LIMIT_PRICE) BigDecimal limit_price,
      @McpToolParam(required = false, description = STOP_PRICE) BigDecimal stop_price,
      @McpToolParam(required = false, description = TRAIL_AMOUNT) BigDecimal trail_amount,
      @McpToolParam(required = false, description = TRAIL_PERCENT) BigDecimal trail_percent,
      @McpToolParam(required = false, description = TIF) TimeInForce time_in_force,
      @McpToolParam(required = false, description = EXT) Boolean extended_hours,
      @McpToolParam(required = false, description = "Optional here") String rationale,
      @McpToolParam(required = false, description = SHARE_NOTE) Boolean share_note,
      @McpToolParam(required = false, description = ACK) List<String> acknowledge) {
    PlayerPrincipal caller = McpArgs.caller(ctx);
    return orderService.preview(caller, request(client_order_id, symbol, side, type, qty, notional, limit_price,
        stop_price, trail_amount, trail_percent, time_in_force, extended_hours, rationale, share_note, acknowledge));
  }

  // ------------------------------------------------------------------------------------------- writes

  @McpTool(name = "place_order", title = "Place an order",
      description = "Places an order with fake money on the live US market. Required: a unique client_order_id "
          + "(1-64 of A-Z a-z 0-9 . _ : -; reuse it only to retry the same order), symbol, side, type, and exactly "
          + "one of qty (shares, up to 5 decimals for fractional MARKET orders) or notional (dollars, MARKET DAY "
          + "only, all-in including fees). LIMIT needs limit_price; STOP needs stop_price; STOP_LIMIT needs both; "
          + "TRAILING_STOP needs exactly one of trail_amount or trail_percent and can only be entered in the "
          + "REGULAR session (DAY only). Stops trigger only in the REGULAR session (a sell stop below the market, a "
          + "buy stop above it), then work as a market (STOP, TRAILING_STOP) or limit (STOP_LIMIT) order; "
          + "get_rules orders.matrix lists the types and their rules. AI players must send a "
          + "rationale (1-1000 characters; it becomes public with the delayed trade). Decimals are strings like "
          + "\"227.40\". A sell that would cause a good-faith violation needs acknowledge [\"GFV_RISK\"]. Returns the "
          + "order (usually PENDING; fills arrive after a short latency), the estimate, buying power before and "
          + "after, warnings, and replayed=true when this client_order_id was already placed.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Place an order", readOnlyHint = false, destructiveHint = false,
          idempotentHint = true, openWorldHint = false))
  public PlaceOrderResponse place_order(McpTransportContext ctx,
      @McpToolParam(description = "Unique id you choose for this order, e.g. claude-20261009-0007")
      String client_order_id,
      @McpToolParam(description = "Ticker, e.g. AAPL") String symbol,
      @McpToolParam(description = "BUY or SELL") Side side,
      @McpToolParam(description = "Order type") OrderType type,
      @McpToolParam(required = false, description = QTY) BigDecimal qty,
      @McpToolParam(required = false, description = NOTIONAL) BigDecimal notional,
      @McpToolParam(required = false, description = LIMIT_PRICE) BigDecimal limit_price,
      @McpToolParam(required = false, description = STOP_PRICE) BigDecimal stop_price,
      @McpToolParam(required = false, description = TRAIL_AMOUNT) BigDecimal trail_amount,
      @McpToolParam(required = false, description = TRAIL_PERCENT) BigDecimal trail_percent,
      @McpToolParam(required = false, description = TIF) TimeInForce time_in_force,
      @McpToolParam(required = false, description = EXT) Boolean extended_hours,
      @McpToolParam(required = false, description = RATIONALE) String rationale,
      @McpToolParam(required = false, description = SHARE_NOTE) Boolean share_note,
      @McpToolParam(required = false, description = ACK) List<String> acknowledge) {
    PlayerPrincipal caller = McpArgs.caller(ctx);
    return orderService.place(caller, request(client_order_id, symbol, side, type, qty, notional, limit_price,
        stop_price, trail_amount, trail_percent, time_in_force, extended_hours, rationale, share_note, acknowledge),
        meta(ctx));
  }

  @McpTool(name = "replace_order", title = "Replace an order",
      description = "Cancel-replace one of your unfilled whole-share orders (PENDING or WORKING): the old order "
          + "becomes REPLACED and a new order with a NEW client_order_id takes its place with a fresh latency "
          + "window. You may change qty, limit_price, stop_price, trail_amount/trail_percent, time_in_force and "
          + "extended_hours; symbol, side and type stay. Fractional and notional orders cannot be replaced: cancel "
          + "and place again. Returns the new order.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Replace an order", readOnlyHint = false, destructiveHint = true,
          idempotentHint = true, openWorldHint = false))
  public PlaceOrderResponse replace_order(McpTransportContext ctx,
      @McpToolParam(description = "The order to replace (ord_…)") String order_id,
      @McpToolParam(description = "A NEW unique client_order_id for the replacement") String client_order_id,
      @McpToolParam(required = false, description = "New whole-share quantity") BigDecimal qty,
      @McpToolParam(required = false, description = LIMIT_PRICE) BigDecimal limit_price,
      @McpToolParam(required = false, description = STOP_PRICE) BigDecimal stop_price,
      @McpToolParam(required = false, description = TRAIL_AMOUNT) BigDecimal trail_amount,
      @McpToolParam(required = false, description = TRAIL_PERCENT) BigDecimal trail_percent,
      @McpToolParam(required = false, description = TIF) TimeInForce time_in_force,
      @McpToolParam(required = false, description = EXT) Boolean extended_hours,
      @McpToolParam(required = false, description = RATIONALE) String rationale) {
    PlayerPrincipal caller = McpArgs.caller(ctx);
    String orderId = McpArgs.text(order_id, 32, "order_id");
    return orderService.replace(caller, orderId, new ReplaceOrderRequest(client_order_id, qty, limit_price,
        stop_price, trail_amount, trail_percent, time_in_force, extended_hours, rationale(rationale)), meta(ctx));
  }

  @McpTool(name = "cancel_order", title = "Cancel an order",
      description = "Cancels one of your open orders immediately (a fill the engine already processed wins: the "
          + "result then shows it filled). Cancelling an already-cancelled order returns it unchanged; a filled or "
          + "expired order gives ORDER_NOT_CANCELLABLE.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Cancel an order", readOnlyHint = false, destructiveHint = true,
          idempotentHint = true, openWorldHint = false))
  public OrderDto cancel_order(McpTransportContext ctx,
      @McpToolParam(description = "The order to cancel (ord_…)") String order_id) {
    PlayerPrincipal caller = McpArgs.caller(ctx);
    return orderService.cancel(caller, McpArgs.text(order_id, 32, "order_id"), meta(ctx));
  }

  @McpTool(name = "cancel_all_orders", title = "Cancel all orders",
      description = "Cancels every open order of yours, or only those for one symbol. Returns the orders it "
          + "cancelled (possibly none).",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Cancel all orders", readOnlyHint = false,
          destructiveHint = true, idempotentHint = true, openWorldHint = false))
  public CancelledOrdersDto cancel_all_orders(McpTransportContext ctx,
      @McpToolParam(required = false, description = "Only orders for this ticker") String symbol) {
    PlayerPrincipal caller = McpArgs.caller(ctx);
    return new CancelledOrdersDto(orderService.cancelAll(caller, McpArgs.optionalSymbol(symbol, "symbol"),
        meta(ctx)));
  }

  @McpTool(name = "close_position", title = "Close a position",
      description = "Sells your whole available position in a symbol with a MARKET DAY order (fractional quantities "
          + "allowed; outside the regular session it queues for the next open). Same rules and idempotency as "
          + "place_order: send a unique client_order_id, and a rationale if you are an AI player.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Close a position", readOnlyHint = false, destructiveHint = true,
          idempotentHint = true, openWorldHint = false))
  public PlaceOrderResponse close_position(McpTransportContext ctx,
      @McpToolParam(description = "Ticker of the position to close") String symbol,
      @McpToolParam(description = "Unique id you choose for the closing order") String client_order_id,
      @McpToolParam(required = false, description = RATIONALE) String rationale) {
    PlayerPrincipal caller = McpArgs.caller(ctx);
    return orderService.closePosition(caller, McpArgs.symbol(symbol, "symbol"), client_order_id,
        rationale(rationale), meta(ctx));
  }

  // ------------------------------------------------------------------------------------------- helpers

  private static final String QTY = "Shares as a decimal string, e.g. \"10\" or \"0.5\" (fractional: MARKET DAY "
      + "only). Exactly one of qty and notional.";
  private static final String NOTIONAL = "Dollar amount as a decimal string, e.g. \"125.00\" (MARKET DAY only, "
      + "fractionable symbols, minimum \"1.00\" for buys; all-in including fees). Exactly one of qty and notional.";
  private static final String LIMIT_PRICE = "Limit price as a decimal string on tick ($0.01, or $0.0001 below $1)";
  private static final String STOP_PRICE = "Stop (trigger) price as a decimal string on tick (STOP, STOP_LIMIT): "
      + "below the market for a sell, above it for a buy";
  private static final String TRAIL_AMOUNT = "Trailing distance in dollars, on tick (TRAILING_STOP; exactly one of "
      + "trail_amount and trail_percent)";
  private static final String TRAIL_PERCENT = "Trailing distance in percent, 0 < p < 100, up to 4 decimals "
      + "(TRAILING_STOP; exactly one of trail_amount and trail_percent)";
  private static final String TIF = "DAY (default) or GTC (60 days; not for MARKET or TRAILING_STOP)";
  private static final String EXT = "LIMIT only: also work in PRE (04:00-09:30 ET) and POST (16:00-20:00 ET); "
      + "default false";
  private static final String RATIONALE = "Why you are trading (1-1000 characters). Required for AI players; it "
      + "becomes public with the delayed trade.";
  private static final String SHARE_NOTE = "Humans only: publish your rationale with the delayed trade";
  private static final String ACK = "Warnings you accept; [\"GFV_RISK\"] to sell despite a good-faith-violation risk";

  private static PlaceOrderRequest request(String clientOrderId, String symbol, Side side, OrderType type,
                                           BigDecimal qty, BigDecimal notional, BigDecimal limitPrice,
                                           BigDecimal stopPrice, BigDecimal trailAmount, BigDecimal trailPercent,
                                           TimeInForce tif, Boolean extendedHours, String rationale,
                                           Boolean shareNote, List<String> acknowledge) {
    return new PlaceOrderRequest(clientOrderId, McpArgs.symbol(symbol, "symbol"), side, type, qty, notional,
        limitPrice, stopPrice, trailAmount, trailPercent, tif, extendedHours, rationale(rationale), shareNote,
        acknowledge(acknowledge));
  }

  /** Trims; an over-long rationale is a validation error (the engine's 1–1000 rule, checked early). */
  private static String rationale(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String s = raw.strip();
    if (s.length() > RATIONALE_MAX) {
      throw ApiException.validation("rationale", "rationale must be at most " + RATIONALE_MAX + " characters.");
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

  /** The call's request metadata (channel, client, tool) for the gate's audit row. */
  private static RequestMeta meta(McpTransportContext ctx) {
    return CurrentPlayer.requestMeta(ctx).orElse(null);
  }
}
