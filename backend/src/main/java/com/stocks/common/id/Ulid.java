package com.stocks.common.id;

import java.time.Instant;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * ULID: 26 Crockford base32 characters, a 48-bit millisecond timestamp followed by 80 random bits
 * (https://github.com/ulid/spec). Values sort lexicographically by time. {@link Generator} is monotonic: within one
 * millisecond (or if the clock steps back) it increments the previous value, so ids from one generator are strictly
 * increasing.
 */
public final class Ulid {

  public static final int LENGTH = 26;
  public static final long MAX_TIMESTAMP = (1L << 48) - 1;

  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final byte[] DECODE = new byte[128];

  static {
    java.util.Arrays.fill(DECODE, (byte) -1);
    for (int i = 0; i < ALPHABET.length; i++) {
      DECODE[ALPHABET[i]] = (byte) i;
      DECODE[Character.toLowerCase(ALPHABET[i])] = (byte) i;
    }
  }

  private Ulid() {}

  /**
   * Encodes a timestamp and 80 bits of randomness ({@code randHi}: the top 16 bits, {@code randLo}: the low 64 bits).
   */
  static String encode(long epochMillis, long randHi16, long randLo64) {
    if (epochMillis < 0 || epochMillis > MAX_TIMESTAMP) {
      throw new IllegalArgumentException("ULID timestamp out of range: " + epochMillis);
    }
    char[] out = new char[LENGTH];
    // 48-bit timestamp -> 10 chars (the first char carries only 3 bits)
    long t = epochMillis;
    for (int i = 9; i >= 0; i--) {
      out[i] = ALPHABET[(int) (t & 31)];
      t >>>= 5;
    }
    // 80-bit randomness -> 16 chars: 16 high bits + 64 low bits
    long hi = randHi16 & 0xFFFFL;
    long lo = randLo64;
    for (int i = 25; i >= 10; i--) {
      out[i] = ALPHABET[(int) (lo & 31)];
      lo = (lo >>> 5) | (hi << 59);
      hi >>>= 5;
    }
    return new String(out);
  }

  /** True if {@code s} is a syntactically valid ULID (case-insensitive, first char ≤ '7'). */
  public static boolean isValid(String s) {
    if (s == null || s.length() != LENGTH) {
      return false;
    }
    for (int i = 0; i < LENGTH; i++) {
      char c = s.charAt(i);
      if (c >= 128 || DECODE[c] < 0) {
        return false;
      }
    }
    return DECODE[s.charAt(0)] <= 7;
  }

  /** The timestamp component of a ULID. */
  public static Instant timestamp(String ulid) {
    if (!isValid(ulid)) {
      throw new IllegalArgumentException("Not a ULID: " + ulid);
    }
    long t = 0;
    for (int i = 0; i < 10; i++) {
      t = (t << 5) | DECODE[ulid.charAt(i)];
    }
    return Instant.ofEpochMilli(t);
  }

  /** Thread-safe monotonic generator. */
  public static final class Generator {

    private final RandomGenerator random;
    private long lastMillis = -1;
    private long lastHi;
    private long lastLo;

    public Generator(RandomGenerator random) {
      this.random = Objects.requireNonNull(random, "random");
    }

    public synchronized String next(long epochMillis) {
      if (epochMillis > lastMillis) {
        lastMillis = epochMillis;
        lastHi = random.nextLong() & 0xFFFFL;
        lastLo = random.nextLong();
      } else {
        // Same millisecond, or the clock stepped back: keep the last timestamp and increment the 80-bit value.
        lastLo++;
        if (lastLo == 0) {
          lastHi = (lastHi + 1) & 0xFFFFL;
          if (lastHi == 0) {
            // 2^80 ids in one millisecond: move to the next millisecond rather than wrap around.
            lastMillis++;
          }
        }
      }
      return encode(lastMillis, lastHi, lastLo);
    }
  }
}
