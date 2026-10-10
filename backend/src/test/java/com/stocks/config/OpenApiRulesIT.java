package com.stocks.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.stocks.common.domain.ChartRange;
import com.stocks.common.domain.HistoryRange;
import com.stocks.common.web.Page;
import com.stocks.contract.marketdata.dto.BarDto;
import com.stocks.contract.marketdata.dto.BarsDto;
import com.stocks.contract.portfolio.dto.PortfolioHistoryDto;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.testkit.TestClockConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The §18 OpenAPI generation rules, checked on {@code /v3/api-docs} with test endpoints that use real contract DTOs
 * (decimals, nested records, a {@code return} member, wire-value enums, a generic {@code Page}, a request with
 * {@code @Schema} required fields). Rule 5 is asserted the same way CI's {@code openapi-assert.mjs} does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OpenApiRulesIT.Endpoints.class)
class OpenApiRulesIT {

  @TestConfiguration(proxyBeanMethods = false)
  @Import(TestClockConfig.class)
  static class Endpoints {
    @TestComponent
    @RestController
    static class OpenApiProbe {
      @PostMapping("/api/v1/__openapi/orders")
      PlaceOrderResponse place(@RequestBody PlaceOrderRequest request) {
        return null;
      }

      @GetMapping("/api/v1/__openapi/history")
      PortfolioHistoryDto history(@RequestParam HistoryRange range) {
        return null;
      }

      @GetMapping("/api/v1/__openapi/bars")
      BarsDto bars(@RequestParam ChartRange range) {
        return null;
      }

      @GetMapping("/api/v1/__openapi/page")
      Page<BarDto> page() {
        return null;
      }
    }
  }

  @Autowired MockMvc mvc;
  @Autowired JsonMapper jsonMapper;
  JsonNode doc;

  @BeforeEach
  void load() throws Exception {
    String body = mvc.perform(get("/v3/api-docs").with(user("openapi"))).andReturn().getResponse()
        .getContentAsString();
    doc = jsonMapper.readTree(body);
  }

  JsonNode schema(String name) {
    JsonNode s = doc.path("components").path("schemas").path(name);
    assertThat(s.isMissingNode()).as("schema %s", name).isFalse();
    return s;
  }

