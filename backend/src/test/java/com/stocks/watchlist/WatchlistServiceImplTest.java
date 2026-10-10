package com.stocks.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.watchlist.dto.WatchlistDto;
import com.stocks.portfolio.PortfolioKit;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.Fixtures.PlayerRef;
import com.stocks.testkit.ScriptedMarketData;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestTimes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Watchlists (§18.2): CRUD, ordering, validation, ownership, the signup default and NAME_CHANGE renames. */
class WatchlistServiceImplTest {

  TestDb db;
  MutableTimeSource time;
  Fixtures fx;
  WatchlistServiceImpl service;
  PlayerRef alice;
  PlayerRef bob;

  @AfterEach
  void closeDb() {
    PortfolioKit.closePool(db);
  }

  @BeforeEach
  void setUp() {
    db = TestDb.fresh();
    time = TestTimes.clock();
    fx = new Fixtures(db.jdbc(), time);
    ScriptedMarketData market = new ScriptedMarketData(time).withAsset("AAPL").withAsset("NVDA").withAsset("TSLA");
    fx.asset("MSFT");                                               // known through the asset table only
    fx.asset("BRK.B");
    service = new WatchlistServiceImpl(db.jdbc(), new TransactionTemplate(new DataSourceTransactionManager(
        db.dataSource())), new PublicId(time), market, time);
    alice = fx.human("alice");
    bob = fx.human("bob");
  }

  @Test
  void theSignupDefaultIsMyListWithFivePopularTickersOnce() {
    service.seedDefault(alice.id());
    service.seedDefault(alice.id());
    List<WatchlistDto> lists = service.list(alice.id());
    assertThat(lists).singleElement().satisfies(w -> {
      assertThat(w.name()).isEqualTo("My List");
      assertThat(w.sortOrder()).isZero();
      assertThat(w.symbols()).containsExactly("SPY", "AAPL", "NVDA", "MSFT", "TSLA");
      assertThat(w.id()).startsWith("wlt_");
    });
    assertThat(service.list(bob.id())).isEmpty();
  }

  @Test
  void crudKeepsOrderAndNormalizesSymbols() {
    WatchlistDto tech = service.create(alice.id(), "  Tech  ", List.of(" aapl", "NVDA", "aapl", "msft"));
    assertThat(tech.name()).isEqualTo("Tech");
    assertThat(tech.symbols()).containsExactly("AAPL", "NVDA", "MSFT");
    WatchlistDto empty = service.create(alice.id(), "Later", null);
    assertThat(empty.symbols()).isEmpty();
    assertThat(empty.sortOrder()).isEqualTo(1);
    assertThat(service.list(alice.id())).extracting(WatchlistDto::name).containsExactly("Tech", "Later");

    WatchlistDto added = service.addSymbol(alice.id(), tech.id(), "tsla");
    assertThat(added.symbols()).containsExactly("AAPL", "NVDA", "MSFT", "TSLA");
    assertThat(service.addSymbol(alice.id(), tech.id(), "AAPL").symbols()).hasSize(4);   // idempotent
    assertThat(service.removeSymbol(alice.id(), tech.id(), "nvda").symbols()).containsExactly("AAPL", "MSFT", "TSLA");
    assertThat(service.removeSymbol(alice.id(), tech.id(), "ZZZZ").symbols()).hasSize(3);

    WatchlistDto replaced = service.replace(alice.id(), tech.id(), "Mega caps", List.of("TSLA", "BRK.B", "AAPL"));
    assertThat(replaced.name()).isEqualTo("Mega caps");
    assertThat(replaced.symbols()).containsExactly("TSLA", "BRK.B", "AAPL");
    assertThat(service.replace(alice.id(), tech.id(), "Mega caps", null).symbols()).isEmpty();

    service.delete(alice.id(), empty.id());
    assertThat(service.list(alice.id())).extracting(WatchlistDto::name).containsExactly("Mega caps");
  }

