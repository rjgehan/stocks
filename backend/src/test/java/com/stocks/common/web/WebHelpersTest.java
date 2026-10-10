package com.stocks.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WebHelpersTest {

  @Test
  void clipStripsControlCharactersThenTruncates() {
    assertThat(Text.clip(null, 10)).isNull();
    assertThat(Text.clip("curl/8.7.1", 300)).isEqualTo("curl/8.7.1");
    assertThat(Text.clip("a\r\nb\tc\u0000d\u007f\u0085e", 300)).isEqualTo("abcde");
    assertThat(Text.clip("zero​width‮bidi", 300)).isEqualTo("zerowidthbidi");
    assertThat(Text.clip("x".repeat(10_000), 300)).hasSize(300);
    assertThat(Text.clip("abc", 0)).isEmpty();
    // never split a surrogate pair: "😀" is two UTF-16 units
    assertThat(Text.clip("ab😀", 3)).isEqualTo("ab");
    assertThat(Text.clip("ab😀", 4)).isEqualTo("ab😀");
    assertThatThrownBy(() -> Text.clip("x", -1)).isInstanceOf(IllegalArgumentException.class);
    assertThat(Text.trimToNull("  ")).isNull();
    assertThat(Text.trimToNull(" a ")).isEqualTo("a");
  }

  @Test
  void cursorRoundTripsAndRejectsGarbage() {
    Cursor c = Cursor.of(Instant.parse("2026-10-09T14:00:00.123456Z"), 42);
    assertThat(c.token()).matches("^[A-Za-z0-9_-]+$");
    assertThat(Cursor.parse(c.token())).isEqualTo(c);
    assertThat(Cursor.parse(null)).isNull();
    assertThat(Cursor.parse(" ")).isNull();
    assertThatThrownBy(() -> Cursor.parse("not-a-cursor"))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
          assertThat(e.fields()).extracting(f -> f.field()).containsExactly("cursor");
        });
  }

  @Test
  void pageLimitsAndLookahead() {
    assertThat(Page.clampLimit(null)).isEqualTo(50);
    assertThat(Page.clampLimit(0)).isEqualTo(50);
    assertThat(Page.clampLimit(10)).isEqualTo(10);
    assertThat(Page.clampLimit(1000)).isEqualTo(200);

    List<Integer> rows = IntStream.rangeClosed(1, 6).boxed().toList();   // limit 5 + 1 lookahead row
    Page<Integer> page = Page.fromLookahead(rows, 5, i -> Cursor.of(Instant.EPOCH.plusSeconds(i), i));
    assertThat(page.items()).containsExactly(1, 2, 3, 4, 5);
    assertThat(Cursor.parse(page.nextCursor()).id()).isEqualTo(5);

    Page<Integer> last = Page.fromLookahead(rows.subList(0, 3), 5, i -> Cursor.of(Instant.EPOCH, i));
    assertThat(last.items()).hasSize(3);
    assertThat(last.nextCursor()).isNull();
    assertThat(last.map(String::valueOf).items()).containsExactly("1", "2", "3");
    assertThat(Page.empty().items()).isEmpty();
  }
}
