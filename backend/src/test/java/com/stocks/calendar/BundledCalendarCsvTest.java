package com.stocks.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.contract.calendar.TradingDay;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code calendar/xnys-2016-2028.csv} is exactly what {@link NyseCalendarGenerator} produces (§9.1). Regenerate with
 * {@code ./mvnw test -Dtest=BundledCalendarCsvTest -Dstocks.calendar.regenerate=true}.
 */
class BundledCalendarCsvTest {

  static final Path CSV = Path.of("src", "main", "resources", BundledCalendarLoader.RESOURCE);
  static final String HEADER = "trade_date,pre_open_et,rth_open_et,rth_close_et,post_close_et,settlement_date,early_close";

  static List<String> generated() {
    List<String> lines = new ArrayList<>();
    lines.add(HEADER);
    NyseCalendarGenerator.generate(BundledCalendarLoader.FIRST, BundledCalendarLoader.LAST)
        .forEach(r -> lines.add(r.csv()));
    return lines;
  }

  @Test
  void csvMatchesTheGenerator() throws IOException {
    List<String> expected = generated();
    if (Boolean.getBoolean("stocks.calendar.regenerate")) {
      Files.write(CSV, expected, StandardCharsets.UTF_8);
    }
    assertThat(Files.readAllLines(CSV, StandardCharsets.UTF_8)).containsExactlyElementsOf(expected);
  }

  @Test
  void loaderReadsEveryRowWithEtInstants() {
    List<TradingDay> days = BundledCalendarLoader.load();
    assertThat(days).hasSize(generated().size() - 1);
    TradingDay d = days.stream().filter(x -> x.tradeDate().toString().equals("2026-10-09")).findFirst().orElseThrow();
    assertThat(d.preOpenAt()).hasToString("2026-10-09T08:00:00Z");
    assertThat(d.rthOpenAt()).hasToString("2026-10-09T13:30:00Z");
    assertThat(d.rthCloseAt()).hasToString("2026-10-09T20:00:00Z");
    assertThat(d.postCloseAt()).hasToString("2026-10-10T00:00:00Z");
    assertThat(d.settlementDate()).hasToString("2026-10-13");
    assertThat(d.source()).isEqualTo("bundled");
    assertThat(days.get(0).tradeDate()).hasToString("2016-01-04");
    assertThat(days.get(days.size() - 1).tradeDate()).hasToString("2028-12-29");
  }
}
