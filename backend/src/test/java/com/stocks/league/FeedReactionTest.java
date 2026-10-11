package com.stocks.league;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.portfolio.dto.FeedItemDto;
import com.stocks.contract.portfolio.dto.FeedItemDto.FeedItemKind;
import com.stocks.contract.portfolio.dto.FeedReactionsDto;
import com.stocks.contract.portfolio.dto.FeedReactionsDto.FeedReactionDto;
import com.stocks.contract.portfolio.dto.FeedReactionsDto.Reaction;
import com.stocks.contract.portfolio.dto.FeedReactionsDto.ReactorDto;
import com.stocks.portfolio.PortfolioData;
import com.stocks.portfolio.PortfolioKit;
import com.stocks.testkit.Fixtures.PlayerRef;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

/**
 * §16.11 feed reactions on a fresh database (H2, or embedded PostgreSQL under {@code -Ppg-it}, which runs the
 * {@code feed_reaction} MERGE on both): one reaction per player per visible item, replaced by another emoji, removed
 * idempotently; the feed lists them in emoji order with who reacted (listed players only); every change is pushed on
 * {@code /topic/feed}.
 */
class FeedReactionTest {

  static final Instant MON_10 = Instant.parse("2026-10-05T14:00:00Z");

  LeagueKit lk;
  PortfolioKit kit;
  PortfolioData data;
  PlayerRef alice;
  PlayerRef bob;
  PlayerRef claude;
  String item;
  SimpMessageSendingOperations ops;

  @AfterEach
  void closeDb() {
    kit.close();
  }

  @BeforeEach
  void setUp() {
    lk = new LeagueKit();
    kit = lk.kit;
    data = kit.data;
    kit.at(MON_10);
    alice = data.human("alice");
    bob = data.human("bob");
    claude = data.ai("claude", alice);
    ops = mock(SimpMessageSendingOperations.class);
    lk.beans.addBean("messaging", ops);
    item = "fde_" + "a".repeat(10);
    lk.feedRepository.insertIfAbsent(item, alice.id(), null, FeedItemKind.JOINED, null, "{}", "JOIN:" + alice.id(),
        MON_10, MON_10);
  }

  FeedItemDto feedItem() {
    return lk.feedService.page(null, 50).items().stream().filter(i -> i.id().equals(item)).findFirst().orElseThrow();
  }

  static FeedReactionDto of(FeedReactionsDto r, Reaction reaction) {
    return r.reactions().stream().filter(x -> x.reaction() == reaction).findFirst().orElseThrow();
  }

  @Test
  void onePerPlayerPerItemReplacedByAnotherEmojiAndRemovedIdempotently() {
    assertThat(feedItem().reactions()).isEmpty();
    FeedReactionsDto r = lk.reactions.react(bob.id(), item, Reaction.ROCKET);
    assertThat(r.itemId()).isEqualTo(item);
    assertThat(r.reactions()).singleElement().satisfies(x -> {
      assertThat(x.reaction()).isEqualTo(Reaction.ROCKET);
      assertThat(x.count()).isEqualTo(1);
      assertThat(x.players()).containsExactly(new ReactorDto("bob", "bob", com.stocks.common.domain.PlayerKind.HUMAN));
    });
    kit.at(MON_10.plusSeconds(1));
    lk.reactions.react(claude.id(), item, Reaction.ROCKET);
    kit.at(MON_10.plusSeconds(2));
    lk.reactions.react(alice.id(), item, Reaction.SKULL);
    r = lk.reactions.react(bob.id(), item, Reaction.ROCKET);              // the same emoji again: no change
    assertThat(of(r, Reaction.ROCKET).players()).extracting(ReactorDto::handle).containsExactly("bob", "claude");
    assertThat(r.reactions()).extracting(FeedReactionDto::reaction).containsExactly(Reaction.ROCKET, Reaction.SKULL);

    kit.at(MON_10.plusSeconds(3));
    r = lk.reactions.react(bob.id(), item, Reaction.FIRE);                // replaces bob's rocket
    assertThat(r.reactions()).extracting(FeedReactionDto::reaction)
        .containsExactly(Reaction.ROCKET, Reaction.FIRE, Reaction.SKULL);
    assertThat(of(r, Reaction.ROCKET).count()).isEqualTo(1);
    assertThat(of(r, Reaction.FIRE).players()).extracting(ReactorDto::handle).containsExactly("bob");
    assertThat(kit.db.jdbc().sql("SELECT COUNT(*) FROM feed_reaction").query(Long.class).single()).isEqualTo(3);

    // the feed shows the same reactions on the item
    assertThat(feedItem().reactions()).isEqualTo(r.reactions());

    r = lk.reactions.unreact(bob.id(), item);
    assertThat(r.reactions()).extracting(FeedReactionDto::reaction).containsExactly(Reaction.ROCKET, Reaction.SKULL);
    assertThat(lk.reactions.unreact(bob.id(), item)).isEqualTo(r);        // removing nothing is fine

    // every change was pushed on /topic/feed (the no-op removal was not)
    verify(ops, times(6)).convertAndSend(eq(FeedService.TOPIC), any(FeedReactionsDto.class));
  }

  @Test
  void hiddenPlayersReactionsAreLeftOutAndHiddenOrFutureItemsAreNotFound() {
    lk.reactions.react(bob.id(), item, Reaction.GEM);
    lk.reactions.react(claude.id(), item, Reaction.GEM);
    data.fx.status(claude, "DISABLED");
    FeedItemDto i = feedItem();
    assertThat(i.reactions()).singleElement().satisfies(x -> {
      assertThat(x.count()).isEqualTo(1);
      assertThat(x.players()).extracting(ReactorDto::handle).containsExactly("bob");
    });

    String later = "fde_" + "b".repeat(10);
    lk.feedRepository.insertIfAbsent(later, bob.id(), null, FeedItemKind.JOINED, null, "{}", "JOIN:" + bob.id(),
        MON_10, MON_10.plus(Duration.ofMinutes(15)));
    for (String id : List.of(later, "fde_unknown", "", "x".repeat(40))) {
      assertThatThrownBy(() -> lk.reactions.react(alice.id(), id, Reaction.CLAP))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }
    kit.at(MON_10.plus(Duration.ofMinutes(15)));
    assertThat(lk.reactions.react(alice.id(), later, Reaction.CLAP).reactions()).hasSize(1);

    data.fx.status(alice, "DISABLED");                                     // the item's player is hidden
    assertThatThrownBy(() -> lk.reactions.unreact(bob.id(), item))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    verify(ops, never()).convertAndSend(eq(FeedService.TOPIC), any(FeedItemDto.class));
  }

  @Test
  void newlyVisibleItemsArePublishedWithTheirReactions() {
    lk.reactions.react(bob.id(), item, Reaction.LAUGH);
    assertThat(lk.feedService.publishNewlyVisible()).isZero();            // the first tick sets the watermark
    String later = "fde_" + "c".repeat(10);
    lk.feedRepository.insertIfAbsent(later, bob.id(), null, FeedItemKind.JOINED, null, "{}", "JOIN:" + bob.id(),
        MON_10, MON_10.plusSeconds(10));
    kit.at(MON_10.plusSeconds(10));
    assertThat(lk.feedService.publishNewlyVisible()).isEqualTo(1);
    verify(ops).convertAndSend(eq(FeedService.TOPIC), any(FeedItemDto.class));
  }
}
