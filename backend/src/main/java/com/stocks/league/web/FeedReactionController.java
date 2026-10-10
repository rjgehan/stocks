package com.stocks.league.web;

import com.stocks.contract.portfolio.FeedReactionService;
import com.stocks.contract.portfolio.dto.FeedReactionsDto;
import com.stocks.contract.portfolio.dto.SetReactionRequest;
import com.stocks.contract.security.CurrentPlayer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 2 feed reactions (§16.11; §18.2 {@code PUT}/{@code DELETE /feed/{itemId}/reaction}, scope {@code trade}): one
 * emoji per player per visible feed item. Both answer the item's {@code FeedReactions}, also pushed on
 * {@code /topic/feed}.
 */
@RestController
@RequestMapping("/api/v1/feed")
@Tag(name = "league", description = "Leaderboard, players and the league feed")
public class FeedReactionController {

  private final FeedReactionService reactions;

  public FeedReactionController(FeedReactionService reactions) {
    this.reactions = reactions;
  }

  @PutMapping("/{itemId}/reaction")
  @Operation(summary = "React to a feed item", description = "Sets your reaction (ROCKET, FIRE, GEM, CLAP, LAUGH or "
      + "SKULL), replacing any other one; idempotent. NOT_FOUND for an item you cannot see.")
  public FeedReactionsDto setReaction(@PathVariable("itemId") String itemId,
                                      @Valid @RequestBody SetReactionRequest request) {
    return reactions.react(CurrentPlayer.require().playerId(), itemId, request.reaction());
  }

  @DeleteMapping("/{itemId}/reaction")
  @Operation(summary = "Remove your reaction", description = "Idempotent: removing nothing is fine.")
  public FeedReactionsDto removeReaction(@PathVariable("itemId") String itemId) {
    return reactions.unreact(CurrentPlayer.require().playerId(), itemId);
  }
}