  @Test
  void anotherPlayersListIsNotFound() {
    WatchlistDto mine = service.create(alice.id(), "Mine", List.of("AAPL"));
    for (Runnable call : List.<Runnable>of(
        () -> service.addSymbol(bob.id(), mine.id(), "NVDA"),
        () -> service.removeSymbol(bob.id(), mine.id(), "AAPL"),
        () -> service.replace(bob.id(), mine.id(), "Stolen", List.of()),
        () -> service.delete(bob.id(), mine.id()),
        () -> service.delete(alice.id(), "wlt_doesnotexist"))) {
      assertThatThrownBy(call::run)
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }
    assertThat(service.list(alice.id()).get(0).symbols()).containsExactly("AAPL");
  }

  @Test
  void validation() {
    assertThatThrownBy(() -> service.create(alice.id(), " ", List.of())).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    assertThatThrownBy(() -> service.create(alice.id(), "x".repeat(41), List.of()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    assertThatThrownBy(() -> service.create(alice.id(), "Bad", List.of("AAPL", "NOPE", "ZZZZ")))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo(ErrorCode.SYMBOL_NOT_FOUND);
          assertThat(e.getMessage()).contains("NOPE, ZZZZ");
        });
    assertThatThrownBy(() -> service.create(alice.id(), "Bad", List.of("not a symbol")))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    service.create(alice.id(), "Dupes", List.of());
    assertThatThrownBy(() -> service.create(alice.id(), "DUPES", List.of()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    WatchlistDto other = service.create(alice.id(), "Other", List.of());
    assertThatThrownBy(() -> service.replace(alice.id(), other.id(), "dupes", List.of()))
        .isInstanceOf(ApiException.class);
    assertThat(service.replace(alice.id(), other.id(), "Other", List.of()).name()).isEqualTo("Other");   // own name ok
    assertThat(service.create(bob.id(), "Dupes", List.of()).name()).isEqualTo("Dupes");   // per player
    List<String> many = new ArrayList<>();
    for (int i = 0; i < WatchlistServiceImpl.MAX_SYMBOLS + 1; i++) {
      many.add("S" + i);
    }
    assertThatThrownBy(() -> service.create(alice.id(), "Many", many)).isInstanceOf(ApiException.class);
    for (int i = service.list(alice.id()).size(); i < WatchlistServiceImpl.MAX_LISTS; i++) {
      service.create(alice.id(), "L" + i, List.of());
    }
    assertThatThrownBy(() -> service.create(alice.id(), "One too many", List.of()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
  }

  @Test
  void theMcpByNameOperationsCreateUpdateAndDelete() {
    WatchlistDto created = service.upsertByName(alice.id(), "Bots", List.of("aapl", "nvda"), null);
    assertThat(created.symbols()).containsExactly("AAPL", "NVDA");
    WatchlistDto updated = service.upsertByName(alice.id(), "bots", List.of("TSLA"), List.of("aapl"));
    assertThat(updated.id()).isEqualTo(created.id());
    assertThat(updated.name()).isEqualTo("Bots");
    assertThat(updated.symbols()).containsExactly("NVDA", "TSLA");
    assertThat(service.upsertByName(alice.id(), "Bots", null, null).symbols()).hasSize(2);
    assertThat(service.deleteByName(alice.id(), "BOTS")).isTrue();
    assertThat(service.deleteByName(alice.id(), "Bots")).isFalse();
    assertThat(service.deleteByName(alice.id(), null)).isFalse();
  }

  @Test
  void aNameChangeRenamesEveryListAndMergesDuplicates() {
    WatchlistDto a = service.create(alice.id(), "A", List.of("AAPL", "MSFT"));
    WatchlistDto b = service.create(bob.id(), "B", List.of("MSFT", "NVDA"));
    WatchlistDto both = service.create(bob.id(), "Both", List.of("NVDA", "MSFT"));
    service.renameSymbol("msft", "NVDA");                          // NVDA already in two of the lists
    assertThat(service.list(alice.id()).get(0).symbols()).containsExactly("AAPL", "NVDA");
    assertThat(service.list(bob.id())).extracting(WatchlistDto::symbols)
        .containsExactly(List.of("NVDA"), List.of("NVDA"));
    service.renameSymbol("NVDA", "NVDA");                          // no-op
    assertThat(a.id()).isNotEqualTo(b.id()).isNotEqualTo(both.id());
  }
}
