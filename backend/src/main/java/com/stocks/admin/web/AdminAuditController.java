package com.stocks.admin.web;

import com.stocks.audit.AuditQueries;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.admin.dto.AuditRowDto;
import com.stocks.identity.PlayerRecord;
import com.stocks.identity.PlayerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Optional;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** §21.1 the {@code audit_log} viewer (§17.7): filter by category, action, actor handle and time, newest first. */
@RestController
@RequestMapping("/api/v1/admin/audit")
@Tag(name = "admin", description = "League administration (session + ADMIN only, §21)")
public class AdminAuditController {

  private final AuditQueries audit;
  private final PlayerService players;

  public AdminAuditController(AuditQueries audit, PlayerService players) {
    this.audit = audit;
    this.players = players;
  }

  @GetMapping
  @Operation(summary = "Audit rows",
      description = "category: AUTH, ORDER, MONEY, ADMIN, KEY, ACCOUNT or SYSTEM; action e.g. LOGIN_FAIL; actor a "
          + "player handle; from/to ISO-8601 instants. Paged with cursor/limit (default 50, max 200).")
  public Page<AuditRowDto> list(@RequestParam(required = false) String category,
                                @RequestParam(required = false) String action,
                                @RequestParam(required = false) String actor,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                Instant from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                Instant to,
                                @RequestParam(required = false) String cursor,
                                @RequestParam(required = false) Integer limit) {
    Long actorId = null;
    if (actor != null && !actor.isBlank()) {
      Optional<PlayerRecord> p = players.byHandle(actor);
      if (p.isEmpty()) {
        return Page.empty();
      }
      actorId = p.get().id();
    }
    return audit.list(new AuditQueries.Filter(AuditQueries.category(category), action, actorId, from, to),
        Cursor.parse(cursor), Page.clampLimit(limit));
  }
}
