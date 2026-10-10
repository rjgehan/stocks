package com.stocks.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.stocks.common.domain.HistoryRange;
import com.stocks.config.JacksonConfig;
import com.stocks.mcp.tools.WhoamiDto;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.io.IOException;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * §20.1 output-schema post-processing: every advertised {@code outputSchema} matches what Boot's mapper writes, as
 * judged by the MCP SDK's own validator (the one the server runs on every {@code structuredContent}). Checked for
 * {@code whoami} in detail and, as an early warning for the Phase 1 tools, for every §18.3 response DTO.
 */
class McpOutputSchemasTest {

  static final JsonMapper MAPPER = JacksonConfig.newMapper();
  static final JsonSchemaValidator VALIDATOR = McpJsonDefaults.getSchemaValidator();

  record WithWireEnumAndGenerics(HistoryRange range, List<BigDecimal> values, Map<String, BigDecimal> byKey,
                                 int count, Integer maybe, boolean flag) {}

  record WithDouble(double ratio) {}

  @Test
  void whoamiSchemaFollowsTheOpenApiRules() {
    Map<String, Object> schema = McpOutputSchemas.forReturnType(WhoamiDto.class, MAPPER);
    String json = MAPPER.writeValueAsString(schema);
    assertThat(json).doesNotContain("\"number\"");
    assertThat(list(schema, "required")).containsExactlyInAnyOrder("me", "account", "rateLimits");

    Map<String, Object> account = map(map(schema, "properties"), "account");
    assertThat(list(account, "type")).containsExactly("object", "null");
    Map<String, Object> accountProps = map(account, "properties");
    assertThat(map(accountProps, "equity")).containsEntry("type", List.of("string", "null"))
        .containsEntry("format", "decimal");
    assertThat(map(accountProps, "runNo")).containsEntry("type", "integer");   // primitive int: never null
    assertThat(list(account, "required")).hasSize(25);
    assertThat(map(map(map(accountProps, "cash"), "properties"), "settled"))
        .containsEntry("type", List.of("string", "null")).containsEntry("format", "decimal");

    Map<String, Object> rateLimits = map(map(schema, "properties"), "rateLimits");
    assertThat(list(rateLimits, "required"))
        .containsExactlyInAnyOrder("readsRemaining", "writesRemainingMinute", "writesRemainingDay");

    // a nullable enum lists null; a nullable string allows null
    Map<String, Object> me = resolve(schema, map(map(schema, "properties"), "me"));
    Map<String, Object> meProps = map(me, "properties");
    assertThat(list(map(meProps, "authMethod"), "enum")).contains("API_KEY", "SESSION").containsNull();
    assertThat(map(meProps, "aiModel")).containsEntry("type", List.of("string", "null"));
    assertThat(list(me, "required")).hasSize(13);
  }

  @Test
  void whoamiSamplesValidate() {
    Map<String, Object> schema = McpOutputSchemas.forReturnType(WhoamiDto.class, MAPPER);
    assertValid(schema, Samples.full(WhoamiDto.class));
    assertValid(schema, Samples.nulls(WhoamiDto.class));
  }

  @Test
  void wireEnumsGenericsAndBoxes() {
    Map<String, Object> schema = McpOutputSchemas.forReturnType(WithWireEnumAndGenerics.class, MAPPER);
    Map<String, Object> props = map(schema, "properties");
    assertThat(list(map(props, "range"), "enum")).contains("1D", "YTD", "ALL").containsNull().doesNotContain("D1");
    assertThat(map(map(props, "values"), "items")).containsEntry("type", "string").containsEntry("format", "decimal");
    assertThat(map(props, "byKey")).containsEntry("type", List.of("object", "null"));   // map values stay open
    assertThat(map(props, "count")).containsEntry("type", "integer");
    assertThat(map(props, "maybe")).containsEntry("type", List.of("integer", "null"));
    assertThat(map(props, "flag")).containsEntry("type", "boolean");
    assertValid(schema, new WithWireEnumAndGenerics(HistoryRange.D1, List.of(new BigDecimal("1.50")),
        Map.of("a", new BigDecimal("2.0000")), 3, null, true));
  }

  @Test
  void aFloatingPointMemberFailsFast() {
    assertThatThrownBy(() -> McpOutputSchemas.forReturnType(WithDouble.class, MAPPER))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("number");
  }

  static Stream<Class<?>> responseDtos() throws IOException {
    return contractDtos().stream().filter(c -> c.isRecord() && !c.getSimpleName().endsWith("Request"));
  }

