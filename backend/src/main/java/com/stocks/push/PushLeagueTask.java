package com.stocks.push;

import com.stocks.contract.jobs.PeriodicTask;
import com.stocks.contract.portfolio.PerformanceQueries;
import com.stocks.contract.portfolio.dto.FeedItemDto;
import com.stocks.contract.portfolio.dto.FeedItemDto.FeedItemKind;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * League events as Web Push (§19.2, category {@code LEAGUE}): every {@value #INTERVAL_SECONDS} s it reads the newest
 * league feed items ({@code PerformanceQueries.feed}, only visible ones) and pushes each NEW_LEADER item it has not
 * seen to every player who wants league pushes. The first tick after boot only records what is already there, so a
 * restart never repeats old news. Runs with the other periodic tasks ({@code stocks.jobs.enabled}).
 */
@Component
public class PushLeagueTask implements PeriodicTask {

  private static final Logger log = LoggerFactory.getLogger(PushLeagueTask.class);

  public static final String NAME = "PUSH_LEAGUE_EVENTS";
  static final long INTERVAL_SECONDS = 60;
  static final int PAGE = 50;
  static final int REMEMBER = 500;

  private final PerformanceQueries performance;
  private final PushService push;
  private final Set<String> seen = new HashSet<>();
  private final Deque<String> order = new ArrayDeque<>();
  private boolean primed;

  public PushLeagueTask(PerformanceQueries performance, PushService push) {
    this.performance = performance;
    this.push = push;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Duration interval() {
    return Duration.ofSeconds(INTERVAL_SECONDS);
  }

  @Override
  public boolean sessionsOnly() {
    return false;
  }

  @Override
  public synchronized void tick() {
    if (!push.available()) {
      return;
    }
    List<FeedItemDto> items = performance.feed(null, PAGE).items();
    List<FeedItemDto> fresh = items.stream()
        .filter(i -> i.kind() == FeedItemKind.NEW_LEADER && i.id() != null && !seen.contains(i.id()))
        .toList();
    for (FeedItemDto i : fresh) {
      remember(i.id());
    }
    if (!primed) {
      primed = true;
      return;
    }
    for (FeedItemDto i : fresh.reversed()) {                // oldest first
      String who = i.player() == null || i.player().displayName() == null ? "Someone" : i.player().displayName();
      String period = i.payload() == null || i.payload().period() == null ? "week"
          : i.payload().period().toLowerCase(Locale.ROOT);
      String periodText = "week".equals(period) ? "weekly" : period;
      int players = push.sendLeague(PushMessage.league("New " + periodText + " leader",
          who + " now leads the " + periodText + " leaderboard.", "league:leader"));
      log.info("League push '{}' queued for {} player(s)", i.id(), players);
    }
  }

  private void remember(String id) {
    if (seen.add(id)) {
      order.addLast(id);
      while (order.size() > REMEMBER) {
        seen.remove(order.removeFirst());
      }
    }
  }
}
