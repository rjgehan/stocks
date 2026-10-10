package com.stocks.mcp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;
import org.springframework.core.ResolvableType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Output-schema post-processing (§20.1). Spring AI derives a tool's {@code outputSchema} from its Java return type
 * ({@code generateOutputSchema = true}), but that schema describes Jackson's defaults, not Boot's {@code JsonMapper}
 * (§6.3): a {@link BigDecimal} would be {@code "type": "number"} while {@code structuredContent} carries a string, and
 * nothing would be nullable although null members are always present. The MCP server validates every
 * {@code structuredContent} against the advertised schema (and so do strict clients), so the schema must match the
 * JSON exactly. This class applies the OpenAPI rules of §18 to the generated schema, walking it together with the Java
 * type:
 * <ol>
 *   <li>{@code BigDecimal} → {@code {"type": "string", "format": "decimal"}};</li>
 *   <li>records: every component is {@code required}; every non-primitive component is also nullable
 *       ({@code "type": [T, "null"]}, a {@code $ref} becomes {@code anyOf: [{$ref}, {"type": "null"}]}, a nullable enum
 *       lists {@code null});</li>
 *   <li>enums publish exactly what Boot's mapper writes (wire-value enums such as {@code "1D"} included);</li>
 *   <li>polymorphic types ({@code @JsonSubTypes}) are not modelled yet and are published as an open object
 *       (Phase 1 TODO).</li>
 * </ol>
 * The result never contains {@code "type": "number"}: the build fails fast (at boot) if one would remain.
 */
public final class McpOutputSchemas {

