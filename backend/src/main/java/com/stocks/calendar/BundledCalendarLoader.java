package com.stocks.calendar;

import com.stocks.contract.calendar.TradingDay;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code classpath:calendar/xnys-2016-2028.csv} (§9.1 source 3), produced by {@link NyseCalendarGenerator}.
 * Format: {@code trade_date,pre_open_et,rth_open_et,rth_close_et,post_close_et,settlement_date,early_close} with a
 * header line; times are ET wall-clock.
 */
public final class BundledCalendarLoader {

  public static final String RESOURCE = "calendar/xnys-2016-2028.csv";
  public static final LocalDate FIRST = LocalDate.of(2016, 1, 1);
  public static final LocalDate LAST = LocalDate.of(2028, 12, 31);

  private BundledCalendarLoader() {}

  public static List<TradingDay> load() {
    try (InputStream in = BundledCalendarLoader.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing classpath resource " + RESOURCE);
      }
      return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<TradingDay> parse(BufferedReader reader) throws IOException {
    List<TradingDay> out = new ArrayList<>(3300);
    String line;
    int lineNo = 0;
    while ((line = reader.readLine()) != null) {
      lineNo++;
      String l = line.trim();
      if (l.isEmpty() || l.startsWith("#") || l.startsWith("trade_date")) {
        continue;
      }
      String[] f = l.split(",");
      if (f.length != 7) {
        throw new IllegalStateException(RESOURCE + ":" + lineNo + ": expected 7 fields, got " + f.length);
      }
      out.add(TradingDays.of(LocalDate.parse(f[0]), LocalTime.parse(f[1]), LocalTime.parse(f[2]),
          LocalTime.parse(f[3]), LocalTime.parse(f[4]), LocalDate.parse(f[5]), Boolean.parseBoolean(f[6]),
          TradingDays.BUNDLED));
    }
    return out;
  }
}
