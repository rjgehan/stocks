package com.stocks.mcp.tools;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.web.Cursor;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.trading.AccountQueries;
import io.modelcontextprotocol.common.McpTransportContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Argument checks shared by the tools (§20.2): every failure is {@code VALIDATION_FAILED} with the snake_case field,
 * the same code REST uses. Range limits and defaults are the §20.3 catalog's ({@code limit?: int(1..100)=50}).
 */
final class McpArgs {

  private static final Pattern SYMBOL = Pattern.compile("^[A-Za-z][A-Za-z0-9.\\-]{0,11}$");
  private static final Pattern HANDLE = Pattern.compile("^[A-Za-z0-9_.\\-]{1,40}$");

  private McpArgs() {}

  /** {@code value} or {@code dflt}, which must lie in {@code [min, max]}. */
  static int limit(Integer value, int dflt, int min, int max, String field) {
    int v = value == null ? dflt : value;
    if (v < min || v > max) {
      throw ApiException.validation(field, field + " must be between " + min + " and " + max + ".");
    }
    return v;
  }

  /** A ticker, upper-cased ({@code brk.b} → {@code BRK.B}). */
  static String symbol(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      throw ApiException.validation(field, field + " is required.");
    }
    String s = raw.strip();
    if (!SYMBOL.matcher(s).matches()) {
      throw ApiException.validation(field, "'" + clip(s) + "' is not a ticker symbol.");
    }
    return s.toUpperCase(Locale.ROOT);
  }

  /** An optional ticker: null stays null. */
  static String optionalSymbol(String raw, String field) {
    return raw == null || raw.isBlank() ? null : symbol(raw, field);
  }

  /** Distinct upper-cased tickers in request order; {@code min..max} of them. */
  static List<String> symbols(List<String> raw, int min, int max, String field) {
    List<String> in = raw == null ? List.of() : raw;
    Set<String> out = new LinkedHashSet<>();
    for (String s : in) {
      out.add(symbol(s, field));
    }
    if (out.size() < min || out.size() > max) {
      throw ApiException.validation(field, field + " must hold " + min + " to " + max + " symbols.");
    }
    return new ArrayList<>(out);
  }

  static String handle(String raw, String field) {
    if (raw == null || !HANDLE.matcher(raw.strip()).matches()) {
      throw ApiException.validation(field, field + " must be a player handle.");
    }
    return raw.strip().toLowerCase(Locale.ROOT);
  }

  /** A non-blank string of at most {@code max} characters (trimmed). */
  static String text(String raw, int max, String field) {
    if (raw == null || raw.isBlank()) {
      throw ApiException.validation(field, field + " is required.");
    }
    String s = raw.strip();
    if (s.length() > max) {
      throw ApiException.validation(field, field + " must be at most " + max + " characters.");
    }
    return s;
  }

  static Cursor cursor(String raw) {
    return Cursor.parse(raw);
  }

  static PlayerPrincipal caller(McpTransportContext ctx) {
    return CurrentPlayer.require(ctx);
  }

  /** The caller's ACTIVE account id ({@code ACCOUNT_NOT_FOUND} with a hint when they have none yet). */
  static long accountId(AccountQueries accounts, PlayerPrincipal caller) {
    try {
      return accounts.activeAccountId(caller.playerId());
    } catch (ApiException e) {
      if (e.code() == ErrorCode.NOT_FOUND) {
        throw ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "You have no active account yet.")
            .withHint("Ask the league admin to fund your account.");
      }
      throw e;
    }
  }

  private static String clip(String s) {
    return s.length() <= 20 ? s : s.substring(0, 20) + "…";
  }
}
