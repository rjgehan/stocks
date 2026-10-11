package com.stocks.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.config.JacksonConfig;
import com.stocks.contract.security.Scopes;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import tools.jackson.databind.json.JsonMapper;

/** §20.3: the tool list follows the catalog order, rejects unknown and duplicate names, and has all 40 names. */
class McpToolCatalogTest {

  static final JsonMapper MAPPER = JacksonConfig.newMapper();

  public record Out(String value) {}

  /** Declared (and alphabetically named) in the opposite order of §20.3. */
  public static class Reversed {
    @McpTool(name = "place_order", generateOutputSchema = true)
    public Out a(McpTransportContext ctx) {
      return new Out("a");
    }

    @McpTool(name = "get_rules", generateOutputSchema = true)
    public Out b(McpTransportContext ctx) {
      return new Out("b");
    }
  }

  public static class First {
    @McpTool(name = "whoami", generateOutputSchema = true)
    public Out z(McpTransportContext ctx) {
      return new Out("z");
    }
  }

  public static class Unknown {
    @McpTool(name = "transfer_cash", generateOutputSchema = true)
    public Out x(McpTransportContext ctx) {
      return new Out("x");
    }
  }

  @Test
  void toolsAreListedInCatalogOrderWhateverTheBeanOrder() {
    List<SyncToolSpecification> specs = McpToolCatalog.specifications(List.of(new Reversed(), new First()), MAPPER);
    assertThat(specs).extracting(s -> s.tool().name()).containsExactly("whoami", "get_rules", "place_order");
  }

  @Test
  void aToolOutsideTheCatalogFailsTheBoot() {
    assertThatThrownBy(() -> McpToolCatalog.specifications(List.of(new Unknown()), MAPPER))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("transfer_cash");
  }

  @Test
  void duplicateNamesFailTheBoot() {
    assertThatThrownBy(() -> McpToolCatalog.specifications(List.of(new First(), new First()), MAPPER))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
  }

  @Test
  void everyToolHasTheScopeRestRequiresForTheSameData() {
    assertThat(McpToolCatalog.SCOPES).containsOnlyKeys(McpToolCatalog.ORDER);
    assertThat(McpToolCatalog.SCOPES.values()).allMatch(s -> s.isEmpty() || Scopes.ALL.contains(s));
    assertThat(McpToolCatalog.SCOPES).containsEntry("whoami", McpToolCatalog.NO_SCOPE)
        .containsEntry("get_rules", Scopes.MARKET_READ).containsEntry("get_bars", Scopes.MARKET_READ)
        .containsEntry("get_account", Scopes.ACCOUNT_READ).containsEntry("preview_order", Scopes.ACCOUNT_READ)
        .containsEntry("get_watchlists", Scopes.ACCOUNT_READ)
        .containsEntry("mark_notifications_read", Scopes.ACCOUNT_READ)
        .containsEntry("get_leaderboard", Scopes.LEAGUE_READ).containsEntry("get_league_feed", Scopes.LEAGUE_READ)
        .containsEntry("place_order", Scopes.TRADE).containsEntry("update_watchlist", Scopes.TRADE)
        .containsEntry("delete_watchlist", Scopes.TRADE);
  }

  @Test
  void theCatalogHasThe40ToolsOfSection20Point3AndNoAdminTool() {
    // 38 Phase 1 tools, then the Phase 2 journal tools appended (§20.3 #39-#40), so the Phase 1 order never changes
    assertThat(McpToolCatalog.ORDER).hasSize(40).doesNotHaveDuplicates().startsWith("whoami", "get_rules")
        .endsWith("mark_notifications_read", "get_player_journal", "add_journal_note")
        .noneMatch(n -> n.contains("admin"));
    assertThat(McpToolCatalog.ORDER.get(37)).isEqualTo("mark_notifications_read");
    assertThat(McpToolCatalog.SCOPES).containsEntry("get_player_journal", Scopes.LEAGUE_READ)
        .containsEntry("add_journal_note", Scopes.TRADE);
  }
}
