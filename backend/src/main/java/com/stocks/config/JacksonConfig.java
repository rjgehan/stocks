package com.stocks.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * The JSON rules of §6.3 for Boot's single {@code JsonMapper}, which REST, STOMP and MCP all use (WS5's
 * {@code McpConfig} hands this same bean to Spring AI as {@code mcpServerJsonMapper}):
 * <ul>
 *   <li>every {@link BigDecimal} is a JSON <b>string</b> in plain notation ({@code "227.4000"}, never {@code 2.274E2});</li>
 *   <li>decimal input is accepted as a string or a number, and never passes through {@code double}
 *       ({@code USE_BIG_DECIMAL_FOR_FLOATS});</li>
 *   <li>null fields are always included, so DTO shapes are stable;</li>
 *   <li>{@link Instant} is ISO-8601 UTC with milliseconds and {@code Z}; {@code LocalDate} is {@code YYYY-MM-DD};
 *       durations are ISO-8601 ({@code PT15M});</li>
 *   <li>enum input is case-insensitive; output is the constant name, except the wire-value enums (§6.3), which
 *       carry their own {@code @JsonValue}/{@code @JsonCreator}.</li>
 * </ul>
 * OpenAPI generation (springdoc, Jackson 2) does not see this mapper; {@code OpenApiConfig} applies the matching
 * schema rules (§18).
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

  /** {@code 2026-10-09T14:00:00.000Z}: UTC, always three fraction digits. */
  public static final DateTimeFormatter INSTANT_FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  @Bean
  JsonMapperBuilderCustomizer stocksJsonMapperCustomizer() {
    return JacksonConfig::configure;
  }

  /**
   * Applies the §6.3 rules to a builder. Public so tests and tools can build an identical mapper without a Spring
   * context: {@code JacksonConfig.configure(JsonMapper.builder()).build()}.
   */
  public static JsonMapper.Builder configure(JsonMapper.Builder builder) {
    SimpleModule module = new SimpleModule("stocks-json");
    module.addSerializer(BigDecimal.class, new PlainDecimalStringSerializer());
    module.addSerializer(Instant.class, new InstantMillisSerializer());
    return builder
        .addModule(module)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
        .changeDefaultPropertyInclusion(incl -> JsonInclude.Value.construct(JsonInclude.Include.ALWAYS,
            JsonInclude.Include.ALWAYS));
  }

  /** A mapper with exactly the §6.3 rules (Boot's own defaults not applied); for tests and tools. */
  public static JsonMapper newMapper() {
    return configure(JsonMapper.builder()).build();
  }

  /** {@code BigDecimal} → {@code "227.4000"} (scale kept, never exponent notation). */
  static final class PlainDecimalStringSerializer extends ValueSerializer<BigDecimal> {
    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
      gen.writeString(value.toPlainString());
    }
  }

  /** {@code Instant} → {@code "2026-10-09T14:00:00.000Z"} (sub-millisecond digits are dropped on the wire). */
  static final class InstantMillisSerializer extends ValueSerializer<Instant> {
    @Override
    public void serialize(Instant value, JsonGenerator gen, SerializationContext ctxt) {
      gen.writeString(INSTANT_FORMAT.format(value));
    }
  }
}
