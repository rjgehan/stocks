package com.stocks.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * Test helpers for the §18.3 DTO contract: discovers every class in {@code com.stocks.contract..dto} and builds
 * all-null and sample instances of any record reflectively.
 */
final class DtoSamples {

  static final BigDecimal SAMPLE_DECIMAL = new BigDecimal("12.3400");
  static final String SAMPLE_DECIMAL_JSON = "12.3400";
  static final Instant SAMPLE_INSTANT = Instant.parse("2026-10-09T14:31:02.120Z");
  static final LocalDate SAMPLE_DATE = LocalDate.of(2026, 10, 13);

  private DtoSamples() {}

  /** Every top-level and nested type in a {@code com.stocks.contract.<producer>.dto} package, sorted by name. */
  static List<Class<?>> dtoTypes() {
    try {
      PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
      MetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
      List<Class<?>> out = new ArrayList<>();
      for (Resource r : resolver.getResources("classpath*:com/stocks/contract/**/dto/*.class")) {
        String name = readers.getMetadataReader(r).getClassMetadata().getClassName();
        if (name.endsWith("package-info")) {
          continue;
        }
        out.add(Class.forName(name));
      }
      out.sort(Comparator.comparing(Class::getName));
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  static List<Class<?>> dtoRecords() {
    return dtoTypes().stream().filter(Class::isRecord).toList();
  }

  /** The JSON property names of a record, in component order (honouring {@code @JsonProperty} on the accessor). */
  static List<String> jsonNames(Class<?> record) {
    List<String> names = new ArrayList<>();
    for (RecordComponent rc : record.getRecordComponents()) {
      names.add(jsonName(rc));
    }
    return names;
  }

  static String jsonName(RecordComponent rc) {
    JsonProperty p = rc.getAccessor().getAnnotation(JsonProperty.class);
    return p != null && !p.value().isEmpty() ? p.value() : rc.getName();
  }

  /** The polymorphic discriminator property a record inherits from a {@code @JsonTypeInfo} interface, or null. */
  static String discriminator(Class<?> type) {
    for (Class<?> i : type.getInterfaces()) {
      JsonTypeInfo info = i.getAnnotation(JsonTypeInfo.class);
      if (info != null) {
        return info.property();
      }
    }
    return null;
  }

  /** A record with every reference component null and every primitive at its default. */
  static Object allNull(Class<?> record) {
    RecordComponent[] rcs = record.getRecordComponents();
    Object[] args = new Object[rcs.length];
    for (int i = 0; i < rcs.length; i++) {
      Class<?> t = rcs[i].getType();
      args[i] = t.isPrimitive() ? Array.get(Array.newInstance(t, 1), 0) : null;
    }
    return construct(record, args);
  }

  /** A record whose every component holds a non-null sample (decimals are {@link #SAMPLE_DECIMAL}). */
  static Object sample(Class<?> record) {
    return sample(record, 0);
  }

  private static Object sample(Type type, int depth) {
    if (type instanceof ParameterizedType pt) {
      Class<?> raw = (Class<?>) pt.getRawType();
      Type[] args = pt.getActualTypeArguments();
      if (List.class.isAssignableFrom(raw)) {
        return List.of(sample(args[0], depth + 1));
      }
      if (Set.class.isAssignableFrom(raw)) {
        Set<Object> s = new LinkedHashSet<>();
        s.add(sample(args[0], depth + 1));
        return s;
      }
      if (Map.class.isAssignableFrom(raw)) {
        return Map.of("k", "v");
      }
      throw new IllegalArgumentException("unsupported generic type " + type);
    }
    Class<?> c = (Class<?>) type;
    if (c == String.class) {
      return "s";
    }
    if (c == BigDecimal.class) {
      return SAMPLE_DECIMAL;
    }
    if (c == Instant.class) {
      return SAMPLE_INSTANT;
    }
    if (c == LocalDate.class) {
      return SAMPLE_DATE;
    }
    if (c == boolean.class || c == Boolean.class) {
      return Boolean.TRUE;
    }
    if (c == int.class || c == Integer.class) {
      return 7;
    }
    if (c == long.class || c == Long.class) {
      return 7L;
    }
    if (c.isEnum()) {
      return c.getEnumConstants()[0];
    }
    if (c.isRecord()) {
      if (depth > 8) {
        throw new IllegalStateException("DTO nesting too deep at " + c);
      }
      RecordComponent[] rcs = c.getRecordComponents();
      Object[] args = new Object[rcs.length];
      for (int i = 0; i < rcs.length; i++) {
        args[i] = sample(rcs[i].getGenericType(), depth + 1);
      }
      return construct(c, args);
    }
    if (c.isSealed()) {
      return sample(c.getPermittedSubclasses()[0], depth);
    }
    throw new IllegalArgumentException("no sample for " + c.getName());
  }

  private static Object construct(Class<?> record, Object[] args) {
    try {
      Class<?>[] types = new Class<?>[args.length];
      RecordComponent[] rcs = record.getRecordComponents();
      for (int i = 0; i < rcs.length; i++) {
        types[i] = rcs[i].getType();
      }
      Constructor<?> ctor = record.getDeclaredConstructor(types);
      ctor.setAccessible(true);
      return ctor.newInstance(args);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot construct " + record.getName(), e);
    }
  }
}
