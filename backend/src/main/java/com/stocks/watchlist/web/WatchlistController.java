package com.stocks.watchlist.web;

import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.watchlist.WatchlistService;
import com.stocks.contract.watchlist.dto.AddWatchlistSymbolRequest;
import com.stocks.contract.watchlist.dto.CreateWatchlistRequest;
import com.stocks.contract.watchlist.dto.UpdateWatchlistRequest;
import com.stocks.contract.watchlist.dto.WatchlistDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §18.2 {@code /watchlists/**}: reads need {@code account:read}, writes {@code trade} (the security chain checks it,
 * §17.1). Lists belong to the caller; another player's list id is {@code NOT_FOUND}.
 */
@RestController
@RequestMapping("/api/v1/watchlists")
@Tag(name = "watchlists", description = "The caller's watchlists")
public class WatchlistController {

  private final WatchlistService watchlists;

  public WatchlistController(WatchlistService watchlists) {
    this.watchlists = watchlists;
  }

  private static long me() {
    return CurrentPlayer.require().playerId();
  }

  @GetMapping
  @Operation(summary = "The caller's watchlists", description = "In sort order, each with its symbols in order.")
  public List<WatchlistDto> listWatchlists() {
    return watchlists.list(me());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create a watchlist", description = "Name 1-40 characters, unique per player; unknown "
      + "symbols answer SYMBOL_NOT_FOUND.")
  public WatchlistDto createWatchlist(@Valid @RequestBody CreateWatchlistRequest request) {
    return watchlists.create(me(), request.name(), request.symbols());
  }

  @PutMapping("/{id}")
  @Operation(summary = "Rename a watchlist and replace its symbols", description = "Array order = sort order.")
  public WatchlistDto updateWatchlist(@PathVariable("id") String id,
                                      @Valid @RequestBody UpdateWatchlistRequest request) {
    return watchlists.replace(me(), id, request.name(), request.symbols());
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete a watchlist")
  public void deleteWatchlist(@PathVariable("id") String id) {
    watchlists.delete(me(), id);
  }

  @PostMapping("/{id}/symbols")
  @Operation(summary = "Add a symbol at the end of a watchlist", description = "Idempotent if already present.")
  public WatchlistDto addWatchlistSymbol(@PathVariable("id") String id,
                                         @Valid @RequestBody AddWatchlistSymbolRequest request) {
    return watchlists.addSymbol(me(), id, request.symbol());
  }

  @DeleteMapping("/{id}/symbols/{symbol}")
  @Operation(summary = "Remove a symbol from a watchlist", description = "Idempotent if absent.")
  public WatchlistDto removeWatchlistSymbol(@PathVariable("id") String id, @PathVariable("symbol") String symbol) {
    return watchlists.removeSymbol(me(), id, symbol);
  }
}
