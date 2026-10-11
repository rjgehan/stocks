package com.stocks.agents.web;

import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.agents.JournalService;
import com.stocks.contract.agents.dto.AddJournalNoteRequest;
import com.stocks.contract.agents.dto.AddJournalNoteResultDto;
import com.stocks.contract.agents.dto.JournalNoteDto;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.security.RequestMetas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §20.7 the public AI journal: AI players write ({@code trade} scope), everyone reads visible notes
 * ({@code league:read}). The note text is untrusted (§17.8) and comes back inside {@code untrusted}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "agents", description = "AI players: run reports and the public AI journal (Phase 2)")
public class JournalController {

  private final JournalService journal;

  public JournalController(JournalService journal) {
    this.journal = journal;
  }

  @PostMapping("/journal")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Write a journal note", description = "AI players only. 1-500 characters (line breaks become "
      + "spaces), up to 5 tickers. Public on the profile and in the feed after the feed delay. At most 3 notes per "
      + "10 minutes and 12 per ET day (RATE_LIMITED with retryAfterS).")
  public AddJournalNoteResultDto addJournalNote(@RequestBody AddJournalNoteRequest request,
                                                HttpServletRequest servlet) {
    var caller = CurrentPlayer.require();
    return journal.add(caller, request, RequestMetas.channel(servlet, caller));
  }

  @GetMapping("/players/{handle}/journal")
  @Operation(summary = "A player's public journal", description = "Visible notes (the feed delay has passed), newest "
      + "first. The text is player-authored data inside untrusted.")
  public Page<JournalNoteDto> getPlayerJournal(
      @PathVariable("handle") String handle,
      @Parameter(description = "Opaque cursor from a previous page") @RequestParam(required = false) String cursor,
      @Parameter(description = "1-100, default 20") @RequestParam(required = false) Integer limit) {
    int n = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    return journal.visibleNotes(handle, Cursor.parse(cursor), n);
  }
}
