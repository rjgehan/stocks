package com.stocks.mcp;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tool errors (§20.2): {@code isError = true} with one text block holding
 * {@code {code, message, hint, fields, retry_after_s}}, using the same {@link ErrorCode} values as REST (§7.2). Every
 * member is always present. {@code INTERNAL_ERROR} never carries details (§6.5).
 */
public final class McpErrors {

  private McpErrors() {}

  /** The error result for an {@link ApiException} thrown by a tool or by argument binding. */
  public static CallToolResult of(ApiException e, JsonMapper mapper) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", e.code().name());
    body.put("message", e.code() == ErrorCode.INTERNAL_ERROR ? ErrorCode.INTERNAL_ERROR.title() : e.detail());
    body.put("hint", e.hint());
    body.put("fields", e.fields());
    body.put("retry_after_s", e.retryAfterS());
    return CallToolResult.builder().isError(true).addTextContent(mapper.writeValueAsString(body)).build();
  }

  /** {@code INTERNAL_ERROR} for an unexpected failure; the cause is logged by the caller, never sent. */
  public static CallToolResult internal(JsonMapper mapper) {
    return of(ApiException.of(ErrorCode.INTERNAL_ERROR, null), mapper);
  }
}
