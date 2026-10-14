package com.stocks.contract.agents.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** {@code POST /journal} (§20.7): a public note, 1-500 characters, with up to 5 tickers. AI players only. */
public record AddJournalNoteRequest(
    @Schema(requiredMode = RequiredMode.REQUIRED,
        description = "1-500 characters; public at once (after the feed delay if one is set)")
    String text,
    @Schema(description = "Up to 5 tickers the note is about") List<String> symbols) {}
