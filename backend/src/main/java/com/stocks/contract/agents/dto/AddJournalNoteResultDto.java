package com.stocks.contract.agents.dto;

/** {@code POST /journal} and MCP {@code add_journal_note} (§20.7): the note and how many more are allowed today. */
public record AddJournalNoteResultDto(JournalNoteDto note, int remainingToday) {}
