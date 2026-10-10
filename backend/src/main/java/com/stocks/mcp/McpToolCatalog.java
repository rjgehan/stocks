package com.stocks.mcp;

import com.stocks.contract.agents.ApiAccessRecorder;
import com.stocks.contract.security.Scopes;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the MCP tool list from the {@code @McpTool} methods of the {@code com.stocks.mcp.tools} beans (§20.1):
 * name, title, description and the four hints from the annotation; the input schema from Spring AI's generator,
 * post-processed by {@link McpInputSchemas} (decimals as strings or numbers, wire-value enums); the
 * output schema from the return type, post-processed by {@link McpOutputSchemas}; the call handler is
 * {@link McpToolHandler} (Boot's {@code JsonMapper}). Tools are registered in the <b>fixed order of §20.3</b>
 * ({@link #ORDER}); a tool that is not in the catalog fails the boot. Every tool has a required scope
 * ({@link #SCOPES}, the "Scope" column of §20.3, mirroring the REST routes of §17.1), checked by
 * {@link McpToolHandler} on every call; a tool without an entry fails the boot.
 */
public final class McpToolCatalog {

  /** §20.3, in order. */
  public static final List<String> ORDER = List.of(
      // read tools
      "whoami", "get_rules", "get_market_clock", "get_calendar", "search_symbols", "get_asset", "get_quotes",
      "get_snapshot", "get_bars", "get_movers", "get_news", "get_fundamentals", "get_account", "get_positions",
      "get_orders", "get_order", "get_executions", "get_activity", "get_violations", "get_runs",
      "get_portfolio_history", "get_notifications", "preview_order", "get_leaderboard", "list_players",
      "get_player_profile", "get_player_history", "get_player_trades", "get_league_feed", "get_watchlists",
      // write tools
      "place_order", "replace_order", "cancel_order", "cancel_all_orders", "close_position", "update_watchlist",
      "delete_watchlist", "mark_notifications_read",
      // Phase 2 (§20.3 #39-#40, appended so the Phase 1 order never changes): the public AI journal (§20.7)
      "get_player_journal", "add_journal_note");

  /** {@link #SCOPES} value of a tool any authenticated caller may use ({@code whoami}). */
  public static final String NO_SCOPE = "";

  /**
   * The scope each tool needs (§20.3), as REST requires it for the same data (§17.1): a key without it gets
   * {@code FORBIDDEN_SCOPE}. A browser session holds all four scopes.
   */
  public static final Map<String, String> SCOPES = scopes();

  private static Map<String, String> scopes() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("whoami", NO_SCOPE);   // its account member is null without account:read (AccountIdentityTools)
    for (String t : List.of("get_rules", "get_market_clock", "get_calendar", "search_symbols", "get_asset",
        "get_quotes", "get_snapshot", "get_bars", "get_movers", "get_news", "get_fundamentals")) {
      m.put(t, Scopes.MARKET_READ);
    }
    for (String t : List.of("get_account", "get_positions", "get_orders", "get_order", "get_executions",
        "get_activity", "get_violations", "get_runs", "get_portfolio_history", "get_notifications", "preview_order",
        "get_watchlists", "mark_notifications_read")) {
      m.put(t, Scopes.ACCOUNT_READ);
    }
    for (String t : List.of("get_leaderboard", "list_players", "get_player_profile", "get_player_history",
        "get_player_trades", "get_league_feed", "get_player_journal")) {
      m.put(t, Scopes.LEAGUE_READ);
    }
    for (String t : List.of("place_order", "replace_order", "cancel_order", "cancel_all_orders", "close_position",
        "update_watchlist", "delete_watchlist", "add_journal_note")) {
      m.put(t, Scopes.TRADE);
    }
    if (!m.keySet().equals(new HashSet<>(ORDER))) {
      throw new IllegalStateException("McpToolCatalog.SCOPES must name exactly the tools of ORDER");
    }
    return Collections.unmodifiableMap(m);
  }

  private McpToolCatalog() {}

  /** One specification per {@code @McpTool} method of {@code toolBeans}, in §20.3 order. */
  public static List<SyncToolSpecification> specifications(List<?> toolBeans, JsonMapper mapper) {
    return specifications(toolBeans, mapper, null);
  }

  /** As above; with {@code recorder}, every API-key call is counted in the API access log (§17.10). */
  public static List<SyncToolSpecification> specifications(List<?> toolBeans, JsonMapper mapper,
                                                           ApiAccessRecorder recorder) {
    Map<String, SyncToolSpecification> byName = new LinkedHashMap<>();
    for (Object bean : toolBeans) {
      Class<?> type = AopUtils.getTargetClass(bean);
      List<Method> methods = new ArrayList<>();
      for (Method m : type.getDeclaredMethods()) {
        if (m.isAnnotationPresent(McpTool.class)) {
          methods.add(m);
        }
      }
      methods.sort(Comparator.comparing(Method::getName));
      for (Method m : methods) {
        SyncToolSpecification spec = specification(bean, m, mapper, recorder);
        if (byName.putIfAbsent(spec.tool().name(), spec) != null) {
          throw new IllegalStateException("Duplicate MCP tool name '" + spec.tool().name() + "' (" + m + ")");
        }
      }
    }
    List<String> unknown = byName.keySet().stream().filter(n -> !ORDER.contains(n)).toList();
    if (!unknown.isEmpty()) {
      throw new IllegalStateException("MCP tools " + unknown + " are not in the §20.3 catalog (McpToolCatalog.ORDER)");
    }
    return ORDER.stream().filter(byName::containsKey).map(byName::get).toList();
  }

  /** The specification of one catalog tool; its scope comes from {@link #SCOPES} (none = boot failure). */
  static SyncToolSpecification specification(Object bean, Method method, JsonMapper mapper) {
    return specification(bean, method, mapper, (ApiAccessRecorder) null);
  }

  static SyncToolSpecification specification(Object bean, Method method, JsonMapper mapper,
                                             ApiAccessRecorder recorder) {
    String name = toolName(method);
    String scope = SCOPES.get(name);
    if (scope == null) {
      throw new IllegalStateException("MCP tool " + name + " has no required scope in McpToolCatalog.SCOPES (§20.3)");
    }
    return specification(bean, method, mapper, scope, recorder);
  }

  /**
   * The specification of one tool that needs {@code requiredScope} ({@link #NO_SCOPE} for none). Catalog tools go
   * through {@link #specification(Object, Method, JsonMapper)}; this overload exists for test-only tools.
   */
  static SyncToolSpecification specification(Object bean, Method method, JsonMapper mapper, String requiredScope) {
    return specification(bean, method, mapper, requiredScope, null);
  }

  private static SyncToolSpecification specification(Object bean, Method method, JsonMapper mapper,
                                                     String requiredScope, ApiAccessRecorder recorder) {
    McpTool annotation = method.getAnnotation(McpTool.class);
    String name = toolName(method);
    if (!Modifier.isPublic(method.getModifiers())) {
      throw new IllegalStateException("MCP tool " + name + " must be public: " + method);
    }
    if (!annotation.generateOutputSchema()) {
      throw new IllegalStateException("§20.2: MCP tool " + name + " must return a DTO with generateOutputSchema = "
          + "true (structuredContent + outputSchema): " + method);
    }
    McpTool.McpAnnotations hints = annotation.annotations();
    String title = StringUtils.hasText(annotation.title()) ? annotation.title()
        : StringUtils.hasText(hints.title()) ? hints.title() : name;
    McpSchema.Tool tool = McpSchema.Tool.builder(name, McpInputSchemas.forMethod(method, mapper))
        .title(title)
        .description(annotation.description())
        .outputSchema(McpOutputSchemas.forReturnType(method.getGenericReturnType(), mapper))
        .annotations(McpSchema.ToolAnnotations.builder()
            .title(StringUtils.hasText(hints.title()) ? hints.title() : title)
            .readOnlyHint(hints.readOnlyHint())
            .destructiveHint(hints.destructiveHint())
            .idempotentHint(hints.idempotentHint())
            .openWorldHint(hints.openWorldHint())
            .build())
        .build();
    return SyncToolSpecification.builder().tool(tool)
        .callHandler(new McpToolHandler(name, requiredScope, bean, method, mapper, recorder))
        .build();
  }

  private static String toolName(Method method) {
    McpTool annotation = method.getAnnotation(McpTool.class);
    return StringUtils.hasText(annotation.name()) ? annotation.name() : method.getName();
  }
}
