package com.stocks.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.web.Page;
import com.stocks.contract.agents.ApiAccessRecorder;
import com.stocks.contract.agents.dto.AddJournalNoteRequest;
import com.stocks.contract.agents.dto.AddJournalNoteResultDto;
import com.stocks.contract.agents.dto.JournalNoteDto;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.Scopes;
import com.stocks.testkit.TestTimes;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * §20.3 #54-#55 through the real tool handler: scopes, delegation to {@code JournalService} with the MCP channel, the
 * untrusted envelope, and (§17.10) every API-key call counted in the access log with {@code OK} or its error code.
 */
class JournalToolsTest {

  final McpToolHarness h = new McpToolHarness();

  @AfterEach
  void close() {
    h.close();
  }

  static JournalNoteDto note(String text) {
    return new JournalNoteDto("jnl_1", TestTimes.START, TestTimes.START.plusSeconds(900),
        new PlayerSummaryDto("ply_1", "claude", "Claude‮bot", PlayerKind.AI, "Claude", null, TestTimes.START, 0),
        List.of("NVDA"), new JournalNoteDto.UntrustedJournalTextDto(text));
  }

  @Test
  void addJournalNoteDelegatesWithTheMcpChannel() {
    when(h.journal.add(any(), any(), any())).thenReturn(new AddJournalNoteResultDto(note("Buying NVDA"), 11));
    JsonNode r = McpToolHarness.ok(h.call("add_journal_note", Map.of("text", "Buying NVDA", "symbols",
        List.of("nvda"))));
    assertThat(r.get("note").get("untrusted").get("text").asString()).isEqualTo("Buying NVDA");
    assertThat(r.get("note").get("player").get("displayName").asString()).isEqualTo("Claudebot");
    assertThat(r.get("remainingToday").asInt()).isEqualTo(11);
    verify(h.journal).add(any(PlayerPrincipal.class), eq(new AddJournalNoteRequest("Buying NVDA", List.of("nvda"))),
        eq(Channel.MCP_KEY));
  }

  @Test
  void writingNeedsTradeAndReadingNeedsLeagueRead() {
    JsonNode denied = McpToolHarness.error(h.call("add_journal_note", h.keyWith(Scopes.LEAGUE_READ),
        Map.of("text", "x")));
    assertThat(denied.get("code").asString()).isEqualTo("FORBIDDEN_SCOPE");
    JsonNode read = McpToolHarness.error(h.call("get_player_journal", h.keyWith(Scopes.TRADE),
        Map.of("handle", "claude")));
    assertThat(read.get("code").asString()).isEqualTo("FORBIDDEN_SCOPE");
    JsonNode missing = McpToolHarness.error(h.call("add_journal_note", Map.of()));
    assertThat(missing.get("code").asString()).isEqualTo("VALIDATION_FAILED");
    when(h.journal.add(any(), any(), any())).thenThrow(ApiException.of(ErrorCode.RATE_LIMITED, "slow down")
        .withRetryAfter(420));
    JsonNode limited = McpToolHarness.error(h.call("add_journal_note", Map.of("text", "again")));
    assertThat(limited.get("code").asString()).isEqualTo("RATE_LIMITED");
    assertThat(limited.get("retry_after_s").asLong()).isEqualTo(420);
  }

  @Test
  void getPlayerJournalPagesVisibleNotes() {
    when(h.journal.visibleNotes(eq("claude"), any(), anyInt())).thenReturn(new Page<>(List.of(note("x".repeat(600))),
        "next"));
    JsonNode page = McpToolHarness.ok(h.call("get_player_journal", Map.of("handle", "Claude", "limit", 5)));
    assertThat(page.get("items").get(0).get("untrusted").get("text").asString()).hasSize(500);
    assertThat(page.get("nextCursor").asString()).isEqualTo("next");
    verify(h.journal).visibleNotes(eq("claude"), any(), eq(5));
  }

  record Recorded(PlayerPrincipal caller, Channel channel, String endpoint, String outcome, String ip, String client) {}

  @Test
  void everyToolCallOfAKeyIsCountedWithItsResultCode() {
    List<Recorded> calls = new ArrayList<>();
    ApiAccessRecorder recorder = (caller, channel, endpoint, outcome, ms, ip, client) ->
        calls.add(new Recorded(caller, channel, endpoint, outcome, ip, client));
    Map<String, SyncToolSpecification> specs = new java.util.LinkedHashMap<>();
    for (SyncToolSpecification s : McpToolCatalog.specifications(h.toolSets, McpToolHarness.MAPPER, recorder)) {
      specs.put(s.tool().name(), s);
    }
    when(h.journal.add(any(), any(), any())).thenReturn(new AddJournalNoteResultDto(note("ok"), 11));
    PlayerPrincipal bot = h.bot();
    specs.get("add_journal_note").callHandler().apply(CurrentPlayer.mcpContext(bot, McpToolHarness.META),
        new CallToolRequest("add_journal_note", Map.of("text", "ok")));
    specs.get("get_player_journal").callHandler().apply(CurrentPlayer.mcpContext(h.keyWith(Scopes.TRADE),
        McpToolHarness.META), new CallToolRequest("get_player_journal", Map.of("handle", "claude")));
    assertThat(calls).extracting(Recorded::endpoint, Recorded::outcome, Recorded::channel, Recorded::client)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("add_journal_note", "OK", Channel.MCP_KEY, "claude-code"),
            org.assertj.core.groups.Tuple.tuple("get_player_journal", "FORBIDDEN_SCOPE", Channel.MCP_KEY,
                "claude-code"));
    assertThat(calls.get(0).ip()).isEqualTo("127.0.0.1");
  }
}
