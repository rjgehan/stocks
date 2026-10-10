package com.stocks.watchlist;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Text;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.watchlist.WatchlistService;
import com.stocks.contract.watchlist.dto.WatchlistDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Watchlists (§18.2, §22.4): a player's named lists of symbols, each in its own order. Symbols are trimmed and
 * upper-cased, de-duplicated, and must be known ({@code MarketDataService.asset}, or a row of the {@code asset}
 * table), else {@code SYMBOL_NOT_FOUND}. Names are 1–40 characters, unique per player (ignoring case). Limits:
 * {@value #MAX_LISTS} lists per player, {@value #MAX_SYMBOLS} symbols per list.
 */
@Service
public class WatchlistServiceImpl implements WatchlistService {

  private static final Logger log = LoggerFactory.getLogger(WatchlistServiceImpl.class);

  /** The signup / admin-create default (§17.3): "My List" with 5 popular tickers. */
  public static final String DEFAULT_NAME = "My List";
  public static final List<String> DEFAULT_SYMBOLS = List.of("SPY", "AAPL", "NVDA", "MSFT", "TSLA");
  public static final int MAX_LISTS = 20;
  public static final int MAX_SYMBOLS = 200;
  public static final int MAX_NAME = 40;
  static final Pattern SYMBOL = Pattern.compile("^[A-Z][A-Z0-9.\\-]{0,11}$");

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final PublicId publicIds;
  private final MarketDataService marketData;
  private final TimeSource time;

  public WatchlistServiceImpl(JdbcClient jdbc, TransactionTemplate tx, PublicId publicIds,
                              MarketDataService marketData, TimeSource time) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.publicIds = publicIds;
    this.marketData = marketData;
    this.time = time;
  }

  private record ListRow(long id, String publicId, String name, int sortOrder) {}

  // ------------------------------------------------------------------------------------------- hooks

  @Override
  public void seedDefault(long playerId) {
    long lists = jdbc.sql("SELECT COUNT(*) FROM watchlist WHERE player_id = ?").param(playerId)
        .query(Long.class).single();
    if (lists > 0) {
      return;
    }
    tx.executeWithoutResult(s -> {
      long id = insertList(playerId, DEFAULT_NAME, 0);
      writeItems(id, DEFAULT_SYMBOLS);
    });
  }

  @Override
  public void renameSymbol(String oldSymbol, String newSymbol) {
    String from = normalize(oldSymbol);
    String to = normalize(newSymbol);
    if (from.equals(to)) {
      return;
    }
    // lists that already hold the new symbol just drop the old one; the rest take the new name in place
    int dropped = jdbc.sql("""
            DELETE FROM watchlist_item WHERE symbol = ?
               AND watchlist_id IN (SELECT watchlist_id FROM watchlist_item WHERE symbol = ?)""")
        .params(from, to).update();
    int renamed = jdbc.sql("UPDATE watchlist_item SET symbol = ? WHERE symbol = ?").params(to, from).update();
    log.info("Watchlists: {} → {} renamed in {} list(s), dropped from {}", from, to, renamed, dropped);
  }

  // ------------------------------------------------------------------------------------------- reads

  @Override
  public List<WatchlistDto> list(long playerId) {
    List<ListRow> lists = jdbc.sql("SELECT id, public_id, name, sort_order FROM watchlist WHERE player_id = ? "
            + "ORDER BY sort_order, id")
        .param(playerId)
        .query((rs, n) -> new ListRow(rs.getLong("id"), rs.getString("public_id"), rs.getString("name"),
            rs.getInt("sort_order")))
        .list();
    if (lists.isEmpty()) {
      return List.of();
    }
    Map<Long, List<String>> symbols = new LinkedHashMap<>();
    jdbc.sql("""
            SELECT i.watchlist_id, i.symbol FROM watchlist_item i JOIN watchlist w ON w.id = i.watchlist_id
             WHERE w.player_id = ? ORDER BY i.watchlist_id, i.sort_order, i.symbol""")
        .param(playerId)
        .query(rs -> {
          symbols.computeIfAbsent(rs.getLong("watchlist_id"), k -> new ArrayList<>()).add(rs.getString("symbol"));
        });
    return lists.stream()
        .map(l -> new WatchlistDto(l.publicId(), l.name(), l.sortOrder(), symbols.getOrDefault(l.id(), List.of())))
        .toList();
  }

  // ------------------------------------------------------------------------------------------- writes

  @Override
  public WatchlistDto create(long playerId, String name, List<String> symbols) {
    String n = validName(name);
    List<String> syms = validSymbols(symbols);
    String publicId = tx.execute(s -> {
      long count = jdbc.sql("SELECT COUNT(*) FROM watchlist WHERE player_id = ?").param(playerId)
          .query(Long.class).single();
      if (count >= MAX_LISTS) {
        throw ApiException.validation("name", "At most " + MAX_LISTS + " watchlists per player");
      }
      requireUniqueName(playerId, n, null);
      int order = jdbc.sql("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM watchlist WHERE player_id = ?")
          .param(playerId).query(Integer.class).single();
      long id = insertList(playerId, n, order);
      writeItems(id, syms);
      return jdbc.sql("SELECT public_id FROM watchlist WHERE id = ?").param(id).query(String.class).single();
    });
    return get(playerId, publicId);
  }

  @Override
  public WatchlistDto replace(long playerId, String watchlistId, String name, List<String> symbols) {
    String n = validName(name);
    List<String> syms = validSymbols(symbols == null ? List.of() : symbols);
    tx.executeWithoutResult(s -> {
      ListRow list = owned(playerId, watchlistId);
      requireUniqueName(playerId, n, list.id());
      jdbc.sql("UPDATE watchlist SET name = ? WHERE id = ?").params(n, list.id()).update();
      jdbc.sql("DELETE FROM watchlist_item WHERE watchlist_id = ?").param(list.id()).update();
      writeItems(list.id(), syms);
    });
    return get(playerId, watchlistId);
  }

  @Override
  public void delete(long playerId, String watchlistId) {
    tx.executeWithoutResult(s -> {
      ListRow list = owned(playerId, watchlistId);
      jdbc.sql("DELETE FROM watchlist_item WHERE watchlist_id = ?").param(list.id()).update();
      jdbc.sql("DELETE FROM watchlist WHERE id = ?").param(list.id()).update();
    });
  }

  @Override
  public WatchlistDto addSymbol(long playerId, String watchlistId, String symbol) {
    String sym = validSymbols(List.of(symbol == null ? "" : symbol)).get(0);
    tx.executeWithoutResult(s -> appendIfAbsent(owned(playerId, watchlistId).id(), sym));
    return get(playerId, watchlistId);
  }

  @Override
  public WatchlistDto removeSymbol(long playerId, String watchlistId, String symbol) {
    String sym = normalize(symbol);
    tx.executeWithoutResult(s -> jdbc.sql("DELETE FROM watchlist_item WHERE watchlist_id = ? AND symbol = ?")
        .params(owned(playerId, watchlistId).id(), sym).update());
    return get(playerId, watchlistId);
  }

  @Override
  public WatchlistDto upsertByName(long playerId, String name, List<String> add, List<String> remove) {
    String n = validName(name);
    List<String> toAdd = add == null || add.isEmpty() ? List.of() : validSymbols(add);
    Optional<ListRow> existing = byName(playerId, n);
    String publicId = existing.isPresent() ? existing.get().publicId() : create(playerId, n, List.of()).id();
    tx.executeWithoutResult(s -> {
      ListRow list = owned(playerId, publicId);
      for (String sym : toAdd) {
        appendIfAbsent(list.id(), sym);
      }
      if (remove != null) {
        for (String r : remove) {
          jdbc.sql("DELETE FROM watchlist_item WHERE watchlist_id = ? AND symbol = ?")
              .params(list.id(), normalize(r)).update();
        }
      }
    });
    return get(playerId, publicId);
  }

  @Override
  public boolean deleteByName(long playerId, String name) {
    Optional<ListRow> list = byName(playerId, name == null ? "" : name.trim());
    if (list.isEmpty()) {
      return false;
    }
    delete(playerId, list.get().publicId());
    return true;
  }

  // ------------------------------------------------------------------------------------------- helpers

  private WatchlistDto get(long playerId, String publicId) {
    return list(playerId).stream().filter(w -> w.id().equals(publicId)).findFirst()
        .orElseThrow(() -> notFound(publicId));
  }

  private ListRow owned(long playerId, String watchlistId) {
    return jdbc.sql("SELECT id, public_id, name, sort_order FROM watchlist WHERE public_id = ? AND player_id = ?")
        .params(watchlistId, playerId)
        .query((rs, n) -> new ListRow(rs.getLong("id"), rs.getString("public_id"), rs.getString("name"),
            rs.getInt("sort_order")))
        .optional()
        .orElseThrow(() -> notFound(watchlistId));
  }

  private Optional<ListRow> byName(long playerId, String name) {
    return jdbc.sql("SELECT id, public_id, name, sort_order FROM watchlist WHERE player_id = ? "
            + "AND LOWER(name) = LOWER(?) ORDER BY id LIMIT 1")
        .params(playerId, name)
        .query((rs, n) -> new ListRow(rs.getLong("id"), rs.getString("public_id"), rs.getString("name"),
            rs.getInt("sort_order")))
        .optional();
  }

  private static ApiException notFound(String watchlistId) {
    return ApiException.of(ErrorCode.NOT_FOUND, "No watchlist '" + Text.clip(watchlistId, 40) + "'.");
  }

  private void requireUniqueName(long playerId, String name, Long exceptId) {
    Optional<ListRow> clash = byName(playerId, name);
    if (clash.isPresent() && (exceptId == null || clash.get().id() != exceptId)) {
      throw ApiException.validation("name", "You already have a watchlist named '" + name + "'");
    }
  }

  private long insertList(long playerId, String name, int sortOrder) {
    String publicId = publicIds.next(Prefix.WATCHLIST);
    jdbc.sql("INSERT INTO watchlist (public_id, player_id, name, sort_order, created_at) VALUES (?, ?, ?, ?, ?)")
        .params(publicId, playerId, name, sortOrder, Jdbc.ts(time.now())).update();
    return jdbc.sql("SELECT id FROM watchlist WHERE public_id = ?").param(publicId).query(Long.class).single();
  }

  private void writeItems(long watchlistId, List<String> symbols) {
    Instant now = time.now();
    int i = 0;
    for (String sym : symbols) {
      jdbc.sql("INSERT INTO watchlist_item (watchlist_id, symbol, sort_order, added_at) VALUES (?, ?, ?, ?)")
          .params(watchlistId, sym, i++, Jdbc.ts(now)).update();
    }
  }

  private void appendIfAbsent(long watchlistId, String symbol) {
    long present = jdbc.sql("SELECT COUNT(*) FROM watchlist_item WHERE watchlist_id = ? AND symbol = ?")
        .params(watchlistId, symbol).query(Long.class).single();
    if (present > 0) {
      return;
    }
    long size = jdbc.sql("SELECT COUNT(*) FROM watchlist_item WHERE watchlist_id = ?").param(watchlistId)
        .query(Long.class).single();
    if (size >= MAX_SYMBOLS) {
      throw ApiException.validation("symbol", "At most " + MAX_SYMBOLS + " symbols per watchlist");
    }
    int order = jdbc.sql("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM watchlist_item WHERE watchlist_id = ?")
        .param(watchlistId).query(Integer.class).single();
    jdbc.sql("INSERT INTO watchlist_item (watchlist_id, symbol, sort_order, added_at) VALUES (?, ?, ?, ?)")
        .params(watchlistId, symbol, order, Jdbc.ts(time.now())).update();
  }

  static String validName(String name) {
    String n = Text.stripControl(name == null ? "" : name).trim();
    if (n.isEmpty() || n.length() > MAX_NAME) {
      throw ApiException.validation("name", "name must be 1-" + MAX_NAME + " characters");
    }
    return n;
  }

  static String normalize(String symbol) {
    return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
  }

  /** Normalized, de-duplicated (first occurrence wins) and known; {@code SYMBOL_NOT_FOUND} lists the unknown. */
  List<String> validSymbols(List<String> symbols) {
    if (symbols == null) {
      return List.of();
    }
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String s : symbols) {
      String sym = normalize(s);
      if (!SYMBOL.matcher(sym).matches()) {
        throw ApiException.validation("symbols", "'" + Text.clip(s, 20) + "' is not a symbol");
      }
      out.add(sym);
    }
    if (out.size() > MAX_SYMBOLS) {
      throw ApiException.validation("symbols", "At most " + MAX_SYMBOLS + " symbols per watchlist");
    }
    List<String> unknown = out.stream().filter(sym -> !known(sym)).toList();
    if (!unknown.isEmpty()) {
      throw ApiException.of(ErrorCode.SYMBOL_NOT_FOUND, "Unknown symbol(s): " + String.join(", ", unknown))
          .withField("symbols", ErrorCode.SYMBOL_NOT_FOUND, String.join(", ", unknown));
    }
    return List.copyOf(out);
  }

  private boolean known(String symbol) {
    try {
      if (marketData.asset(symbol).isPresent()) {
        return true;
      }
    } catch (RuntimeException e) {
      log.debug("asset({}) failed: {}", symbol, e.toString());
    }
    return jdbc.sql("SELECT COUNT(*) FROM asset WHERE symbol = ?").param(symbol).query(Long.class).single() > 0;
  }
}
