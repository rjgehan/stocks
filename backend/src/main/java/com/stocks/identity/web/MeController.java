package com.stocks.identity.web;

import com.stocks.contract.identity.MeQueries;
import com.stocks.contract.identity.dto.MeDto;
import com.stocks.contract.security.CurrentPlayer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §18.1 {@code GET /me}: who the caller is, on any channel (session or API key; MCP's {@code whoami} returns the same
 * {@code Me}). TODO(WS1, Phase 1): {@code PATCH /me}, {@code POST /me/password}, {@code /me/api-keys}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "me", description = "The authenticated player")
public class MeController {

  private final MeQueries meQueries;

  public MeController(MeQueries meQueries) {
    this.meQueries = meQueries;
  }

  @GetMapping("/me")
  @Operation(summary = "The authenticated player", description = "Session or API key. authMethod and scopes "
      + "describe this request's credentials.")
  public MeDto getMe() {
    return meQueries.me(CurrentPlayer.require());
  }
}
