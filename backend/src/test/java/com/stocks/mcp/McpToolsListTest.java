package com.stocks.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.testkit.GoldenFiles;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The §20.3 {@code tools/list}: all 41 tools in catalog order, the four hints of every tool as the tables give them,
 * snake_case inputs, schemas that follow §20.2, and a reviewed snapshot ({@code golden/mcp/tools-list.json}; re-record
 * with {@code -Dgolden.update=true} after an intended change and review the diff: it is what every agent sees).
 */
class McpToolsListTest {

  static McpToolHarness harness;
  static List<Tool> tools;

  /** §20.3 write tools, with their {@code destructiveHint}. */
  static final Map<String, Boolean> WRITES = Map.of("place_order", false, "replace_order", true, "cancel_order", true,
      "cancel_all_orders", true, "close_position", true, "update_watchlist", false, "delete_watchlist", true,
      "mark_notifications_read", false, "create_alert", false, "delete_alert", true);

  static final Pattern SNAKE = Pattern.compile("^[a-z][a-z0-9]*(_[a-z0-9]+)*$");

  @org.junit.jupiter.api.AfterAll
  static void closeHarness() {
    harness.close();
  }

  @BeforeAll
  static void build() {
    harness = new McpToolHarness();
    tools = harness.specs.values().stream().map(s -> s.tool()).toList();
  }

  @Test
  void everyCatalogToolIsRegisteredInOrder() {
    assertThat(tools).extracting(Tool::name).containsExactlyElementsOf(McpToolCatalog.ORDER);
  }

  @Test
  void hintsFollowTheCatalogTables() {
    for (Tool t : tools) {
      var a = t.annotations();
      assertThat(a.openWorldHint()).as(t.name()).isFalse();
      assertThat(a.idempotentHint()).as(t.name() + " idempotent").isTrue();
      if (WRITES.containsKey(t.name())) {
        assertThat(a.readOnlyHint()).as(t.name()).isFalse();
        assertThat(a.destructiveHint()).as(t.name() + " destructive").isEqualTo(WRITES.get(t.name()));
      } else {
        assertThat(a.readOnlyHint()).as(t.name()).isTrue();
        assertThat(a.destructiveHint()).as(t.name()).isFalse();
      }
      assertThat(t.title()).as(t.name()).isNotBlank();
      assertThat(t.description()).as(t.name()).hasSizeGreaterThan(40);
    }
  }

  @Test
  void inputsAreSnakeCaseAndDecimalsAcceptStringsOrNumbers() {
    for (Tool t : tools) {
      JsonNode schema = McpToolHarness.MAPPER.valueToTree(t.inputSchema());
      assertThat(schema.get("type").asString()).isEqualTo("object");
      for (String name : schema.get("properties").propertyNames()) {
        assertThat(name).as(t.name()).matches(SNAKE);
      }
      List<String> numbers = new ArrayList<>();
      numbersOutsideDecimals(schema, "$", numbers);
      assertThat(numbers).as(t.name() + ": a bare \"number\" would mean a double").isEmpty();
    }
  }

  @Test
  void outputSchemasAreObjectsWithoutNumbers() {
    JsonSchemaValidator validator = McpJsonDefaults.getSchemaValidator();
    for (Tool t : tools) {
      assertThat(t.outputSchema()).as(t.name()).isNotNull().containsEntry("type", "object");
      String json = McpToolHarness.MAPPER.writeValueAsString(t.outputSchema());
      assertThat(json).as(t.name()).doesNotContain("\"number\"");
      // the SDK asserts both schemas are valid JSON Schema at registration (McpStatelessAsyncServer)
      validator.validate(t.outputSchema(), Map.of());
    }
  }

  @Test
  void theToolListMatchesTheGoldenSnapshot() {
    List<Map<String, Object>> list = new ArrayList<>();
    for (Tool t : tools) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("name", t.name());
      m.put("title", t.title());
      m.put("description", t.description());
      m.put("annotations", McpToolHarness.MAPPER.valueToTree(t.annotations()));
      m.put("inputSchema", t.inputSchema());
      m.put("outputSchema", t.outputSchema());
      list.add(m);
    }
    GoldenFiles.assertMatches("mcp", "tools-list", list);
  }

  @Test
  void noToolNameMentionsAdmin() {
    assertThat(tools).extracting(Tool::name).noneMatch(n -> n.contains("admin"));
    assertThat(Set.copyOf(McpToolCatalog.SCOPES.values())).doesNotContain("admin");
  }

  /** Paths of "number" in a {@code type} that is not the decimal union {@code ["string", "number"]}. */
  static void numbersOutsideDecimals(JsonNode node, String path, List<String> found) {
    if (node.isObject()) {
      JsonNode type = node.get("type");
      if (type != null && type.isString() && "number".equals(type.asString())) {
        found.add(path);
      } else if (type != null && type.isArray()) {
        boolean number = type.valueStream().anyMatch(v -> "number".equals(v.asString()));
        boolean string = type.valueStream().anyMatch(v -> "string".equals(v.asString()));
        if (number && !string) {
          found.add(path);
        }
      }
      for (Map.Entry<String, JsonNode> e : node.properties()) {
        numbersOutsideDecimals(e.getValue(), path + "." + e.getKey(), found);
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        numbersOutsideDecimals(node.get(i), path + "[" + i + "]", found);
      }
    }
  }
}
