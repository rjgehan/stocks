package com.stocks.trading.web;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.OrderQueries;
import com.stocks.contract.trading.OrderService;
import com.stocks.contract.trading.dto.GroupPreviewDto;
import com.stocks.contract.trading.dto.OrderGroupDto;
import com.stocks.contract.trading.dto.PlaceOrderGroupRequest;
import com.stocks.contract.trading.dto.PlaceOrderGroupResponse;
import com.stocks.contract.trading.dto.ReplaceOrderGroupRequest;
import com.stocks.security.RequestMetas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §18.2 group orders (Phase 2, §11.9): OCO, OTO, OTOCO and BRACKET. Placement and replacement answer 201, or 200 with
 * {@code Idempotent-Replay: true} when the group {@code clientOrderId} was already used for the same request. Each leg
 * is also an ordinary order under {@code /orders} (its {@code group} names the group).
 */
@RestController
@RequestMapping("/api/v1/order-groups")
@Tag(name = "orders", description = "Place, preview, list, replace and cancel orders")
public class OrderGroupController {

  private final OrderService orders;
  private final OrderQueries queries;
  private final AccountQueries accounts;

  public OrderGroupController(OrderService orders, OrderQueries queries, AccountQueries accounts) {
    this.orders = orders;
    this.queries = queries;
    this.accounts = accounts;
  }

  @PostMapping("/preview")
  @Operation(summary = "Preview a group order", description = "Runs the group rules and every leg's checks against "
      + "the current account and market without placing anything; always 200 (valid=false lists the error, its field "
      + "prefixed with the leg, e.g. takeProfit.limitPrice). clientOrderId is optional.")
  public GroupPreviewDto preview(@RequestBody PlaceOrderGroupRequest request) {
    return orders.previewGroup(CurrentPlayer.require(), request);
  }

  @PostMapping
  @Operation(summary = "Place a group order", description = "OCO, OTO, OTOCO or BRACKET: every leg or nothing. 201 "
      + "with the group (children of an entry wait, held, until it executes); 200 with Idempotent-Replay: true when "
      + "this clientOrderId was already used for the same group; 409 for a different request.")
  public ResponseEntity<PlaceOrderGroupResponse> place(@RequestBody PlaceOrderGroupRequest request,
                                                       HttpServletRequest http) {
    PlayerPrincipal who = CurrentPlayer.require();
    return created(orders.placeGroup(who, request, RequestMetas.of(http, who)));
  }

  @GetMapping("/{groupId}")
  @Operation(summary = "One group order with its current legs")
  public OrderGroupDto get(@PathVariable String groupId) {
    long accountId = accounts.activeAccountId(CurrentPlayer.require().playerId());
    return queries.group(accountId, groupId).orElseThrow(() -> notFound(groupId));
  }

  @PatchMapping("/{groupId}")
  @Operation(summary = "Replace a group order", description = "One cancel-replace of the changed legs (prices, the "
      + "quantity while nothing has filled, the time in force) with a NEW group clientOrderId; 201 with the group.")
  public ResponseEntity<PlaceOrderGroupResponse> replace(@PathVariable String groupId,
                                                         @RequestBody ReplaceOrderGroupRequest request,
                                                         HttpServletRequest http) {
    PlayerPrincipal who = CurrentPlayer.require();
    return created(orders.replaceGroup(who, groupId, request, RequestMetas.of(http, who)));
  }

  @DeleteMapping("/{groupId}")
  @Operation(summary = "Cancel a group order", description = "Cancels every open leg, children first; idempotent.")
  public OrderGroupDto cancel(@PathVariable String groupId, HttpServletRequest http) {
    PlayerPrincipal who = CurrentPlayer.require();
    return orders.cancelGroup(who, groupId, RequestMetas.of(http, who));
  }

  static ResponseEntity<PlaceOrderGroupResponse> created(PlaceOrderGroupResponse r) {
    if (r.replayed()) {
      return ResponseEntity.ok().header(OrderController.REPLAY_HEADER, "true").body(r);
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(r);
  }

  private static ApiException notFound(String id) {
    return ApiException.of(ErrorCode.NOT_FOUND, "No order group " + id + ".");
  }
}
