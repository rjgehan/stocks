package com.stocks.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;

/**
 * §20.3 coverage: AIs can reach everything a player can. Every non-admin {@code GET} in {@code docs/openapi.json} maps
 * to a tool of the catalog or is excluded with a reason in {@code golden/mcp/tool-coverage.yml}; every mapped tool is
 * really registered. The file also lists the GETs other workstreams add in Phase 1, so this test keeps passing as
 * their endpoints land and fails as soon as one lands without a tool.
 */
class McpToolCoverageTest {

  static final Path OPENAPI = Path.of("..", "docs", "openapi.json");

  record Coverage(Map<String, String> tools, Map<String, String> excluded) {}

  @SuppressWarnings("unchecked")
  static Coverage coverage() throws IOException {
    try (InputStream in = McpToolCoverageTest.class.getResourceAsStream("/golden/mcp/tool-coverage.yml")) {
      Map<String, Object> yaml = new Yaml().load(in);
      return new Coverage(normalizeKeys((Map<String, String>) yaml.get("tools")),
          normalizeKeys((Map<String, String>) yaml.get("excluded")));
    }
  }

  static Map<String, String> normalizeKeys(Map<String, String> in) {
    Map<String, String> out = new LinkedHashMap<>();
    in.forEach((k, v) -> out.put(normalize(k), v));
    return out;
  }

  /** {@code /orders/{orderId}} → {@code /orders/{}}: variables match by position. */
  static String normalize(String path) {
    return path.replaceAll("\\{[^}]*}", "{}");
  }

  static List<String> nonAdminGets() throws IOException {
    JsonNode doc = McpToolHarness.MAPPER.readTree(Files.readString(OPENAPI));
    List<String> out = new ArrayList<>();
    for (Map.Entry<String, JsonNode> e : doc.get("paths").properties()) {
      if (e.getValue().has("get") && !e.getKey().startsWith("/api/v1/admin/")) {
        out.add(e.getKey());
      }
    }
    return out;
  }

  @Test
  void everyNonAdminGetHasAToolOrAnExclusion() throws IOException {
    Coverage c = coverage();
    List<String> gets = nonAdminGets();
    assertThat(gets).as("docs/openapi.json").isNotEmpty();
    List<String> uncovered = gets.stream().map(McpToolCoverageTest::normalize)
        .filter(p -> !c.tools().containsKey(p) && !c.excluded().containsKey(p)).toList();
    assertThat(uncovered).as("GET endpoints without an MCP tool: add the tool, or exclude them with a reason in "
        + "golden/mcp/tool-coverage.yml").isEmpty();
  }

  @Test
  void everyMappedToolExistsAndIsRegistered() throws IOException {
    Coverage c = coverage();
    try (McpToolHarness harness = new McpToolHarness()) {
      for (Map.Entry<String, String> e : c.tools().entrySet()) {
        assertThat(McpToolCatalog.ORDER).as(e.getKey()).contains(e.getValue());
        assertThat(harness.specs).as(e.getKey()).containsKey(e.getValue());
      }
    }
    assertThat(c.tools().keySet()).doesNotContainAnyElementsOf(c.excluded().keySet());
    assertThat(c.excluded().values()).allSatisfy(reason -> assertThat(reason).isNotBlank());
  }

  @Test
  void theSpecExclusionListIsExactlyThis() throws IOException {
    // §20.3: "That list is: GET /auth/csrf; GET /me; GET /me/api-keys; GET /assets/{symbol}/logo;
    // GET /market/indices; GET /account/positions/{symbol}; GET /orders/by-client-id/{id}", plus Phase 2's
    // GET /players/{handle}/agent-runs, GET /me/api-calls and GET /stream.
    assertThat(coverage().excluded().keySet()).containsExactlyInAnyOrder(
        normalize("/api/v1/auth/csrf"), normalize("/api/v1/me"), normalize("/api/v1/me/api-keys"),
        normalize("/api/v1/assets/{symbol}/logo"), normalize("/api/v1/market/indices"),
        normalize("/api/v1/account/positions/{symbol}"), normalize("/api/v1/orders/by-client-id/{id}"),
        normalize("/api/v1/players/{handle}/agent-runs"), normalize("/api/v1/me/api-calls"),
        normalize("/api/v1/stream"));
  }
}
