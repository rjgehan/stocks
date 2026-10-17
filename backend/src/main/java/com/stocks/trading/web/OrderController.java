package com.stocks.trading.web;

import com.stocks.common.domain.Crypto;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
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
import com.stocks.security.RequestMetas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Locale;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §18.2 orders. Placement and replacement answer 201, or 200 with {@code Idempotent-Replay: true} when the
 * {@code clientOrderId} was already used for the same request (§11.7). Validation failures are 422 problems and are
 * not persisted (D15).
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "orders", description = "Place, preview, list, replace and cancel orders")
public class OrderController {

  public static final String REPLAY_HEADER = "Idempotent-Replay";

  private final OrderService orders;
  private final OrderQueries queries;
  private final AccountQueries accounts;

  public OrderController(OrderService orders, OrderQueries queries, AccountQueries accounts) {
    this.orders = orders;
    this.queries = queries;
    this.accounts = accounts;
  }

  @PostMapping("/preview")
  @Operation(summary = "Preview an order", description = "Runs every check against the current account and market "
      + "without placing anything; always 200 (valid=false lists the errors). clientOrderId is optional.")
  public PreviewDto preview(@RequestBody PlaceOrderRequest request) {
    return orders.preview(CurrentPlayer.require(), request);
  }

  @PostMapping
  @Operation(summary = "Place an order", description = "201 with the PENDING order; 200 with Idempotent-Replay: true "
      + "when this clientOrderId was already used for the same request; 409 for a different request.")
  public ResponseEntity<PlaceOrderResponse> place(@RequestBody PlaceOrderRequest request, HttpServletRequest http) {
    PlayerPrincipal who = CurrentPlayer.require();
    return created(orders.place(who, request, RequestMetas.of(http, who)));
  }

  @GetMapping
  @Operation(summary = "List orders", description = "Newest first; status OPEN | CLOSED | ALL (default ALL). "
      + "List items have executions and events null.")
  public Page<OrderDto> list(@RequestParam(required = false) OrderStatusFilter status,
                             @RequestParam(required = false) String symbol,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                             Instant from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                             Instant to,
                             @RequestParam(required = false) String cursor,
                             @RequestParam(required = false) Integer limit) {
    long accountId = accounts.activeAccountId(CurrentPlayer.require().playerId());
    OrderFilter filter = new OrderFilter(status == null ? OrderStatusFilter.ALL : status, upper(symbol), from, to);
    return queries.list(accountId, filter, Cursor.parse(cursor), Page.clampLimit(limit));
  }

  @GetMapping("/{orderId}")
  @Operation(summary = "One order with its executions and timeline")
  public OrderDto get(@PathVariable String orderId) {
    long accountId = accounts.activeAccountId(CurrentPlayer.require().playerId());
    return queries.byPublicId(accountId, orderId, true).orElseThrow(() -> notFound(orderId));
  }

  @GetMapping("/by-client-id/{clientOrderId}")
  @Operation(summary = "One order by its clientOrderId, with executions and timeline")
  public OrderDto byClientId(@PathVariable String clientOrderId) {
    long accountId = accounts.activeAccountId(CurrentPlayer.require().playerId());
    return queries.byClientOrderId(accountId, clientOrderId, true).orElseThrow(() -> notFound(clientOrderId));
  }

  @PatchMapping("/{orderId}")
  @Operation(summary = "Replace an order", description = "Cancel-replace of an unfilled whole-share PENDING or "
      + "WORKING order with a NEW clientOrderId; 201 with the new order.")
  public ResponseEntity<PlaceOrderResponse> replace(@PathVariable String orderId,
                                                    @RequestBody ReplaceOrderRequest request,
                                                    HttpServletRequest http) {
    PlayerPrincipal who = CurrentPlayer.require();
    return created(orders.replace(who, orderId, request, RequestMetas.of(http, who)));
  }

  @DeleteMapping("/{orderId}")
  @Operation(summary = "Cancel an order", description = "Immediate; cancelling a CANCELLED order is idempotent (200).")
  public OrderDto cancel(@PathVariable String orderId, HttpServletRequest http) {
    PlayerPrincipal who = CurrentPlayer.require();
    return orders.cancel(who, orderId, RequestMetas.of(http, who));
  }

  @DeleteMapping
  @Operation(summary = "Cancel every open order", description = "Optionally only on one symbol.")
  public CancelledOrdersDto cancelAll(@RequestParam(required = false) String symbol, HttpServletRequest http) {
    PlayerPrincipal who = CurrentPlayer.require();
    return new CancelledOrdersDto(orders.cancelAll(who, upper(symbol), RequestMetas.of(http, who)));
  }

  static ResponseEntity<PlaceOrderResponse> created(PlaceOrderResponse r) {
    if (r.replayed()) {
      return ResponseEntity.ok().header(REPLAY_HEADER, "true").body(r);
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(r);
  }

  static String upper(String symbol) {
    return symbol == null || symbol.isBlank() ? null : Crypto.fromPath(symbol.trim().toUpperCase(Locale.ROOT));
  }

  private static ApiException notFound(String id) {
    return ApiException.of(ErrorCode.ORDER_NOT_FOUND, "No order " + id + ".");
  }
}
