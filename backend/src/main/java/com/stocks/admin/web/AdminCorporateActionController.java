package com.stocks.admin.web;

import com.stocks.admin.AdminText;
import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.domain.CorporateActionStatus;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.admin.dto.ResolveCorporateActionRequest;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.trading.CorporateActionQueries;
import com.stocks.contract.trading.CorporateActionService;
import com.stocks.contract.trading.dto.CorporateActionDto;
import com.stocks.security.RecentAuth;
import com.stocks.security.RequestMetas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** §21.1 corporate actions (§14) through WS3's contracts: list, and apply or skip the ones that need the admin. */
@RestController
@RequestMapping("/api/v1/admin/corporate-actions")
@Tag(name = "admin", description = "League administration (session + ADMIN only, §21)")
public class AdminCorporateActionController {

  private final CorporateActionQueries queries;
  private final CorporateActionService service;
  private final AuditService audit;

  public AdminCorporateActionController(CorporateActionQueries queries, CorporateActionService service,
                                        AuditService audit) {
    this.queries = queries;
    this.service = service;
    this.audit = audit;
  }

  @GetMapping
  @Operation(summary = "Corporate actions", description = "Newest first; filter by status (NEEDS_ADMIN for the "
      + "ones to resolve).")
  public Page<CorporateActionDto> list(@RequestParam(required = false) CorporateActionStatus status,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(required = false) Integer limit) {
    return queries.list(status, Cursor.parse(cursor), Page.clampLimit(limit));
  }

  @PostMapping("/{id}/resolve")
  @RecentAuth
  @Operation(summary = "Apply or skip a corporate action",
      description = "Recent password check. Phase 1 applies forward and reverse splits only, with confirmed "
          + "oldRate/newRate. note 1-200 characters. Errors: CORPORATE_ACTION_NOT_RESOLVABLE, VALIDATION_FAILED.")
  public CorporateActionDto resolve(@PathVariable String id, @Valid @RequestBody ResolveCorporateActionRequest body,
                                    HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    String note = AdminText.require(body.note(), "note", 1, 200);
    CorporateActionDto result = service.resolve(id, body.resolution(), body.oldRate(), body.newRate(), note,
        admin.playerId());
    audit.record(AuditEntry.of(AuditCategory.ADMIN, "CORPORATE_ACTION_RESOLVE", AuditOutcome.OK).withActor(admin)
        .withMeta(RequestMetas.of(request, admin)).withTarget("corporate_action", id)
        .withDetail("resolution", body.resolution().name())
        .withDetail("oldRate", body.oldRate() == null ? null : body.oldRate().toPlainString())
        .withDetail("newRate", body.newRate() == null ? null : body.newRate().toPlainString())
        .withDetail("note", note));
    return result;
  }
}
