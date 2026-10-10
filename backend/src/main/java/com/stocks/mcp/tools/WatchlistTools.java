package com.stocks.mcp.tools;

import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.watchlist.WatchlistService;
import com.stocks.contract.watchlist.dto.WatchlistDto;
import com.stocks.mcp.tools.McpOutputs.DeletedDto;
import com.stocks.mcp.tools.McpOutputs.WatchlistsDto;
import io.modelcontextprotocol.common.McpTransportContext;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Watchlist tools (§20.3 #30, #36, #37): read with {@code account:read}, change with {@code trade} (as REST
 * {@code /watchlists}, §17.1). Lists are addressed by name, which agents can remember, through WS4's
 * {@code WatchlistService}.
 */
@Component
public class WatchlistTools implements McpToolSet {

  static final int NAME_MAX = 40;
  static final int MAX_SYMBOLS_PER_CALL = 50;

  private final WatchlistService watchlists;

  public WatchlistTools(WatchlistService watchlists) {
    this.watchlists = watchlists;
  }

  @McpTool(name = "get_watchlists", title = "Watchlists",
      description = "Your watchlists in display order, each with its symbols in order.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Watchlists", readOnlyHint = true, destructiveHint = false,
          idempotentHint = true, openWorldHint = false))
  public WatchlistsDto get_watchlists(McpTransportContext ctx) {
    return new WatchlistsDto(watchlists.list(McpArgs.caller(ctx).playerId()));
  }

  @McpTool(name = "update_watchlist", title = "Update a watchlist",
      description = "Adds and/or removes symbols on your watchlist called name, creating the list if it does not "
          + "exist. Adding a symbol that is already there, or removing one that is not, changes nothing. "
          + "SYMBOL_NOT_FOUND for an unknown ticker. Returns the list.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Update a watchlist", readOnlyHint = false,
          destructiveHint = false, idempotentHint = true, openWorldHint = false))
  public WatchlistDto update_watchlist(McpTransportContext ctx,
      @McpToolParam(description = "Watchlist name, 1-40 characters, e.g. \"My List\"") String name,
      @McpToolParam(required = false, description = "Tickers to append (up to 50)") List<String> add,
      @McpToolParam(required = false, description = "Tickers to remove (up to 50)") List<String> remove) {
    PlayerPrincipal caller = McpArgs.caller(ctx);
    String list = McpArgs.text(name, NAME_MAX, "name");
    List<String> toAdd = add == null || add.isEmpty() ? List.of()
        : McpArgs.symbols(add, 1, MAX_SYMBOLS_PER_CALL, "add");
    List<String> toRemove = remove == null || remove.isEmpty() ? List.of()
        : McpArgs.symbols(remove, 1, MAX_SYMBOLS_PER_CALL, "remove");
    return watchlists.upsertByName(caller.playerId(), list, toAdd, toRemove);
  }

  @McpTool(name = "delete_watchlist", title = "Delete a watchlist",
      description = "Deletes your watchlist called name. Returns deleted=false if there was no such list.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Delete a watchlist", readOnlyHint = false,
          destructiveHint = true, idempotentHint = true, openWorldHint = false))
  public DeletedDto delete_watchlist(McpTransportContext ctx,
      @McpToolParam(description = "Watchlist name") String name) {
    PlayerPrincipal caller = McpArgs.caller(ctx);
    String list = McpArgs.text(name, NAME_MAX, "name");
    return new DeletedDto(watchlists.deleteByName(caller.playerId(), list));
  }
}