  static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.isNull() ? null : n.asString()));
    return out;
  }

  @Test
  void isOpenApi31WithAStableHeader() {
    assertThat(doc.path("openapi").asString()).startsWith("3.1");
    assertThat(doc.path("info").path("version").asString()).isEqualTo(OpenApiConfig.API_VERSION);
    assertThat(doc.path("servers").get(0).path("url").asString()).isEqualTo("/");
    assertThat(doc.path("components").path("securitySchemes").has("apiKey")).isTrue();
    assertThat(doc.path("paths").has("/api/v1/me")).isTrue();
    assertThat(doc.path("paths").has("/api/v1/test/clock")).as("@Hidden").isFalse();
    assertThat(doc.path("paths").has("/mcp")).isFalse();
  }

  @Test
  void rule1_decimalsAreStrings() {
    JsonNode o = schema("Bar").path("properties").path("o");
    assertThat(strings(o.path("type"))).containsExactly("string", "null");
    assertThat(o.path("format").asString()).isEqualTo("decimal");
  }

  @Test
  void rule2_responseRecordsAreFullyRequiredAndNullable() {
    JsonNode bar = schema("Bar");
    assertThat(strings(bar.path("required"))).containsExactlyInAnyOrder("t", "o", "h", "l", "c", "v", "vw");
    assertThat(bar.path("properties").path("v").path("type").asString()).as("primitive long").isEqualTo("integer");
    JsonNode summary = schema("HistorySummary");
    assertThat(summary.path("properties").has("return")).isTrue();
    assertThat(summary.path("properties").has("ret")).isFalse();
    assertThat(strings(summary.path("required"))).contains("return");
    JsonNode nested = schema("PortfolioHistory").path("properties").path("summary");
    assertThat(nested.path("anyOf").get(0).path("$ref").asString()).isEqualTo("#/components/schemas/HistorySummary");
    assertThat(nested.path("anyOf").get(1).path("type").asString()).isEqualTo("null");
    JsonNode page = schema("PageBar");
    assertThat(strings(page.path("required"))).containsExactlyInAnyOrder("items", "nextCursor");
  }

  @Test
  void rule2_requestRecordsRequireOnlyAnnotatedMembers() {
    JsonNode req = schema("PlaceOrderRequest");
    assertThat(strings(req.path("required"))).containsExactlyInAnyOrder("clientOrderId", "symbol", "side", "type");
    assertThat(strings(req.path("properties").path("qty").path("type"))).containsExactly("string", "null");
    assertThat(strings(schema("LoginRequest").path("required"))).containsExactlyInAnyOrder("handle", "password");
  }

  @Test
  void rule3_wireValueEnumsPublishWireValues() {
    assertThat(strings(schema("PortfolioHistory").path("properties").path("range").path("enum")))
        .contains("1D", "1W", "YTD", "ALL").doesNotContain("D1", "W1");
    assertThat(strings(schema("Bars").path("properties").path("timeframe").path("enum")))
        .contains("1Min", "1Day").doesNotContain("MIN1");
    JsonNode param = doc.path("paths").path("/api/v1/__openapi/bars").path("get").path("parameters").get(0);
    assertThat(strings(param.path("schema").path("enum"))).contains("1D", "5D", "5Y").doesNotContain("D5");
  }

  @Test
  void rule4_schemaNamesDropTheDtoSuffixAndEveryRefResolves() {
    Set<String> names = new HashSet<>();
    doc.path("components").path("schemas").propertyNames().forEach(names::add);
    assertThat(names).contains("Me", "Preferences", "Order", "Estimate", "Fees", "Bar", "Bars", "Problem")
        .noneMatch(n -> n.endsWith("Dto"));
    List<String> refs = new ArrayList<>();
    collect(doc, "$ref", refs);
    assertThat(refs).isNotEmpty().allSatisfy(ref -> {
      assertThat(ref).startsWith("#/components/schemas/");
      assertThat(names).contains(ref.substring("#/components/schemas/".length()));
    });
  }

  @Test
  void rule5_noNumberTypeAndEveryResponsePropertyRequired() {
    List<String> types = new ArrayList<>();
    collect(doc, "type", types);
    assertThat(types).doesNotContain("number");
    List<String> violations = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    doc.path("paths").properties().forEach(path -> path.getValue().properties().forEach(op ->
        op.getValue().path("responses").properties().forEach(resp ->
            resp.getValue().path("content").properties().forEach(media ->
                checkRequired(media.getValue().path("schema"), path.getKey(), visited, violations)))));
    assertThat(violations).isEmpty();
  }

  @Test
  void everyOperationDocumentsTheProblemResponse() {
    doc.path("paths").properties().forEach(path -> path.getValue().properties().forEach(op ->
        assertThat(op.getValue().path("responses").path("default").path("content")
            .path("application/problem+json").path("schema").path("$ref").asString())
            .as("%s %s", op.getKey(), path.getKey()).isEqualTo("#/components/schemas/Problem")));
    assertThat(strings(schema("Problem").path("required")))
        .containsExactlyInAnyOrder("type", "title", "status", "code", "detail", "hint", "fields", "retryAfterS",
            "requestId");
  }

  private void checkRequired(JsonNode schema, String label, Set<String> visited, List<String> violations) {
    checkRequired(schema, label, visited, violations, Set.of());
  }

  /**
   * Like {@code .github/scripts/openapi-assert.mjs}: {@code allOf} members share the composite's {@code required}
   * (springdoc puts a polymorphic subtype's {@code required} next to its {@code allOf}); oneOf/anyOf stand alone.
   */
  private void checkRequired(JsonNode schema, String label, Set<String> visited, List<String> violations,
                             Set<String> inherited) {
    if (schema == null || !schema.isObject()) {
      return;
    }
    if (schema.has("$ref")) {
      String ref = schema.path("$ref").asString();
      if (visited.add(ref + "|" + new java.util.TreeSet<>(inherited))) {
        String name = ref.substring(ref.lastIndexOf('/') + 1);
        checkRequired(doc.path("components").path("schemas").path(name), name, visited, violations, inherited);
      }
      return;
    }
    Set<String> required = new HashSet<>(strings(schema.path("required")));
    required.addAll(inherited);
    if (schema.has("properties")) {
      schema.path("properties").properties().forEach(p -> {
        if (!required.contains(p.getKey())) {
          violations.add(label + "." + p.getKey());
        }
        checkRequired(p.getValue(), label + "." + p.getKey(), visited, violations);
      });
    }
    checkRequired(schema.path("items"), label + "[]", visited, violations);
    checkRequired(schema.path("additionalProperties"), label + "{}", visited, violations);
    schema.path("allOf").forEach(s -> checkRequired(s, label, visited, violations, required));
    for (String k : List.of("anyOf", "oneOf")) {
      schema.path(k).forEach(s -> checkRequired(s, label, visited, violations));
    }
  }

  private static void collect(JsonNode node, String key, List<String> out) {
    if (node.isObject()) {
      for (Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        if (e.getKey().equals("enum") || e.getKey().equals("example") || e.getKey().equals("default")) {
          continue;
        }
        if (e.getKey().equals(key)) {
          if (e.getValue().isArray()) {
            e.getValue().forEach(v -> out.add(v.asString()));
          } else if (e.getValue().isString()) {
            out.add(e.getValue().asString());
          }
        }
        collect(e.getValue(), key, out);
      }
    } else if (node.isArray()) {
      node.forEach(n -> collect(n, key, out));
    }
  }
}