  /** Early warning for Phase 1: every §18.3 response DTO gets a valid schema that its own samples satisfy. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("responseDtos")
  void everyResponseDtoGetsAMatchingSchema(Class<?> dto) {
    Map<String, Object> schema = McpOutputSchemas.forReturnType(dto, MAPPER);
    assertThat(MAPPER.writeValueAsString(schema)).doesNotContain("\"number\"");
    assertValid(schema, Samples.full(dto));
    assertValid(schema, Samples.nulls(dto));
  }

  // ------------------------------------------------------------------------------------------- helpers

  static void assertValid(Map<String, Object> schema, Object sample) {
    Object json = MAPPER.readValue(MAPPER.writeValueAsString(sample), Object.class);
    JsonSchemaValidator.ValidationResponse r = VALIDATOR.validate(schema, json);
    assertThat(r.valid()).as("%s\nschema: %s\njson: %s", r.errorMessage(), MAPPER.writeValueAsString(schema),
        MAPPER.writeValueAsString(json)).isTrue();
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Map<String, Object> m, String key) {
    return (Map<String, Object>) m.get(key);
  }

  @SuppressWarnings("unchecked")
  static List<Object> list(Map<String, Object> m, String key) {
    return (List<Object>) m.get(key);
  }

  /** Follows {@code $ref} and {@code anyOf: [{$ref}, null]} into {@code $defs}. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> resolve(Map<String, Object> root, Map<String, Object> node) {
    if (node.containsKey("anyOf")) {
      node = (Map<String, Object>) ((List<Object>) node.get("anyOf")).get(0);
    }
    if (node.get("$ref") instanceof String ref) {
      return map(map(root, "$defs"), ref.substring("#/$defs/".length()));
    }
    return node;
  }

  static List<Class<?>> contractDtos() throws IOException {
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    MetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
    List<Class<?>> types = new ArrayList<>();
    for (Resource r : resolver.getResources("classpath*:com/stocks/contract/**/dto/*.class")) {
      String name = readers.getMetadataReader(r).getClassMetadata().getClassName();
      try {
        types.add(Class.forName(name));
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException(e);
      }
    }
    types.sort(Comparator.comparing(Class::getName));
    return types;
  }

  /** Reflective sample instances of records: every member set ({@link #full}) or every nullable member null. */
  static final class Samples {

    private Samples() {}

    static Object full(Class<?> type) {
      return build(type, true, 0);
    }

    static Object nulls(Class<?> type) {
      return build(type, false, 0);
    }

    private static Object build(Class<?> type, boolean full, int depth) {
      try {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
          types[i] = components[i].getType();
          args[i] = full || types[i].isPrimitive()
              ? value(components[i].getType(), components[i].getGenericType(), depth) : null;
        }
        Constructor<?> c = type.getDeclaredConstructor(types);
        c.setAccessible(true);
        return c.newInstance(args);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("Cannot build a sample " + type, e);
      }
    }

    private static Object value(Class<?> raw, Type generic, int depth) {
      if (raw == boolean.class || raw == Boolean.class) {
        return true;
      }
      if (raw == int.class || raw == Integer.class) {
        return 3;
      }
      if (raw == long.class || raw == Long.class) {
        return 7L;
      }
      if (raw == String.class) {
        return "x";
      }
      if (raw == BigDecimal.class) {
        return new BigDecimal("12.3400");
      }
      if (raw == Instant.class) {
        return Instant.parse("2026-10-09T14:31:02.120Z");
      }
      if (raw == LocalDate.class) {
        return LocalDate.of(2026, 10, 13);
      }
      if (raw.isEnum()) {
        return raw.getEnumConstants()[0];
      }
      if (raw.isRecord()) {
        return depth > 6 ? null : build(raw, true, depth + 1);
      }
      if (raw.isSealed() && raw.isAnnotationPresent(JsonSubTypes.class)) {
        return value(raw.getAnnotation(JsonSubTypes.class).value()[0].value(), null, depth);
      }
      if (List.class.isAssignableFrom(raw) && generic instanceof ParameterizedType p) {
        Type element = p.getActualTypeArguments()[0];
        Class<?> elementRaw = element instanceof ParameterizedType ep ? (Class<?>) ep.getRawType() : (Class<?>) element;
        Object item = value(elementRaw, element, depth + 1);
        return item == null ? List.of() : List.of(item);
      }
      if (Map.class.isAssignableFrom(raw)) {
        return Map.of("k", "v");
      }
      if (raw.isArray()) {
        Object array = Array.newInstance(raw.getComponentType(), 1);
        Array.set(array, 0, value(raw.getComponentType(), raw.getComponentType(), depth + 1));
        return array;
      }
      if (raw == Object.class) {
        return "x";
      }
      throw new IllegalStateException("No sample for " + generic);
    }
  }
}
