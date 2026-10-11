package com.stocks.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.events.JournalNotePosted;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.agents.dto.AddJournalNoteRequest;
import com.stocks.contract.agents.dto.AddJournalNoteResultDto;
import com.stocks.contract.agents.dto.JournalNoteDto;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.identity.SettingsService;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.Scopes;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.Fixtures.PlayerRef;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestPrincipals;
import com.stocks.testkit.TestTimes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §20.7 the public AI journal on a real database: AI players only, cleaned untrusted text, known symbols, the
 * per-bot limits counted in the database (with {@code retryAfterS}), the feed delay, the {@code JournalNotePosted}
 * event and the visible-notes page.
 */
class JournalServiceTest {

  static final Instant NOW = TestTimes.START;                      // Fri 2026-10-09 10:00 ET

  TestDb db;
  MutableTimeSource time;
  Fixtures fx;
  JournalServiceImpl journal;
  PlayerRef owner;
  PlayerRef bot;
  PlayerPrincipal botKey;
  List<Object> events = new ArrayList<>();

  @BeforeEach
  void setUp() {
    db = TestDb.fresh();
    time = TestTimes.clock();
    time.set(NOW);
    fx = new Fixtures(db.jdbc(), time);
    owner = fx.human("owner");
    bot = fx.ai("claude", owner);
    fx.asset("NVDA");
    fx.asset("AAPL");
    botKey = TestPrincipals.apiKey(bot.id(), bot.publicId(), "claude", PlayerKind.AI, Scopes.ALL, 1L);
    SettingsService settings = mock(SettingsService.class);
    when(settings.tradeFeedDelay()).thenReturn(Duration.ofMinutes(15));
    MarketDataService market = mock(MarketDataService.class);
    when(market.asset(anyString())).thenReturn(Optional.empty());
    journal = new JournalServiceImpl(db.jdbc(), new AgentPlayers(db.jdbc(), mock(PlayerQueries.class)),
        new PublicId(time), time, settings, market, events::add, AgentsProperties.defaults());
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  AddJournalNoteResultDto add(String text, String... symbols) {
    return journal.add(botKey, new AddJournalNoteRequest(text, symbols.length == 0 ? null : List.of(symbols)),
        Channel.MCP_KEY);
  }

  static ErrorCode code(Runnable r) {
    try {
      r.run();
    } catch (ApiException e) {
      return e.code();
    }
    return null;
  }

  @Test
  void aNoteIsCleanedStoredAndPublishedWithTheFeedDelay() {
    AddJournalNoteResultDto r = add("  Buying\tNVDA:\n\nguidance​ raised.‮  ", "nvda", "NVDA", "aapl");
    JournalNoteDto note = r.note();
    assertThat(note.id()).startsWith("jnl_");
    assertThat(note.untrusted().text()).isEqualTo("Buying NVDA: guidance raised.");
    assertThat(note.symbols()).containsExactly("NVDA", "AAPL");
    assertThat(note.at()).isEqualTo(NOW);
    assertThat(note.visibleAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    assertThat(note.player().handle()).isEqualTo("claude");
    assertThat(r.remainingToday()).isEqualTo(11);
    assertThat(events).singleElement().isEqualTo(new JournalNotePosted(bot.id(), note.id(), NOW,
        NOW.plus(Duration.ofMinutes(15))));
    assertThat(db.jdbc().sql("SELECT channel FROM journal_note").query(String.class).single()).isEqualTo("MCP_KEY");
  }

  @Test
  void onlyAiPlayersWrite() {
    PlayerPrincipal human = TestPrincipals.session(owner.id(), owner.publicId(), "owner");
    assertThat(code(() -> journal.add(human, new AddJournalNoteRequest("hello there", null), Channel.WEB)))
        .isEqualTo(ErrorCode.FORBIDDEN);
    assertThat(code(() -> journal.add(null, new AddJournalNoteRequest("hello there", null), Channel.WEB)))
        .isEqualTo(ErrorCode.FORBIDDEN);
  }

  @Test
  void textAndSymbolsAreChecked() {
    assertThat(code(() -> add(" \n\t​ "))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(code(() -> add(null))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(code(() -> add("x".repeat(501)))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(add("x".repeat(500)).note().untrusted().text()).hasSize(500);
    // 500 characters outside the BMP are 1,000 UTF-16 units: the limit counts characters and the column holds them
    String emoji = "📈".repeat(500);
    assertThat(add(emoji).note().untrusted().text()).isEqualTo(emoji);
    assertThat(code(() -> add(emoji + "x"))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    assertThat(code(() -> add("ok", "ZZZZ"))).isEqualTo(ErrorCode.SYMBOL_NOT_FOUND);
    assertThat(code(() -> add("ok", "not a symbol"))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    for (String s : List.of("A", "B", "C", "D", "E", "F")) {
      fx.asset(s);
    }
    assertThat(code(() -> add("ok", "A", "B", "C", "D", "E", "F"))).isEqualTo(ErrorCode.VALIDATION_FAILED);
  }

  @Test
  void atMostThreeNotesPerTenMinutesWithRetryAfter() {
    add("one");
    time.set(NOW.plusSeconds(60));
    add("two");
    time.set(NOW.plusSeconds(120));
    add("three");
    time.set(NOW.plusSeconds(180));
    assertThatThrownBy(() -> add("four")).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
      assertThat(e.retryAfterS()).isEqualTo(420);                // until "one" leaves the window
    });
    time.set(NOW.plusSeconds(601));
    assertThat(add("four").remainingToday()).isEqualTo(8);
  }

  @Test
  void atMostTwelveNotesPerEtDayUntilMidnight() {
    for (int i = 0; i < 12; i++) {
      time.set(NOW.plus(Duration.ofMinutes(11L * i)));
      add("note " + i);
    }
    Instant last = NOW.plus(Duration.ofMinutes(11L * 12));
    time.set(last);
    assertThatThrownBy(() -> add("thirteen")).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
      // midnight ET (04:00Z on 2026-10-10) minus 2026-10-09T16:12Z
      assertThat(e.retryAfterS()).isEqualTo(Duration.between(last, Instant.parse("2026-10-10T04:00:00Z"))
          .toSeconds());
    });
    time.set(Instant.parse("2026-10-10T04:00:01Z"));
    assertThat(add("a new day").remainingToday()).isEqualTo(11);
  }

  @Test
  void notesAreVisibleOnlyAfterTheDelayNewestFirstAndPaged() {
    add("first");
    time.set(NOW.plusSeconds(700));
    add("second");
    time.set(NOW.plusSeconds(1400));
    add("third");
    time.set(NOW.plus(Duration.ofMinutes(14)));
    assertThat(journal.visibleNotes("claude", null, 20).items()).isEmpty();
    time.set(NOW.plus(Duration.ofMinutes(15)).plusSeconds(1400));
    Page<JournalNoteDto> page = journal.visibleNotes("claude", null, 2);
    assertThat(page.items()).extracting(n -> n.untrusted().text()).containsExactly("third", "second");
    Page<JournalNoteDto> next = journal.visibleNotes("claude", Cursor.parse(page.nextCursor()), 2);
    assertThat(next.items()).extracting(n -> n.untrusted().text()).containsExactly("first");
    assertThat(next.nextCursor()).isNull();
    assertThat(journal.visibleNotes("owner", null, 20).items()).isEmpty();
    assertThat(code(() -> journal.visibleNotes("ghost", null, 20))).isEqualTo(ErrorCode.PLAYER_NOT_FOUND);
  }

  @Test
  void cleaningRules() {
    assertThat(JournalServiceImpl.clean("a\r\nb c  d")).isEqualTo("a b c d");
    assertThat(JournalServiceImpl.clean("<script>alert(1)</script>")).isEqualTo("<script>alert(1)</script>");
    assertThat(JournalServiceImpl.clean("\u0007‎")).isNull();
  }
}
