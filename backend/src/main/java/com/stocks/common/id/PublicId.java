package com.stocks.common.id;

import com.stocks.common.time.TimeSource;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Generates and parses external ids {@code <prefix>_<ULID>} (§6.1, D20), e.g. {@code ord_01J9Z3K4M5N6P7Q8R9S0T1V2W3}.
 * The ULID time comes from {@link TimeSource}, so ids follow test and SIM time; ids from one instance are strictly
 * increasing. Inject the bean and call {@code publicId.next(Prefix.ORDER)}.
 */
@Component
public final class PublicId {

  /** Prefix (3 lowercase letters) + '_' + ULID. */
  public static final int LENGTH = 3 + 1 + Ulid.LENGTH;

  private final TimeSource time;
  private final Ulid.Generator generator;

  @Autowired
  public PublicId(TimeSource time) {
    this(time, new SecureRandom());
  }

  /** For tests that need reproducible ids: pass a seeded generator. */
  public PublicId(TimeSource time, RandomGenerator random) {
    this.time = Objects.requireNonNull(time, "time");
    this.generator = new Ulid.Generator(random);
  }

  /** A new id with this prefix. */
  public String next(Prefix prefix) {
    return prefix.code() + "_" + generator.next(time.now().toEpochMilli());
  }

  /** True if {@code id} is {@code <prefix>_<ULID>} for this prefix. */
  public static boolean isValid(String id, Prefix prefix) {
    return id != null
        && id.length() == LENGTH
        && id.startsWith(prefix.code() + "_")
        && Ulid.isValid(id.substring(4));
  }

  /** The prefix of a well-formed public id. */
  public static Optional<Prefix> prefixOf(String id) {
    if (id == null || id.length() != LENGTH || id.charAt(3) != '_' || !Ulid.isValid(id.substring(4))) {
      return Optional.empty();
    }
    try {
      return Optional.of(Prefix.fromCode(id.substring(0, 3)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
