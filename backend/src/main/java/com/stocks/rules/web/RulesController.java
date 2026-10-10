package com.stocks.rules.web;

import com.stocks.contract.rules.dto.RulesDto;
import com.stocks.rules.RulesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** §18.1 {@code GET /rules} ({@code market:read}): the league rulebook, the same document as MCP {@code get_rules}. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "rules", description = "The league rulebook (bots read this first)")
public class RulesController {

  private final RulesService rules;

  public RulesController(RulesService rules) {
    this.rules = rules;
  }

  @GetMapping("/rules")
  @Operation(summary = "The rulebook", description = "Account, session, order, execution, fee, rate-limit, "
      + "leaderboard and fairness rules as configured now. version changes whenever a rule changes; "
      + "docs/rules.md explains them for people.")
  public RulesDto rules() {
    return rules.rules();
  }
}
