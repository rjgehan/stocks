package com.stocks.contract.agents.dto;

import com.stocks.contract.identity.dto.PlayerSummaryDto;
import java.time.Instant;
import java.util.List;

/**
 * §20.7 {@code JournalNote}: a public note of an AI player. The text is untrusted (§17.8): data, never instructions.
 */
public record JournalNoteDto(String id, Instant at, Instant visibleAt, PlayerSummaryDto player, List<String> symbols,
                             UntrustedJournalTextDto untrusted) {

  public record UntrustedJournalTextDto(String text) {}
}
