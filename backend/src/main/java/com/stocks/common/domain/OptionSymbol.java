package com.stocks.common.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * US equity options (§30): OCC contract symbols and the rules every layer shares. An option symbol is the OCC
 * spelling without padding, {@code ROOT YYMMDD C|P STRIKE×1000} ({@code AAPL261120C00250000} = AAPL, 20 Nov 2026,
 * $250 call); no stock or crypto symbol matches it, so {@link #isOption} is a pure, lookup-free test like
 * {@code Crypto.isCrypto}. Quantities are whole contracts, prices are per share and every dollar amount (principal,
 * market value) is {@code price × }{@link #MULTIPLIER}{@code  × contracts}.
 */
public final class OptionSymbol {

  /** Shares per contract (standard US equity options; adjusted contracts are not offered). */
  public static final int MULTIPLIER = 100;

  public static final BigDecimal MULTIPLIER_DEC = BigDecimal.valueOf(MULTIPLIER);

  /** The OCC pattern: a 1–6 letter root, the expiration, the right and the strike in thousandths. */
  public static final String REGEX = "^[A-Z]{1,6}[0-9]{6}[CP][0-9]{8}$";

  /** The largest option order (contracts). */
  public static final BigDecimal MAX_ORDER_CONTRACTS = new BigDecimal("1000");

  /** The exchange label on option assets. */
  public static final String EXCHANGE = "OPRA";

  private static final Pattern PATTERN = Pattern.compile("^([A-Z]{1,6})([0-9]{6})([CP])([0-9]{8})$");
  /** Strict: 31 November is not a date (the default SMART resolver would read it as 30 November). */
  private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("uuMMdd", Locale.ROOT)
      .withResolverStyle(ResolverStyle.STRICT);
  private static final DateTimeFormatter LABEL_DATE = DateTimeFormatter.ofPattern("d MMM yy", Locale.ENGLISH);
  private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

  /** One parsed contract. {@code strike} is in dollars, at scale 2 (3 for the rare half-cent strikes). */
  public record Contract(String symbol, String underlying, LocalDate expiration, OptionRight right,
                         BigDecimal strike) {

    public boolean call() {
      return right == OptionRight.CALL;
    }

    /** {@code max(0, S − K)} for a call, {@code max(0, K − S)} for a put; per share. */
    public BigDecimal intrinsic(BigDecimal underlyingPrice) {
      BigDecimal v = call() ? underlyingPrice.subtract(strike) : strike.subtract(underlyingPrice);
      return v.signum() > 0 ? v : BigDecimal.ZERO;
    }

    /** {@code AAPL 20 Nov 26 $250 Call}. */
    public String label() {
      return underlying + " " + expiration.format(LABEL_DATE) + " $" + strike.stripTrailingZeros().toPlainString()
          + " " + (call() ? "Call" : "Put");
    }
  }

  private OptionSymbol() {}

  /** An OCC option symbol. Null-safe; never throws. */
  public static boolean isOption(String symbol) {
    return symbol != null && symbol.length() >= 16 && symbol.length() <= 21 && PATTERN.matcher(symbol).matches()
        && parse(symbol).isPresent();
  }

  /** The contract, if {@code symbol} is a well-formed OCC symbol with a real calendar date and a positive strike. */
  public static Optional<Contract> parse(String symbol) {
    if (symbol == null) {
      return Optional.empty();
    }
    Matcher m = PATTERN.matcher(symbol.trim().toUpperCase(Locale.ROOT));
    if (!m.matches()) {
      return Optional.empty();
    }
    LocalDate expiration;
    try {
      expiration = LocalDate.parse(m.group(2), YYMMDD);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    BigDecimal strike = new BigDecimal(m.group(4)).divide(THOUSAND, 3, RoundingMode.UNNECESSARY).stripTrailingZeros();
    strike = strike.scale() < 2 ? strike.setScale(2) : strike;
    if (strike.signum() <= 0) {
      return Optional.empty();
    }
    OptionRight right = "C".equals(m.group(3)) ? OptionRight.CALL : OptionRight.PUT;
    return Optional.of(new Contract(m.group(0), m.group(1), expiration, right, strike));
  }

  /** The OCC symbol of a contract; {@code strike} must have at most 3 decimals and be below $100,000. */
  public static String format(String underlying, LocalDate expiration, OptionRight right, BigDecimal strike) {
    long thousandths = strike.multiply(THOUSAND).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    return underlying.toUpperCase(Locale.ROOT) + expiration.format(YYMMDD) + (right == OptionRight.CALL ? "C" : "P")
        + String.format(Locale.ROOT, "%08d", thousandths);
  }

  /** Shares per unit of quantity: 100 for an option, 1 for anything else. */
  public static BigDecimal multiplier(String symbol) {
    return isOption(symbol) ? MULTIPLIER_DEC : BigDecimal.ONE;
  }

  /** The dollar value of one unit of {@code symbol} at {@code price} per share: {@code price × multiplier}. */
  public static BigDecimal unitValue(String symbol, BigDecimal price) {
    return isOption(symbol) ? price.multiply(MULTIPLIER_DEC) : price;
  }
}
