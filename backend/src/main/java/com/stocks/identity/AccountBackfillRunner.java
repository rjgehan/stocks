package com.stocks.identity;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * At boot, opens the first account of every player who has none yet (status not DISABLED): players created while the
 * trading core was still a Day-0 stub (§27.1), or whose signup lost its engine command to a crash. Runs after the
 * bootstrap admin and the dev seed; stops quietly while accounts are still stubbed; a failure for one player is
 * logged and never stops the boot.
 */
@Component
@Order(30)
public class AccountBackfillRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AccountBackfillRunner.class);

  private final JdbcClient jdbc;
  private final AccountProvisioner accounts;

  public AccountBackfillRunner(JdbcClient jdbc, AccountProvisioner accounts) {
    this.jdbc = jdbc;
    this.accounts = accounts;
  }

  @Override
  public void run(ApplicationArguments args) {
    backfill();
  }

  /** @return the number of accounts opened */
  public int backfill() {
    List<Long> players = jdbc.sql("""
            SELECT p.id FROM player p
             WHERE p.status <> 'DISABLED' AND NOT EXISTS (SELECT 1 FROM account a WHERE a.player_id = p.id)
             ORDER BY p.id""")
        .query(Long.class).list();
    if (players.isEmpty()) {
      return 0;
    }
    Long actor = jdbc.sql("SELECT id FROM player WHERE role = 'ADMIN' ORDER BY id LIMIT 1").query(Long.class)
        .optional().orElse(null);
    int opened = 0;
    for (long playerId : players) {
      try {
        AccountProvisioner.Outcome outcome = accounts.ensureAccount(playerId, actor == null ? playerId : actor);
        if (outcome == AccountProvisioner.Outcome.PENDING) {
          log.info("{} player(s) have no account yet; accounts are not implemented in this build", players.size());
          return opened;
        }
        if (outcome == AccountProvisioner.Outcome.OPENED) {
          opened++;
        }
      } catch (RuntimeException e) {
        log.warn("Could not open the first account of player {}: {}", playerId, e.toString());
      }
    }
    if (opened > 0) {
      log.info("Opened the first account of {} player(s)", opened);
    }
    return opened;
  }
}
