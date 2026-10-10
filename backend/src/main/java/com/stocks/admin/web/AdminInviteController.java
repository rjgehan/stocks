package com.stocks.admin.web;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.contract.admin.dto.CreateInviteRequest;
import com.stocks.contract.admin.dto.InviteCreatedDto;
import com.stocks.contract.admin.dto.InviteDto;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.identity.InviteService;
import com.stocks.security.RequestMetas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** §21.1 invite links for the invite-only signup: create (the code is shown once), list, revoke. */
@RestController
@RequestMapping("/api/v1/admin/invites")
@Tag(name = "admin", description = "League administration (session + ADMIN only, §21)")
public class AdminInviteController {

  private final InviteService invites;
  private final AuditService audit;

  public AdminInviteController(InviteService invites, AuditService audit) {
    this.invites = invites;
    this.audit = audit;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create an invite link",
      description = "maxUses 1-50, expiresInHours 1-720. Returns the code and the link <public-url>/join/<code> "
          + "once (only a hash is stored). Errors: VALIDATION_FAILED.")
  public InviteCreatedDto create(@Valid @RequestBody CreateInviteRequest body, HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    InviteCreatedDto created = invites.create(body.maxUses(), body.expiresInHours(), admin.playerId());
    audit.record(AuditEntry.of(AuditCategory.ADMIN, "INVITE_CREATE", AuditOutcome.OK).withActor(admin)
        .withMeta(RequestMetas.of(request, admin)).withTarget("invite", created.inviteId())
        .withDetail("maxUses", body.maxUses()).withDetail("expiresAt", created.expiresAt().toString()));
    return created;
  }

  @GetMapping
  @Operation(summary = "Invite links", description = "Newest first, used-up, expired and revoked ones included.")
  public List<InviteDto> list() {
    return invites.list();
  }

  @DeleteMapping("/{inviteId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Revoke an invite link", description = "Immediate. Errors: NOT_FOUND.")
  public void revoke(@PathVariable String inviteId, HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    invites.revoke(inviteId);
    audit.record(AuditEntry.of(AuditCategory.ADMIN, "INVITE_REVOKE", AuditOutcome.OK).withActor(admin)
        .withMeta(RequestMetas.of(request, admin)).withTarget("invite", inviteId));
  }
}
