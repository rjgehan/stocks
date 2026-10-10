package com.stocks.identity;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.config.StocksProperties;
import com.stocks.contract.identity.SettingsService;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.AccountService;
import com.stocks.contract.watchlist.WatchlistService;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Gives a player their first funded account (run 1) and the default "My List" watchlist, through the WS3/WS4
 * contracts (§17.3 signup, §21.1 admin create, bootstrap admin, dev seed, and the boot-time backfill of
 * {@link AccountBackfillRunner}). Idempotent: a player who already has an ACTIVE account is left alone. While those
 * contracts are still Day-0 stubs (501 {@code NOT_IMPLEMENTED}) it does nothing and reports {@link Outcome#PENDING},
 * so every player gets their account on the first boot after WS3 lands, with no WS1 change.
 */
@Component
public class AccountProvisioner {

  private static final Logger log = LoggerFactory.getLogger(AccountProvisioner.class);

  public enum Outcome { EXISTS, OPENED, PENDING }

  private final AccountQueries accountQueries;
  private final AccountService accountService;
  private final SettingsService settings;
  private final WatchlistService watchlists;
  private final StocksProperties properties;

  public AccountProvisioner(AccountQueries accountQueries, AccountService accountService, SettingsService settings,
                            WatchlistService watchlists, StocksProperties properties) {
    this.accountQueries = accountQueries;
    this.accountService = accountService;
    this.settings = settings;
    this.watchlists = watchlists;
    this.properties = properties;
  }

  /** With the league's default starting cash. */
  public Outcome ensureAccount(long playerId, long actorPlayerId) {
    return ensureAccount(playerId, actorPlayerId, null);
  }

  /**
   * Opens run 1 with {@code startingCashOrNull} (null = {@code league.defaultStartingCash}) unless the player already
   * has an ACTIVE account, then seeds the default watchlist.
   */
  public Outcome ensureAccount(long playerId, long actorPlayerId, BigDecimal startingCashOrNull) {
    try {
      accountQueries.activeAccountId(playerId);
      return Outcome.EXISTS;
    } catch (ApiException e) {
      if (e.code() == ErrorCode.NOT_IMPLEMENTED) {
        log.debug("Accounts are not implemented yet; player {} gets one once WS3 lands", playerId);
        return Outcome.PENDING;
      }
      if (e.code() != ErrorCode.NOT_FOUND && e.code() != ErrorCode.ACCOUNT_NOT_FOUND) {
        throw e;
      }
    }
    try {
      accountService.openInitialAccount(playerId,
          startingCashOrNull == null ? startingCash() : startingCashOrNull, actorPlayerId);
    } catch (ApiException e) {
      if (e.code() == ErrorCode.NOT_IMPLEMENTED) {
        return Outcome.PENDING;
      }
      throw e;
    }
    seedWatchlist(playerId);
    return Outcome.OPENED;
  }

  /**
   * The default watchlist is a convenience: once the account is open, a failure here is logged and never undoes or
   * hides the account (the player can build a list by hand; {@code seedDefault} is a no-op once a list exists).
   */
  private void seedWatchlist(long playerId) {
    try {
      watchlists.seedDefault(playerId);
    } catch (ApiException e) {
      if (e.code() == ErrorCode.NOT_IMPLEMENTED) {
        log.info("Watchlists are not implemented yet; player {} has no default watchlist", playerId);
      } else {
        log.warn("Player {} got no default watchlist: {}", playerId, e.toString());
      }
    } catch (RuntimeException e) {
      log.warn("Player {} got no default watchlist: {}", playerId, e.toString());
    }
  }

  /** {@code league.defaultStartingCash}, or {@code stocks.bootstrap.default-starting-cash} until settings exist. */
  public BigDecimal startingCash() {
    try {
      return settings.defaultStartingCash();
    } catch (ApiException e) {
      if (e.code() != ErrorCode.NOT_IMPLEMENTED) {
        throw e;
      }
      return properties.bootstrap().defaultStartingCash();
    }
  }
}