  private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};
  private static final String DEFS_PREFIX = "#/$defs/";

  private McpOutputSchemas() {}

  /** The post-processed {@code outputSchema} of a tool whose method returns {@code returnType}. */
  public static Map<String, Object> forReturnType(Type returnType, JsonMapper mapper) {
    ObjectNode root = (ObjectNode) mapper.readTree(McpJsonSchemaGenerator.generateFromType(returnType));
    new Walker(root, mapper).process(root, ResolvableType.forType(returnType));
    List<String> numbers = new ArrayList<>();
    findNumbers(root, "$", numbers);
    if (!numbers.isEmpty()) {
      throw new IllegalStateException("§20.2: the MCP output schema of " + returnType.getTypeName()
          + " has \"type\": \"number\" at " + numbers + " (money is a decimal string; integers are integer)");
    }
    return mapper.convertValue(root, MAP);
  }

  /** Paths of every {@code "type": "number"} (alone or in a type array) under {@code node}. */
  static void findNumbers(JsonNode node, String path, List<String> found) {
    if (node.isObject()) {
      JsonNode type = node.get("type");
      if (type != null && (type.isString() && "number".equals(type.asString())
          || type.isArray() && type.valueStream().anyMatch(t -> "number".equals(t.asString())))) {
        found.add(path);
      }
      for (Map.Entry<String, JsonNode> e : node.properties()) {
        findNumbers(e.getValue(), path + "." + e.getKey(), found);
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        findNumbers(node.get(i), path + "[" + i + "]", found);
      }
    }
  }

  /** Walks one generated schema; {@code $defs} entries are processed once, at their first use. */
  private static final class Walker {

    private final ObjectNode root;
    private final JsonMapper mapper;
    private final Set<String> processedDefs = new HashSet<>();

    Walker(ObjectNode root, JsonMapper mapper) {
      this.root = root;
      this.mapper = mapper;
    }

    void process(ObjectNode node, ResolvableType type) {
      JsonNode ref = node.get("$ref");
      if (ref != null && ref.isString()) {
        String name = ref.asString();
        if (!name.startsWith(DEFS_PREFIX)) {
          throw new IllegalStateException("Unsupported $ref " + name + " in the schema of " + type);
        }
        String key = name.substring(DEFS_PREFIX.length());
        if (processedDefs.add(key)) {
          JsonNode def = root.path("$defs").get(key);
          if (!(def instanceof ObjectNode defNode)) {
            throw new IllegalStateException("Dangling $ref " + name + " in the schema of " + type);
          }
          process(defNode, type);
        }
        return;
      }
      Class<?> raw = type.toClass();
      if (raw == BigDecimal.class) {
        decimal(node);
      } else if (raw.isEnum()) {
        enumeration(node, raw);
      } else if (raw.isRecord()) {
        record(node, type, raw);
      } else if (raw.isArray()) {
        child(node, "items", type.getComponentType());
      } else if (Collection.class.isAssignableFrom(raw)) {
        child(node, "items", type.asCollection().getGeneric(0));
      } else if (Map.class.isAssignableFrom(raw)) {
        child(node, "additionalProperties", type.asMap().getGeneric(1));
      } else if (raw.isAnnotationPresent(JsonSubTypes.class)) {
        // TODO(WS5, Phase 1): model @JsonTypeInfo/@JsonSubTypes (e.g. ActivityDetailDto) as oneOf with a "type"
        // const per subtype. Until then the member is an open object, which every subtype satisfies.
        String description = text(node, "description");
        node.removeAll();
        node.put("type", "object");
        if (description != null) {
          node.put("description", description);
        }
      }
      // String, Instant, LocalDate, boolean, int/long and their boxes: the generated schema is already right.
    }

    private void child(ObjectNode node, String member, ResolvableType type) {
      if (node.get(member) instanceof ObjectNode child && type.resolve() != null) {
        process(child, type);
      }
    }

    private static void decimal(ObjectNode node) {
      String description = text(node, "description");
      node.removeAll();
      node.put("type", "string");
      node.put("format", "decimal");
      if (description != null) {
        node.put("description", description);
      }
    }

    private void enumeration(ObjectNode node, Class<?> raw) {
      ArrayNode values = JsonNodeFactory.instance.arrayNode();
      for (Object constant : raw.getEnumConstants()) {
        values.add(mapper.valueToTree(constant));   // what Boot writes, incl. @JsonValue wire values
      }
      node.remove("const");   // victools writes a single-constant enum as "const"; "enum" says the same
      node.put("type", "string");
      node.set("enum", values);
    }

    private void record(ObjectNode node, ResolvableType type, Class<?> raw) {
      ObjectNode properties = node.get("properties") instanceof ObjectNode p ? p : node.putObject("properties");
      ArrayNode required = JsonNodeFactory.instance.arrayNode();
      for (RecordComponent component : raw.getRecordComponents()) {
        if (annotation(raw, component, JsonIgnore.class) != null) {
          continue;
        }
        String name = jsonName(raw, component);
        if (!(properties.get(name) instanceof ObjectNode property)) {
          throw new IllegalStateException("The generated schema of " + raw.getName() + " has no property '" + name
              + "'");
        }
        process(property, componentType(raw, component, type));
        if (!component.getType().isPrimitive()) {
          properties.set(name, nullable(property));
        }
        required.add(name);
      }
      node.set("required", required);
    }

    private static ResolvableType componentType(Class<?> raw, RecordComponent component, ResolvableType owner) {
      try {
        Field field = raw.getDeclaredField(component.getName());
        return ResolvableType.forField(field, owner);
      } catch (NoSuchFieldException e) {
        return ResolvableType.forMethodReturnType(component.getAccessor(), raw);
      }
    }

    private static String jsonName(Class<?> raw, RecordComponent component) {
      JsonProperty property = annotation(raw, component, JsonProperty.class);
      return property != null && !property.value().isEmpty() ? property.value() : component.getName();
    }

    /** Jackson annotations on a record component land on its field, accessor or constructor parameter. */
    private static <A extends java.lang.annotation.Annotation> A annotation(Class<?> raw, RecordComponent component,
                                                                             Class<A> type) {
      A onAccessor = component.getAccessor().getAnnotation(type);
      if (onAccessor != null) {
        return onAccessor;
      }
      try {
        return raw.getDeclaredField(component.getName()).getAnnotation(type);
      } catch (NoSuchFieldException e) {
        return null;
      }
    }

    /** {@code node}, or a copy of it that also accepts {@code null}. */
    private static ObjectNode nullable(ObjectNode node) {
      if (node.has("$ref")) {
        ObjectNode wrapper = JsonNodeFactory.instance.objectNode();
        ArrayNode anyOf = wrapper.putArray("anyOf");
        anyOf.add(node);
        anyOf.addObject().put("type", "null");
        return wrapper;
      }
      JsonNode type = node.get("type");
      if (type != null && type.isString()) {
        if (!"null".equals(type.asString())) {
          ArrayNode types = JsonNodeFactory.instance.arrayNode();
          types.add(type.asString());
          types.add("null");
          node.set("type", types);
        }
      } else if (type != null && type.isArray()) {
        ArrayNode types = (ArrayNode) type;
        if (types.valueStream().noneMatch(t -> "null".equals(t.asString()))) {
          types.add("null");
        }
      } else {
        for (String combinator : List.of("anyOf", "oneOf")) {
          if (node.get(combinator) instanceof ArrayNode options
              && options.valueStream().noneMatch(o -> "null".equals(o.path("type").asString(null)))) {
            options.addObject().put("type", "null");
          }
        }
      }
      if (node.get("enum") instanceof ArrayNode values && values.valueStream().noneMatch(JsonNode::isNull)) {
        values.addNull();
      }
      return node;
    }

    private static String text(ObjectNode node, String member) {
      JsonNode value = node.get(member);
      return value != null && value.isString() ? value.asString() : null;
    }
  }
}
