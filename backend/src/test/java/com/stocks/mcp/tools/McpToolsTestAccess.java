package com.stocks.mcp.tools;

import java.time.LocalDate;

/** Test access to the package-private helpers of the MCP tools. */
public final class McpToolsTestAccess {

  private McpToolsTestAccess() {}

  public static String holiday(LocalDate date) {
    return MarketDtos.holidayName(date);
  }
}
