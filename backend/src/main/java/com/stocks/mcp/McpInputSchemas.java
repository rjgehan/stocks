package com.stocks.mcp;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Input-schema post-processing (§20.2). Spring AI generates a tool's {@code inputSchema} from its
 * {@code @McpToolParam} parameters, and the MCP SDK validates every call's arguments against it <b>before</b> the
 * tool runs (SDK 2.0.0 {@code validateToolInputs}, always on), so the schema must accept exactly what
 * {@link McpToolHandler} binds with Boot's mapper:
 * <ol>
 *   <li>{@link BigDecimal} → {@code {"type": ["string", "number"]}}: a decimal arrives as a string ({@code "125.00"},
 *       preferred) or a number, and never passes through {@code double} (§6.3);</li>
 *   <li>enums (also as list items) list the values Boot's mapper reads and writes, so the wire-value enums
 *       ({@code Timeframe} {@code "1Min"}, {@code HistoryRange} {@code "1D"}) are advertised by wire value.</li>
 * </ol>
 * Everything else (strings, integers, booleans, dates as {@code format: date}, instants as {@code date-time}, lists)
 * is Spring AI's schema as generated.
 */
public final class McpInputSchemas {

  private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

  private McpInputSchemas() {}

  /** The post-processed {@code inputSchema} of a tool method. */
  public static Map<String, Object> forMethod(Method method, JsonMapper mapper) {
    ObjectNode root = (ObjectNode) mapper.readTree(McpJsonSchemaGenerator.generateForMethodInput(method));
    JsonNode properties = root.get("properties");
    if (properties instanceof ObjectNode props) {
      Parameter[] parameters = method.getParameters();
      for (int i = 0; i < parameters.length; i++) {
        Parameter p = parameters[i];
        if (!p.isAnnotationPresent(McpToolParam.class) || !(props.get(p.getName()) instanceof ObjectNode prop)) {
          continue;
        }
        process(prop, ResolvableType.forMethodParameter(new MethodParameter(method, i)), mapper);
      }
    }
    return mapper.convertValue(root, MAP);
  }

  private static void process(ObjectNode node, ResolvableType type, JsonMapper mapper) {
    Class<?> raw = type.resolve(Object.class);
    if (raw == BigDecimal.class) {
      String description = text(node, "description");
      node.removeAll();
      ArrayNode types = node.putArray("type");
      types.add("string");
      types.add("number");
      if (description != null) {
        node.put("description", description);
      }
    } else if (raw.isEnum()) {
      ArrayNode values = JsonNodeFactory.instance.arrayNode();
      for (Object constant : raw.getEnumConstants()) {
        values.add(mapper.valueToTree(constant));
      }
      node.remove("const");
      node.put("type", "string");
      node.set("enum", values);
    } else if (Collection.class.isAssignableFrom(raw) && node.get("items") instanceof ObjectNode items) {
      process(items, type.asCollection().getGeneric(0), mapper);
    }
  }

  private static String text(ObjectNode node, String member) {
    JsonNode value = node.get(member);
    return value != null && value.isString() ? value.asString() : null;
  }
}
