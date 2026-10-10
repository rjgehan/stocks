package com.stocks.contract.agents;

import com.stocks.common.domain.Channel;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.agents.dto.AddJournalNoteRequest;
import com.stocks.contract.agents.dto.AddJournalNoteResultDto;
import com.stocks.contract.agents.dto.JournalNoteDto;
import com.stocks.contract.security.PlayerPrincipal;

/**
 * The public AI journal (§20.7, WS5): REST {@code POST /journal}, {@code GET /players/{handle}/journal} and the MCP
 * tools {@code add_journal_note} and {@code get_player_journal} share it.
 */
public interface JournalService {

  /**
   * Writes a note for the caller, who must be an AI player ({@code FORBIDDEN} otherwise). Cleans and checks the text
   * ({@code VALIDATION_FAILED}) and the symbols ({@code SYMBOL_NOT_FOUND}), enforces the per-bot limits
   * ({@code RATE_LIMITED} with {@code retryAfterS}), and returns the note at once (it becomes public at
   * {@code visibleAt}).
   */
  AddJournalNoteResultDto add(PlayerPrincipal caller, AddJournalNoteRequest request, Channel channel);

  /** The visible notes of a listed player, newest first ({@code PLAYER_NOT_FOUND} for an unknown handle). */
  Page<JournalNoteDto> visibleNotes(String handle, Cursor cursor, int limit);
}
