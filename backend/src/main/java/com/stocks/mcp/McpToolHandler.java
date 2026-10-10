package com.stocks.mcp;

import com.stocks.common.error.ApiException;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpToolParam;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls one {@code @McpTool} method (§20.1, §20.2). Replaces Spring AI's own method callback, which serializes results
 * with Spring AI's private Jackson defaults (decimals as numbers, no text block): here the result is written once by
 * Boot's {@code JsonMapper} and returned as {@code structuredContent} plus the same JSON as a text block, so both
 * follow §6.3 (decimals as strings, nulls present).
 *
 * <p>Binding: in STATELESS mode a tool takes only {@link McpTransportContext} (the caller, see {@code McpConfig}) and
 * {@code @McpToolParam} parameters, read from {@code arguments} by parameter name (the input-schema property name that
 * Spring AI generates) and converted with Boot's mapper (§6.3: a decimal may arrive as a string or a number). The
 * context handed to the tool carries the tool name in {@code RequestMeta.mcpTool}.
 *
 * <p>Errors: an {@link ApiException} becomes a §20.2 error result ({@link McpErrors}); anything else is logged and
 * answered with {@code INTERNAL_ERROR}.
 */
final class McpToolHandler implements BiFunction<McpTransportContext, CallToolRequest, CallToolResult> {

  private static final Logger log = LoggerFactory.getLogger(McpToolHandler.class);

  private final String toolName;
  private final Object bean;
  private final Method method;
  private final JsonMapper mapper;
  private final List<Binder> binders;

  McpToolHandler(String toolName, Object bean, Method method, JsonMapper mapper) {
    this.toolName = toolName;
    this.bean = bean;
    this.method = method;
    this.mapper = mapper;
    List<Binder> list = new ArrayList<>();
    for (Parameter p : method.getParameters()) {
      list.add(binder(p));
    }
    this.binders = List.copyOf(list);
    method.trySetAccessible();
  }

  private Binder binder(Parameter p) {
    if (p.getType() == McpTransportContext.class) {
      return (ctx, args) -> ctx;
    }
    McpToolParam param = p.getAnnotation(McpToolParam.class);
    if (param == null) {
      throw new IllegalStateException("§20.1: a STATELESS tool takes only McpTransportContext and @McpToolParam "
          + "parameters; " + method + " has " + p);
    }
    if (!p.isNamePresent()) {
      throw new IllegalStateException("Compile with -parameters: the input property names of " + method
          + " are its parameter names");
    }
    String name = p.getName();
    JavaType type = mapper.constructType(p.getParameterizedType());
    boolean required = param.required();
    boolean primitive = p.getType().isPrimitive();
    return (ctx, args) -> {
      Object raw = args == null ? null : args.get(name);
      if (raw == null) {
        if (required || primitive) {
          throw ApiException.validation(name, "Required.");
        }
        return null;
      }
      try {
        return mapper.convertValue(raw, type);
      } catch (JacksonException | IllegalArgumentException e) {
        throw ApiException.validation(name, "Invalid value.");
      }
    };
  }

  @Override
  public CallToolResult apply(McpTransportContext ctx, CallToolRequest request) {
    McpTransportContext callContext = withTool(ctx);
    try {
      Object[] args = new Object[binders.size()];
      for (int i = 0; i < args.length; i++) {
        args[i] = binders.get(i).bind(callContext, request.arguments());
      }
      Object result = method.invoke(bean, args);
      if (result == null) {
        log.error("MCP tool {} returned null", toolName);
        return McpErrors.internal(mapper);
      }
      String json = mapper.writeValueAsString(result);
      Object structured = mapper.readValue(json, Object.class);
      return CallToolResult.builder().structuredContent(structured).addTextContent(json).build();
    } catch (ApiException e) {
      return McpErrors.of(e, mapper);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof ApiException api) {
        return McpErrors.of(api, mapper);
      }
      if (cause instanceof Error error) {
        throw error;
      }
      log.error("MCP tool {} failed (request {})", toolName, requestId(callContext), cause);
      return McpErrors.internal(mapper);
    } catch (IllegalAccessException | RuntimeException e) {
      log.error("MCP tool {} failed (request {})", toolName, requestId(callContext), e);
      return McpErrors.internal(mapper);
    }
  }

  /** The caller's context with {@code RequestMeta.mcpTool} set to this tool. */
  private McpTransportContext withTool(McpTransportContext ctx) {
    PlayerPrincipal principal = ctx != null && ctx.get(CurrentPlayer.PRINCIPAL_KEY) instanceof PlayerPrincipal p
        ? p : null;
    RequestMeta meta = CurrentPlayer.requestMeta(ctx)
        .map(m -> new RequestMeta(m.requestId(), m.channel(), m.ip(), m.userAgent(), m.mcpClient(), toolName))
        .orElse(null);
    return CurrentPlayer.mcpContext(principal, meta);
  }

  private static String requestId(McpTransportContext ctx) {
    return CurrentPlayer.requestMeta(ctx).map(RequestMeta::requestId).orElse(null);
  }

  @FunctionalInterface
  private interface Binder {
    Object bind(McpTransportContext ctx, Map<String, Object> arguments);
  }
}
