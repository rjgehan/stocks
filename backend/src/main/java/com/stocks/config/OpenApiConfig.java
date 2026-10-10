package com.stocks.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stocks.common.domain.WireEnum;
import com.stocks.common.error.Problem;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI generation rules (§18, WS1). springdoc 3.1.1 introspects models through swagger-core's own Jackson 2
 * setup, so the §6.3 Jackson 3 rules do not reach it; these rules make {@code docs/openapi.json} (and the
 * {@code schema.d.ts} WS6 codes against) match §18.3:
 * <ol>
 *   <li>{@code BigDecimal} is {@code type: string, format: decimal}.</li>
 *   <li>Records under {@code com.stocks}: <b>response</b> DTOs have every component {@code required}, and every
 *       non-primitive component also nullable (OpenAPI 3.1 {@code type: [T, "null"]}, or
 *       {@code anyOf: [{$ref}, {type: "null"}]} for a nested object), so openapi-typescript emits
 *       {@code field: T | null}. <b>Request</b> DTOs (simple name ends in {@code Request}) are nullable and required
 *       only where annotated {@code @NotNull}/{@code @NotBlank}/{@code @NotEmpty} or
 *       {@code @Schema(requiredMode = REQUIRED)}.</li>
 *   <li>Wire-value enums ({@link WireEnum}: {@code HistoryRange}, {@code ChartRange}, {@code Timeframe}) publish their
 *       wire values ({@code "1D"}, {@code "1Min"}).</li>
 *   <li>Schema names drop the Java {@code Dto} suffix ({@code QuoteDto} → {@code Quote}); every {@code $ref} follows.
 *       A name collision fails the generation.</li>
 *   <li>(CI) no {@code type: number} anywhere and every response property required: asserted by
 *       {@code OpenApiRulesIT} and {@code .github/scripts/openapi-assert.mjs}.</li>
 * </ol>
 * Also: every operation gets a {@code default} response with the §6.5 {@code Problem} body
 * ({@code application/problem+json}); the document lists the two credentials (bearer {@code stk_…} key, session
 * cookie) and a same-origin server, so the output does not depend on the host that generated it.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

  /** {@code info.version}: the REST API version (the {@code /api/v1} prefix), not the build version. */
  public static final String API_VERSION = "v1";
  static final String SCHEMAS = "#/components/schemas/";

  static {
    SpringDocUtils.getConfig().replaceWithSchema(BigDecimal.class, decimalSchema());
  }

  static Schema<?> decimalSchema() {
    StringSchema s = new StringSchema();
    s.format("decimal");
    s.setTypes(new LinkedHashSet<>(List.of("string")));
    return s;
  }

  @Bean
  OpenAPI stocksOpenApi() {
    return new OpenAPI()
        .info(new Info().title("stocks API").version(API_VERSION)
            .description("Multiplayer paper-trading league. Decimals are strings; every response field is present "
                + "(null when unused); errors are RFC 9457 problems with a stable `code`. Authenticate with "
                + "`Authorization: Bearer stk_…` (bots, scripts) or the browser session cookie plus the "
                + "X-XSRF-TOKEN header."))
        .servers(List.of(new Server().url("/").description("Same origin")))
        .components(new Components()
            .addSecuritySchemes("apiKey", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                .bearerFormat("stk_<keyId>_<secret>")
                .description("API key issued by the admin (bots) or from Settings (humans, read-only by default)."))
            .addSecuritySchemes("session", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE).name("stocks_sid")
                .description("Browser session from POST /api/v1/auth/login (__Host-stocks_sid in production); "
                    + "unsafe methods also need X-XSRF-TOKEN.")))
        .addSecurityItem(new SecurityRequirement().addList("apiKey"))
        .addSecurityItem(new SecurityRequirement().addList("session"));
  }

  @Bean
  StocksModelConverter stocksModelConverter() {
    return new StocksModelConverter();
  }

  @Bean
  OpenApiCustomizer stocksOpenApiCustomizer() {
    return openApi -> {
      addProblemResponses(openApi);
      renameDtoSchemas(openApi);
    };
  }

  // ------------------------------------------------------------------------------------------- rules 2 and 3

  /** Rules 2 and 3, applied as swagger-core resolves each type. Registered by springdoc (it is a bean). */
  public static final class StocksModelConverter implements ModelConverter {

    @Override
    public Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
      Class<?> raw = rawClass(type.getType());
      if (raw != null && raw.isEnum() && WireEnum.class.isAssignableFrom(raw)) {
        return wireEnumSchema(raw);
      }
      if (!chain.hasNext()) {
        return null;
      }
      Schema<?> result = chain.next().resolve(type, context, chain);
      if (result != null && raw != null && raw.isRecord() && raw.getName().startsWith("com.stocks.")) {
        Schema<?> model = result;
        if (result.get$ref() != null && result.get$ref().startsWith(SCHEMAS)) {
          Schema<?> defined = context.getDefinedModels().get(result.get$ref().substring(SCHEMAS.length()));
          if (defined != null) {
            model = defined;
          }
        }
        applyRecordRules(raw, model);
      }
      return result;
    }

    private static Class<?> rawClass(Type type) {
      if (type instanceof Class<?> c) {
        return c;
      }
      try {
        return type == null ? null : Json.mapper().constructType(type).getRawClass();
      } catch (RuntimeException e) {
        return null;
      }
    }
  }

  static Schema<?> wireEnumSchema(Class<?> enumType) {
    StringSchema s = new StringSchema();
    s.setTypes(new LinkedHashSet<>(List.of("string")));
    for (Object constant : enumType.getEnumConstants()) {
      s.addEnumItem(((WireEnum) constant).wireValue());
    }
    return s;
  }

  /** Rule 2 on one record's model schema. Idempotent. */
  static void applyRecordRules(Class<?> record, Schema<?> model) {
    if (model.getProperties() == null || model.getProperties().isEmpty()) {
      return;
    }
    boolean request = record.getSimpleName().endsWith("Request");
    Map<String, RecordComponent> components = new HashMap<>();
    for (RecordComponent rc : record.getRecordComponents()) {
      components.put(jsonName(record, rc), rc);
    }
    Map<String, Schema> properties = new LinkedHashMap<>();
    List<String> required = new ArrayList<>();
    for (Map.Entry<String, Schema> e : model.getProperties().entrySet()) {
      RecordComponent rc = components.get(e.getKey());
      boolean primitive = rc != null && rc.getType().isPrimitive();
      properties.put(e.getKey(), primitive ? e.getValue() : nullable(e.getValue()));
      if (!request || (rc != null && requiredByAnnotation(record, rc))) {
        required.add(e.getKey());
      }
    }
    model.setProperties(properties);
    model.setRequired(required.isEmpty() ? null : required);
  }

  /** The JSON property name: {@code @JsonProperty} on the component, accessor or field, else the component name. */
  static String jsonName(Class<?> record, RecordComponent rc) {
    for (AnnotatedElement el : elements(record, rc)) {
      JsonProperty jp = el.getAnnotation(JsonProperty.class);
      if (jp != null && !jp.value().isEmpty()) {
        return jp.value();
      }
    }
    return rc.getName();
  }

  static boolean requiredByAnnotation(Class<?> record, RecordComponent rc) {
    for (AnnotatedElement el : elements(record, rc)) {
      for (Annotation a : el.getAnnotations()) {
        if (a instanceof NotNull || a instanceof NotBlank || a instanceof NotEmpty) {
          return true;
        }
        if (a instanceof io.swagger.v3.oas.annotations.media.Schema s
            && (s.requiredMode() == io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
                || s.required())) {
          return true;
        }
      }
    }
    return false;
  }

  private static List<AnnotatedElement> elements(Class<?> record, RecordComponent rc) {
    List<AnnotatedElement> out = new ArrayList<>(List.of(rc, rc.getAccessor()));
    try {
      Field f = record.getDeclaredField(rc.getName());
      out.add(f);
    } catch (NoSuchFieldException ignored) {
      // records always have the field
    }
    return out;
  }

  /** Makes a property schema accept null (OpenAPI 3.1). Idempotent. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  static Schema nullable(Schema s) {
    if (s.get$ref() != null) {
      Schema wrapper = new Schema<>();
      wrapper.setAnyOf(new ArrayList<>(List.of(s, nullSchema())));
      if (s.getDescription() != null) {
        wrapper.setDescription(s.getDescription());
      }
      return wrapper;
    }
    if (s.getType() == null && (s.getTypes() == null || s.getTypes().isEmpty())) {
      List<Schema> alternatives = s.getAnyOf() != null ? s.getAnyOf() : s.getOneOf();
      if (alternatives != null) {
        if (alternatives.stream().noneMatch(OpenApiConfig::isNullSchema)) {
          List<Schema> copy = new ArrayList<>(alternatives);
          copy.add(nullSchema());
          if (s.getAnyOf() != null) {
            s.setAnyOf(copy);
          } else {
            s.setOneOf(copy);
          }
        }
        return s;
      }
      return s; // untyped (any value): already accepts null
    }
    Set<String> types = new LinkedHashSet<>();
    if (s.getTypes() != null) {
      types.addAll(s.getTypes());
    }
    if (s.getType() != null) {
      types.add(s.getType());
    }
    types.add("null");
    s.setTypes(types);
    if (s.getEnum() != null && !s.getEnum().contains(null)) {
      List copy = new ArrayList<>(s.getEnum());
      copy.add(null);
      s.setEnum(copy);
    }
    return s;
  }

  static Schema<?> nullSchema() {
    Schema<?> n = new Schema<>();
    n.setTypes(new LinkedHashSet<>(List.of("null")));
    return n;
  }

  private static boolean isNullSchema(Schema<?> s) {
    return s.getTypes() != null && s.getTypes().equals(Set.of("null"));
  }

  // ------------------------------------------------------------------------------------------- problem responses

  /** Adds {@code default: Problem (application/problem+json)} to every operation that has no default response. */
  static void addProblemResponses(OpenAPI openApi) {
    if (openApi.getPaths() == null || openApi.getPaths().isEmpty()) {
      return;
    }
    boolean v31 = openApi.getOpenapi() == null || openApi.getOpenapi().startsWith("3.1");
    ResolvedSchema resolved = ModelConverters.getInstance(v31)
        .resolveAsResolvedSchema(new AnnotatedType(Problem.class).resolveAsRef(true));
    if (openApi.getComponents() == null) {
      openApi.setComponents(new Components());
    }
    if (resolved.referencedSchemas != null) {
      resolved.referencedSchemas.forEach((name, schema) -> {
        if (openApi.getComponents().getSchemas() == null || !openApi.getComponents().getSchemas().containsKey(name)) {
          openApi.getComponents().addSchemas(name, schema);
        }
      });
    }
    // the converter may not have seen these (they can be resolved outside springdoc's context): apply rule 2 here
    Map<String, Schema> schemas = openApi.getComponents().getSchemas();
    if (schemas != null) {
      applyIfPresent(schemas, "Problem", Problem.class);
      applyIfPresent(schemas, "ProblemField", com.stocks.common.error.ProblemField.class);
    }
    for (PathItem item : openApi.getPaths().values()) {
      for (Operation op : item.readOperations()) {
        ApiResponses responses = op.getResponses() == null ? new ApiResponses() : op.getResponses();
        if (!responses.containsKey("default")) {
          responses.addApiResponse("default", new ApiResponse()
              .description("Error: an RFC 9457 problem with a stable `code` (§6.5, §7.2)")
              .content(new Content().addMediaType("application/problem+json",
                  new MediaType().schema(new Schema<>().$ref(SCHEMAS + "Problem")))));
        }
        op.setResponses(responses);
      }
    }
  }

  private static void applyIfPresent(Map<String, Schema> schemas, String name, Class<?> type) {
    Schema<?> s = schemas.get(name);
    if (s != null) {
      applyRecordRules(type, s);
    }
  }

  // ------------------------------------------------------------------------------------------- rule 4

  /** Renames {@code XDto} component schemas to {@code X} and rewrites every {@code $ref}. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  static void renameDtoSchemas(OpenAPI openApi) {
    if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
      return;
    }
    Map<String, Schema> schemas = openApi.getComponents().getSchemas();
    Map<String, String> renames = new HashMap<>();
    for (String name : schemas.keySet()) {
      if (name.endsWith("Dto") && name.length() > 3) {
        renames.put(name, name.substring(0, name.length() - 3));
      }
    }
    Map<String, Schema> renamed = new TreeMap<>();
    for (Map.Entry<String, Schema> e : schemas.entrySet()) {
      String target = renames.getOrDefault(e.getKey(), e.getKey());
      if (renamed.containsKey(target)) {
        throw new IllegalStateException("OpenAPI schema name collision after dropping 'Dto': " + target);
      }
      renamed.put(target, e.getValue());
    }
    openApi.getComponents().setSchemas(renamed);
    if (renames.isEmpty()) {
      return;
    }
    UnaryOperator<String> rewrite = ref -> {
      if (ref != null && ref.startsWith(SCHEMAS)) {
        String to = renames.get(ref.substring(SCHEMAS.length()));
        if (to != null) {
          return SCHEMAS + to;
        }
      }
      return ref;
    };
    RefWalker walker = new RefWalker(rewrite);
    renamed.values().forEach(walker::schema);
    if (openApi.getPaths() != null) {
      for (PathItem item : openApi.getPaths().values()) {
        walker.parameters(item.getParameters());
        for (Operation op : item.readOperations()) {
          walker.parameters(op.getParameters());
          walker.requestBody(op.getRequestBody());
          if (op.getResponses() != null) {
            op.getResponses().values().forEach(walker::response);
          }
        }
      }
    }
    Components c = openApi.getComponents();
    if (c.getResponses() != null) {
      c.getResponses().values().forEach(walker::response);
    }
    if (c.getRequestBodies() != null) {
      c.getRequestBodies().values().forEach(walker::requestBody);
    }
    if (c.getParameters() != null) {
      walker.parameters(new ArrayList<>(c.getParameters().values()));
    }
    if (c.getHeaders() != null) {
      c.getHeaders().values().forEach(walker::header);
    }
  }

  /** Visits every schema reachable from paths and components and rewrites {@code $ref}s. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  static final class RefWalker {
    private final UnaryOperator<String> rewrite;
    private final Set<Schema> seen = Collections.newSetFromMap(new IdentityHashMap<>());

    RefWalker(UnaryOperator<String> rewrite) {
      this.rewrite = rewrite;
    }

    void schema(Schema s) {
      if (s == null || !seen.add(s)) {
        return;
      }
      if (s.get$ref() != null) {
        s.set$ref(rewrite.apply(s.get$ref()));
      }
      if (s.getProperties() != null) {
        ((Map<String, Schema>) s.getProperties()).values().forEach(this::schema);
      }
      schema(s.getItems());
      if (s.getAdditionalProperties() instanceof Schema ap) {
        schema(ap);
      }
      for (List<Schema> list : new List[] {s.getAllOf(), s.getAnyOf(), s.getOneOf(), s.getPrefixItems()}) {
        if (list != null) {
          list.forEach(this::schema);
        }
      }
      schema(s.getNot());
      if (s.getDiscriminator() != null && s.getDiscriminator().getMapping() != null) {
        s.getDiscriminator().getMapping().replaceAll((k, v) -> rewrite.apply(v));
      }
    }

    void content(Content content) {
      if (content != null) {
        content.values().forEach(m -> schema(m.getSchema()));
      }
    }

    void parameters(List<Parameter> parameters) {
      if (parameters != null) {
        for (Parameter p : parameters) {
          schema(p.getSchema());
          content(p.getContent());
        }
      }
    }

    void requestBody(RequestBody body) {
      if (body != null) {
        content(body.getContent());
      }
    }

    void response(ApiResponse response) {
      if (response != null) {
        content(response.getContent());
        if (response.getHeaders() != null) {
          response.getHeaders().values().forEach(this::header);
        }
      }
    }

    void header(Header header) {
      if (header != null) {
        schema(header.getSchema());
        content(header.getContent());
      }
    }
  }
}
