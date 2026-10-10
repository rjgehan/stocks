package com.stocks.mcp.tools;

/**
 * Marker for the beans that hold {@code @McpTool} methods (§20.1). {@code McpConfig} builds the tool list from every
 * {@code McpToolSet} bean (Spring AI's annotation scanner is switched off), in the fixed order of §20.3.
 *
 * <p>Tool rules: a tool is a public {@code @McpTool} method with {@code generateOutputSchema = true} and all four
 * hints set; it takes {@code McpTransportContext ctx} first, then only {@code @McpToolParam} parameters (snake_case
 * names, §20.2); it returns a §18.3 DTO (or a record of them), built by the same services and mappers as REST; it
 * reads the caller with {@code CurrentPlayer.require(ctx)}.
 */
public interface McpToolSet {}
