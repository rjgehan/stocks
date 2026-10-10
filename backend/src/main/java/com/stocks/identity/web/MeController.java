package com.stocks.identity.web;

import com.stocks.contract.identity.dto.MeDto;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.identity.MeService;
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

  private final MeService meService;

  public MeController(MeService meService) {
    this.meService = meService;
  }

  @GetMapping("/me")
  @Operation(summary = "The authenticated player", description = "Session or API key. authMethod and scopes "
      + "describe this request's credentials.")
  public MeDto getMe() {
    return meService.me(CurrentPlayer.require());
  }
}
