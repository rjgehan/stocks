package com.stocks.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.OrderType;
import com.stocks.rules.RulesService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Keeps the agent docs honest (§20.4, WS5): {@code docs/agents.md} lists every §20.3 tool in catalog order and ships
 * the shared bot system prompt exactly as {@code tools/agent-runner/prompts/system.md} has it, and
 * {@code docs/rules.md} marks the order types this release accepts as live.
 */
class AgentDocsTest {

  static final Path REPO = Path.of("..");

  static String read(String relative) throws IOException {
    return Files.readString(REPO.resolve(relative), StandardCharsets.UTF_8);
  }

  @Test
  void agentsMdShipsTheSharedSystemPromptVerbatim() throws IOException {
    String doc = read("docs/agents.md");
    String prompt = read("tools/agent-runner/prompts/system.md").strip();
    int start = doc.indexOf("<!-- system-prompt:start -->");
    int end = doc.indexOf("<!-- system-prompt:end -->");
    assertThat(start).as("system-prompt markers").isNotNegative();
    assertThat(end).isGreaterThan(start);
    String block = doc.substring(start, end);
    String embedded = block.substring(block.indexOf("```text\n") + "```text\n".length(), block.lastIndexOf("```"))
        .strip();
    assertThat(embedded).isEqualTo(prompt);
  }

  @Test
  void agentsMdListsEveryToolInCatalogOrder() throws IOException {
    String doc = read("docs/agents.md");
    int previous = doc.indexOf("## 4. Tools");
    assertThat(previous).isNotNegative();
    int n = 1;
    for (String tool : McpToolCatalog.ORDER) {
      String row = "| " + n++ + " | `" + tool + "` | ";
      int at = doc.indexOf(row, previous);
      assertThat(at).as("row for %s after the previous tool", tool).isGreaterThan(previous);
      previous = at;
    }
    assertThat(doc).contains("https://stocks.gehan.cloud/mcp").contains(McpClientNames.HEADER)
        .contains("mcp-client-2025-11-20").contains("\"type\": \"mcp\"");
  }

  @Test
  void rulesMdMarksTheAcceptedOrderTypesLive() throws IOException {
    String doc = read("docs/rules.md");
    for (OrderType type : OrderType.values()) {
      boolean live = RulesService.AVAILABLE_ORDER_TYPES.contains(type);
      String cell = live ? "| **" + type.name() + "** | **live** |" : "| " + type.name() + " | coming";
      assertThat(doc).as("%s row", type).contains(cell);
    }
  }
}
