package com.stocks.admin.web;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.domain.CancelReason;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.trading.AdminOrderService;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.security.RecentAuth;
import com.stocks.security.RequestMetas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** §21.1 the admin cancels any player's open order (reason ADMIN, visible in the player's order timeline). */
@RestController
@RequestMapping("/api/v1/admin/orders")
@Tag(name = "admin", description = "League administration (session + ADMIN only, §21)")
public class AdminOrderController {

  private final AdminOrderService orders;
  private final AuditService audit;

  public AdminOrderController(AdminOrderService orders, AuditService audit) {
    this.orders = orders;
    this.audit = audit;
  }

  @DeleteMapping("/{orderId}")
  @RecentAuth
  @Operation(summary = "Cancel any player's open order", description = "Recent password check. Errors: "
      + "ORDER_NOT_FOUND, ORDER_NOT_CANCELLABLE.")
  public OrderDto cancel(@PathVariable String orderId, HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    RequestMeta meta = RequestMetas.of(request, admin);
    OrderDto cancelled = orders.cancel(orderId, CancelReason.ADMIN, admin.playerId(), meta);
    audit.record(AuditEntry.of(AuditCategory.ADMIN, "ORDER_CANCEL", AuditOutcome.OK).withActor(admin).withMeta(meta)
        .withTarget("order", orderId));
    return cancelled;
  }
}
