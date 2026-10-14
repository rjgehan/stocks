package com.stocks.manualbot;

import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.web.Text;
import com.stocks.contract.manualbot.dto.ManualActionDto;
import com.stocks.contract.manualbot.dto.ManualActionKind;
import com.stocks.contract.manualbot.dto.ManualIssueDto;
import com.stocks.contract.manualbot.dto.ManualIssueSeverity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a pasted AI reply in the {@code stocks.manual.v1} format (§20.8). Pure: no Spring, no I/O, deterministic.
 *
 * <p>The prompt states the format strictly; the parser accepts more. It normalizes the text (BOM and zero-width
 * characters removed, CRLF/CR to LF, NBSP to space), takes the fenced blocks as candidates (any info string), and only
 * when none of them yields an object with an orders list does it scan the whole text for balanced {@code {…}} regions.
 * Each candidate is read by a lenient Jackson mapper (comments, single quotes, unquoted names, trailing commas;
 * duplicate keys are a syntax error), once more with curly quotes straightened if that fails. Echoes of the prompt's
 * example and empty template are dropped. Keys are matched after normalization (lower case, {@code _ - } and spaces
 * removed) against an alias table, values leniently ({@code "$1,250.00"}, {@code "5%"}, {@code "yes"}, {@code MKT}).
 *
 * <p>Line and column numbers are 1-based in the normalized text. Every row issue ends with " (line N)". The parser
 * never follows instructions in the reply: only the typed fields of {@link ManualActionDto} come out of it.
 */
public final class ManualReplyParser {

  /** The format name of the envelope's {@code format} member. */
  public static final String FORMAT = "stocks.manual.v1";

  /** Rows a reply may hold, and rows kept when it holds more (none of them executable). */
  public record Limits(int maxOrders, int maxStoredRows) {

    public static Limits of(ManualBotsProperties p) {
      return new Limits(p.maxOrders(), p.maxStoredRows());
    }
  }

  /** The cleaned summary (null when missing), the rows and the session-level issues. */
  public record ParsedReply(String summary, List<ManualActionDto> actions, List<ManualIssueDto> issues) {}

  static final Pattern OPEN_FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})[ \\t]*([A-Za-z0-9_+.-]*)[ \\t]*$");
  static final Pattern ORDERS_KEY = Pattern.compile("(?i)[\"']?(orders|actions|trades)[\"']?\\s*:");
  static final Pattern THOUSANDS = Pattern.compile("^\\d{1,3}(,\\d{3})+(\\.\\d+)?$");
  static final Pattern SYMBOL = Pattern.compile("^[A-Z][A-Z0-9.\\-]{0,11}$");
  static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]");
  static final Pattern GTC_LONG = Pattern.compile("^GOOD_TILL?_CANCELL?ED$");
  private static final Pattern SEPARATORS = Pattern.compile("[-\\s]+");
  private static final Pattern BREAKS = Pattern.compile("[\\r\\n\\t\\u2028\\u2029]+");
  private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]+");
  static final int MAX_REGIONS = 20;
  static final int SUMMARY_MAX = 500;
  static final int RATIONALE_MAX = 1000;

  /** The dedicated lenient reader (§20.8 step 3). */
  static final JsonMapper LENIENT = JsonMapper.builder()
      .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_YAML_COMMENTS,
          JsonReadFeature.ALLOW_SINGLE_QUOTES, JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES,
          JsonReadFeature.ALLOW_TRAILING_COMMA, JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS,
          JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS,
          JsonReadFeature.ALLOW_TRAILING_DECIMAL_POINT_FOR_NUMBERS, JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .build();

  private static final JsonNode EXAMPLE = LENIENT.readTree(ManualPromptBuilder.EXAMPLE_JSON);

  /** Envelope members: normalized key → canonical name. */
  private static final Map<String, String> ENVELOPE = Map.of(
      "format", "format", "session", "session", "sessionid", "session", "summary", "summary", "journal", "summary",
      "journalnote", "summary", "orders", "orders", "actions", "orders", "trades", "orders");

  /** Row members: normalized key → canonical field (§20.8 step 6 alias table). */
  private static final Map<String, String> ROW = new LinkedHashMap<>();

  static {
    alias("action", "action", "side");
    alias("symbol", "symbol", "ticker");
    alias("type", "type", "ordertype");
    alias("qty", "qty", "quantity", "shares");
    alias("notional", "notional", "amount", "dollars", "usd", "notionalusd");
    alias("limitPrice", "limitprice", "limit", "lmtprice");
    alias("stopPrice", "stopprice", "stop");
    alias("trailPercent", "trailpercent", "trailpct");
    alias("trailAmount", "trailamount");
    alias("timeInForce", "timeinforce", "tif", "duration");
    alias("extendedHours", "extendedhours", "extended", "exthours", "outsiderth");
    alias("rationale", "rationale", "reason", "why", "note");
    alias("acknowledge", "acknowledge", "ack");
    alias("acknowledgeGfv", "acknowledgegfv");
    alias("orderId", "orderid", "id");
    alias("price", "price");
    alias("clientOrderId", "clientorderid");
  }

  private static void alias(String field, String... keys) {
    for (String k : keys) {
      ROW.put(k, field);
    }
  }

  /** How a field is named in messages. */
  private static final Map<String, String> LABEL = Map.ofEntries(Map.entry("action", "action"),
      Map.entry("symbol", "symbol"), Map.entry("type", "type"), Map.entry("qty", "qty"),
      Map.entry("notional", "notional"), Map.entry("limitPrice", "limit price"),
      Map.entry("stopPrice", "stop price"), Map.entry("trailPercent", "trail percent"),
      Map.entry("trailAmount", "trail amount"), Map.entry("timeInForce", "time in force"),
      Map.entry("extendedHours", "extended hours"), Map.entry("rationale", "rationale"),
      Map.entry("acknowledge", "acknowledge"), Map.entry("orderId", "order id"));

  /** A usable decimal example per field, for messages. */
  private static final Map<String, String> EXAMPLE_VALUE = Map.of("qty", "10", "notional", "250.00", "limitPrice",
      "412.50", "stopPrice", "395.00", "trailPercent", "5", "trailAmount", "2.50");

  private ManualReplyParser() {}

  // ------------------------------------------------------------------------------------------- entry point

  /** Parses {@code reply} (already redacted) as the answer to {@code sessionId}. Never throws for bad input. */
  public static ParsedReply parse(String reply, String sessionId, Limits limits) {
    String text = normalize(reply == null ? "" : reply);
    List<ManualIssueDto> issues = new ArrayList<>();
    String[] lines = text.split("\n", -1);

    List<Candidate> fenced = fencedBlocks(lines, issues);
    List<Parsed> parsed = new ArrayList<>(fenced.stream().map(ManualReplyParser::read).toList());
    Selection selection = select(parsed, issues);
    if (selection.usable().isEmpty()) {
      List<Parsed> regions = regions(text, fenced).stream().map(ManualReplyParser::read).toList();
      parsed.addAll(regions);
      selection = select(parsed, issues);
    }

    List<Parsed> usable = selection.usable();
    if (usable.size() > 1) {
      List<String> at = usable.stream().map(p -> String.valueOf(p.candidate().line())).toList();
      issues.add(error("MULTIPLE_JSON_BLOCKS", null, usable.get(1).candidate().line(), null,
          "Found " + usable.size() + " JSON blocks with orders (lines " + joinAnd(at) + "); keep exactly one.",
          "Ask the AI for one JSON block only, or delete the extra block before checking."));
      return new ParsedReply(null, List.of(), issues);
    }
    if (usable.isEmpty()) {
      if (selection.failures().isEmpty()) {
        issues.add(error("NO_JSON_BLOCK", null, null, null, "Found no JSON block with an \"orders\" list. The reply "
                + "must contain one ```json block in the stocks.manual.v1 format.",
            "Ask the AI to answer in the format at the end of the prompt."));
      } else {
        for (Parsed f : selection.failures()) {
          issues.add(syntaxError(f));
        }
      }
      return new ParsedReply(null, List.of(), issues);
    }
    for (Parsed f : selection.failures()) {
      issues.add(warning("BLOCK_IGNORED", null, f.candidate().line(), null,
          "Ignored an unreadable block at line " + f.candidate().line() + ".", null));
    }
    Parsed chosen = usable.get(0);
    if (chosen.curlyFixed()) {
      issues.add(warning("CURLY_QUOTES_FIXED", null, chosen.candidate().line(), null,
          "Replaced curly quotes in the JSON block with straight ones.", null));
    }
    return envelope(chosen, sessionId, limits, issues);
  }

  // ------------------------------------------------------------------------------------------- 1. normalize

  /** BOM and zero-width characters removed, CRLF and CR to LF, NBSP to space. */
  static String normalize(String s) {
    String out = ZERO_WIDTH.matcher(s).replaceAll("");
    out = out.replace("\r\n", "\n").replace('\r', '\n');
    return out.replace(' ', ' ');
  }

  // ------------------------------------------------------------------------------------------- 2. candidates

  /** A piece of text that may hold the JSON object: its content and where it starts (1-based). */
  record Candidate(String text, int line, int column) {}

  /** A candidate after reading: the tree (or the failure), and whether curly quotes had to be straightened. */
  record Parsed(Candidate candidate, String text, JsonNode node, JacksonException error, boolean curlyFixed) {}

  /** (a) Fenced blocks, any info string; an unclosed fence runs to the end of the text. */
  static List<Candidate> fencedBlocks(String[] lines, List<ManualIssueDto> issues) {
    List<Candidate> out = new ArrayList<>();
    int i = 0;
    while (i < lines.length) {
      Matcher open = OPEN_FENCE.matcher(lines[i]);
      if (!open.matches()) {
        i++;
        continue;
      }
      char fence = open.group(1).charAt(0);
      int length = open.group(1).length();
      int start = i + 1;
      int end = start;
      while (end < lines.length && !closes(lines[end], fence, length)) {
        end++;
      }
      if (end >= lines.length) {
        issues.add(warning("FENCE_UNCLOSED", null, i + 1, null,
            "The code block opened at line " + (i + 1) + " is never closed; read to the end of the reply.", null));
      }
      String content = String.join("\n", java.util.Arrays.asList(lines).subList(start, Math.min(end, lines.length)));
      if (!content.isBlank()) {
        out.add(new Candidate(content, start + 1, 1));
      }
      i = end + 1;
    }
    return out;
  }

  /** A closing fence: up to 3 spaces, at least {@code length} of the same character, then only blanks. */
  private static boolean closes(String line, char fence, int length) {
    int i = 0;
    while (i < line.length() && i < 3 && line.charAt(i) == ' ') {
      i++;
    }
    int run = 0;
    while (i < line.length() && line.charAt(i) == fence) {
      i++;
      run++;
    }
    return run >= length && line.substring(i).isBlank();
  }

  /**
   * (b) Balanced top-level {@code {…}} regions of the whole text that mention an orders key, at most
   * {@link #MAX_REGIONS}. Quotes are tracked only inside a region ({@code "} always; {@code '} only after
   * {@code { [ , :}), with backslash escapes. A region equal to a fenced block's content is not read twice.
   */
  static List<Candidate> regions(String text, List<Candidate> fenced) {
    List<String> fencedTexts = fenced.stream().map(c -> c.text().strip()).toList();
    List<Candidate> out = new ArrayList<>();
    int i = 0;
    int n = text.length();
    while (i < n && out.size() < MAX_REGIONS) {
      if (text.charAt(i) != '{') {
        i++;
        continue;
      }
      int start = i;
      int end = regionEnd(text, start);
      String region = text.substring(start, end);
      if (ORDERS_KEY.matcher(region).find() && !fencedTexts.contains(region.strip())) {
        int line = 1;
        int lineStart = 0;
        for (int k = 0; k < start; k++) {
          if (text.charAt(k) == '\n') {
            line++;
            lineStart = k + 1;
          }
        }
        out.add(new Candidate(region, line, start - lineStart + 1));
      }
      i = end;
    }
    return out;
  }

  /** The index after the {@code }} that closes the region opened at {@code start}, or the text's end. */
  private static int regionEnd(String text, int start) {
    int depth = 0;
    char quote = 0;
    char previous = 0;          // the previous non-space character outside strings
    for (int i = start; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quote != 0) {
        if (c == '\\') {
          i++;
        } else if (c == quote) {
          quote = 0;
          previous = c;
        }
        continue;
      }
      if (c == '"' || (c == '\'' && (previous == '{' || previous == '[' || previous == ',' || previous == ':'))) {
        quote = c;
        continue;
      }
      if (c == '{' || c == '[') {
        depth++;
      } else if (c == '}' || c == ']') {
        depth--;
        if (depth == 0) {
          return i + 1;
        }
      }
      if (!Character.isWhitespace(c)) {
        previous = c;
      }
    }
    return text.length();
  }

  // ------------------------------------------------------------------------------------------- 3. read

  static Parsed read(Candidate c) {
    try {
      return new Parsed(c, c.text(), LENIENT.readTree(c.text()), null, false);
    } catch (JacksonException e) {
      if (hasCurlyQuotes(c.text())) {
        String fixed = c.text().replace('“', '"').replace('”', '"').replace('‘', '\'')
            .replace('’', '\'');
        try {
          return new Parsed(c, fixed, LENIENT.readTree(fixed), null, true);
        } catch (JacksonException ignored) {
          // report the first failure: it points at the original text
        }
      }
      return new Parsed(c, c.text(), null, e, false);
    }
  }

  private static boolean hasCurlyQuotes(String s) {
    return s.indexOf('“') >= 0 || s.indexOf('”') >= 0 || s.indexOf('‘') >= 0
        || s.indexOf('’') >= 0;
  }

  // ------------------------------------------------------------------------------------------- 4. select

  /** Distinct usable objects (first of identical ones), and the candidates that failed to parse. */
  record Selection(List<Parsed> usable, List<Parsed> failures) {}

  private static Selection select(List<Parsed> parsed, List<ManualIssueDto> issues) {
    List<Parsed> usable = new ArrayList<>();
    List<Parsed> failures = new ArrayList<>();
    for (Parsed p : parsed) {
      if (p.error() != null) {
        failures.add(p);
        continue;
      }
      JsonNode node = p.node();
      if (node == null || node.isMissingNode()) {
        continue;
      }
      if (node.equals(EXAMPLE)) {
        addOnce(issues, warning("EXAMPLE_IGNORED", null, p.candidate().line(), null, "Ignored the example block "
            + "from the prompt at line " + p.candidate().line() + ".", null));
        continue;
      }
      if (isTemplateEcho(node)) {
        addOnce(issues, warning("TEMPLATE_IGNORED", null, p.candidate().line(), null, "Ignored the empty format "
            + "block from the prompt at line " + p.candidate().line() + ".", null));
        continue;
      }
      if (!node.isArray() && !(node.isObject() && ordersKey(node) != null)) {
        continue;             // JSON without orders: not a reply block
      }
      if (usable.stream().noneMatch(u -> u.node().equals(node))) {
        usable.add(p);
      }
    }
    return new Selection(usable, failures);
  }

  private static void addOnce(List<ManualIssueDto> issues, ManualIssueDto issue) {
    if (!issues.contains(issue)) {
      issues.add(issue);
    }
  }

  private static boolean isTemplateEcho(JsonNode node) {
    if (!node.isObject()) {
      return false;
    }
    String ordersKey = ordersKey(node);
    if (ordersKey == null || !node.get(ordersKey).isArray() || !node.get(ordersKey).isEmpty()) {
      return false;
    }
    for (Map.Entry<String, JsonNode> e : node.properties()) {
      if ("summary".equals(ENVELOPE.get(norm(e.getKey()))) && e.getValue().isString()
          && ManualPromptBuilder.TEMPLATE_SUMMARY.equals(e.getValue().stringValue().strip())) {
        return true;
      }
    }
    return false;
  }

  /** The first top-level key that normalizes to an orders alias, or null. */
  private static String ordersKey(JsonNode object) {
    for (String k : object.propertyNames()) {
      if ("orders".equals(ENVELOPE.get(norm(k)))) {
        return k;
      }
    }
    return null;
  }

  private static ManualIssueDto syntaxError(Parsed f) {
    JacksonException e = f.error();
    TokenStreamLocation loc = e.getLocation();
    Integer line = null;
    Integer column = null;
    if (loc != null && loc.getLineNr() > 0) {
      line = f.candidate().line() + loc.getLineNr() - 1;
      column = loc.getColumnNr() <= 0 ? null
          : loc.getLineNr() == 1 ? f.candidate().column() + loc.getColumnNr() - 1 : loc.getColumnNr();
    }
    String original = e.getOriginalMessage() == null ? "Unreadable JSON" : e.getOriginalMessage();
    original = BREAKS.matcher(original).replaceAll(" ").strip();
    String where = line == null ? "" : "Line " + line + (column == null ? "" : ", column " + column) + ": ";
    return error("JSON_SYNTAX", null, line, column, where + Text.clip(original, 300),
        "Fix the JSON there, or ask the AI to resend the block.");
  }

  // ------------------------------------------------------------------------------------------- 5. envelope

  private static ParsedReply envelope(Parsed chosen, String sessionId, Limits limits, List<ManualIssueDto> issues) {
    JsonNode root = chosen.node();
    int blockLine = chosen.candidate().line();
    JsonNode orders;
    String ordersKey = null;
    String summary = null;
    if (root.isArray()) {
      orders = root;
      issues.add(warning("NO_ENVELOPE", null, blockLine, null, "The block is a bare list of orders: no session "
          + "and no summary, so no journal note will be posted.", null));
    } else {
      Map<String, List<String>> keys = new LinkedHashMap<>();
      for (String k : root.propertyNames()) {
        String canonical = ENVELOPE.get(norm(k));
        if (canonical == null) {
          issues.add(warning("FIELD_IGNORED", k, blockLine, null, "Ignored the unknown field \"" + Text.clip(k, 40)
              + "\".", null));
        } else {
          keys.computeIfAbsent(canonical, x -> new ArrayList<>()).add(k);
        }
      }
      boolean twice = false;
      for (Map.Entry<String, List<String>> e : keys.entrySet()) {
        if (e.getValue().size() > 1) {
          twice = true;
          issues.add(error("FIELD_INVALID", e.getValue().get(1), blockLine, null, e.getKey() + " given twice ("
              + String.join(", ", e.getValue()) + ")", null));
        }
      }
      String format = first(root, keys.get("format"));
      if (format != null && !FORMAT.equals(format.strip())) {
        issues.add(warning("FORMAT_UNKNOWN", keys.get("format").get(0), blockLine, null, "Unknown format \""
            + Text.clip(format, 40) + "\"; read as " + FORMAT + ".", null));
      }
      session(first(root, keys.get("session")), keys.get("session"), sessionId, blockLine, issues);
      summary = summary(first(root, keys.get("summary")), keys.get("summary"), blockLine, issues);
      ordersKey = keys.get("orders").get(0);
      orders = root.get(ordersKey);
      if (twice) {
        return new ParsedReply(summary, List.of(), issues);
      }
      if (!orders.isArray()) {
        issues.add(error("FIELD_INVALID", ordersKey, blockLine, null, ordersKey + " must be a list of orders",
            "Use \"orders\": [ … ], or \"orders\": [] for no trade."));
        return new ParsedReply(summary, List.of(), issues);
      }
    }
    boolean tooMany = orders.size() > limits.maxOrders();
    if (tooMany) {
      issues.add(error("TOO_MANY_ORDERS", ordersKey, blockLine, null, "The reply has " + orders.size()
          + " orders; at most " + limits.maxOrders() + ".", "Ask the AI for at most " + limits.maxOrders()
          + " orders."));
    }
    List<Integer> rowLines = rowLines(chosen.text(), ordersKey);
    List<ManualActionDto> actions = new ArrayList<>();
    List<JsonNode> seen = new ArrayList<>();
    int index = 0;
    for (JsonNode element : orders.values()) {
      if (index >= limits.maxStoredRows()) {
        break;
      }
      Integer line = index < rowLines.size() ? blockLine + rowLines.get(index) - 1 : blockLine;
      index++;
      Row row = new Row(index, line);
      if (!element.isObject()) {
        row.error("FIELD_INVALID", null, "order #" + index + " is not an object", null);
      } else {
        row.parse(element);
        if (seen.contains(element)) {
          row.warn("DUPLICATE_ROW", null, "order #" + index + " repeats an earlier order", null);
        }
        seen.add(element);
      }
      actions.add(row.toDto(!tooMany));
    }
    return new ParsedReply(summary, actions, issues);
  }

  private static String first(JsonNode root, List<String> keys) {
    if (keys == null || keys.isEmpty()) {
      return null;
    }
    JsonNode v = root.get(keys.get(0));
    return v == null || v.isNull() ? null : v.isValueNode() ? v.asString() : v.toString();
  }

  private static void session(String value, List<String> keys, String sessionId, int line,
                              List<ManualIssueDto> issues) {
    String field = keys == null ? "session" : keys.get(0);
    String s = value == null ? null : value.strip();
    if (s == null || s.isEmpty()) {
      issues.add(warning("SESSION_MISSING", field, line, null, "The reply names no session; it is taken as the "
          + "answer to " + sessionId + ".", null));
    } else if (s.equals(sessionId)) {
      return;
    } else if (s.startsWith("mbs_")) {
      issues.add(error("SESSION_MISMATCH", field, line, null, "This reply is for session " + Text.clip(s, 40)
          + ", not " + sessionId + ". Paste the reply to the latest prompt, or make a new prompt.", null));
    } else {
      issues.add(warning("SESSION_UNEXPECTED", field, line, null, "The reply names session " + Text.clip(s, 40)
          + "; it is taken as the answer to " + sessionId + ".", null));
    }
  }

  private static String summary(String value, List<String> keys, int line, List<ManualIssueDto> issues) {
    String field = keys == null ? "summary" : keys.get(0);
    String s = clean(value);
    if (s == null) {
      issues.add(warning("SUMMARY_MISSING", field, line, null, "The reply has no summary; no journal note will be "
          + "posted.", null));
      return null;
    }
    if (s.length() > SUMMARY_MAX) {
      issues.add(warning("SUMMARY_CLIPPED", field, line, null, "The summary has " + s.length() + " characters; "
          + "only the first " + SUMMARY_MAX + " are kept.", null));
      int end = SUMMARY_MAX;
      if (Character.isHighSurrogate(s.charAt(end - 1))) {
        end--;
      }
      return s.substring(0, end).strip();
    }
    return s;
  }

  /**
   * The journal's cleaning (§20.7): line breaks and tabs → spaces, control and format characters removed, whitespace
   * runs collapsed, trimmed; {@code null} when nothing is left.
   */
  static String clean(String text) {
    if (text == null) {
      return null;
    }
    String flat = BREAKS.matcher(text).replaceAll(" ");
    return Text.trimToNull(SPACES.matcher(Text.stripControl(flat)).replaceAll(" "));
  }

  /**
   * The 1-based START_OBJECT line (relative to the block) of each element of the orders list: a streaming pass over
   * the chosen block ({@code ordersKey} null = the block is the list itself).
   */
  static List<Integer> rowLines(String text, String ordersKey) {
    List<Integer> out = new ArrayList<>();
    try (JsonParser p = LENIENT.createParser(text)) {
      JsonToken root = p.nextToken();
      if (ordersKey == null) {
        if (root == JsonToken.START_ARRAY) {
          elements(p, out);
        }
        return out;
      }
      if (root != JsonToken.START_OBJECT) {
        return out;
      }
      while (p.nextToken() == JsonToken.PROPERTY_NAME) {
        String name = p.currentName();
        JsonToken value = p.nextToken();
        if (name.equals(ordersKey) && value == JsonToken.START_ARRAY) {
          elements(p, out);
          return out;
        }
        p.skipChildren();
      }
    } catch (JacksonException e) {
      // the tree read already succeeded; a failure here only loses line numbers
    }
    return out;
  }

  private static void elements(JsonParser p, List<Integer> out) {
    JsonToken t;
    while ((t = p.nextToken()) != null && t != JsonToken.END_ARRAY) {
      out.add(p.currentTokenLocation().getLineNr());
      p.skipChildren();
    }
  }

  // ------------------------------------------------------------------------------------------- 6. rows

  /** One row being read: its fields and issues. */
  private static final class Row {

    final int index;
    final Integer line;
    final List<ManualIssueDto> issues = new ArrayList<>();
    ManualActionKind action;
    String symbol;
    OrderType type;
    BigDecimal qty;
    BigDecimal notional;
    BigDecimal limitPrice;
    BigDecimal stopPrice;
    BigDecimal trailAmount;
    BigDecimal trailPercent;
    TimeInForce timeInForce;
    boolean extendedHours;
    List<String> acknowledge = List.of();
    String orderId;
    String rationale;

    Row(int index, Integer line) {
      this.index = index;
      this.line = line;
    }

    void parse(JsonNode object) {
      Map<String, List<Map.Entry<String, JsonNode>>> fields = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> e : object.properties()) {
        String field = ROW.get(norm(e.getKey()));
        if (field == null) {
          warn("FIELD_IGNORED", e.getKey(), "Ignored the unknown field \"" + Text.clip(e.getKey(), 40) + "\"", null);
        } else if ("clientOrderId".equals(field)) {
          warn("FIELD_IGNORED", e.getKey(), Text.clip(e.getKey(), 40) + " is ignored: the app sets its own ids",
              null);
        } else if (!absent(e.getValue())) {
          fields.computeIfAbsent(field, k -> new ArrayList<>()).add(e);
        }
      }
      Map.Entry<String, JsonNode> actionEntry = single(fields, "action");
      if (actionEntry == null) {
        if (!fields.containsKey("action")) {
          error("FIELD_MISSING", "action", "action is required (BUY, SELL or CANCEL)", null);
        }
      } else {
        action = action(actionEntry);
      }
      if (action == ManualActionKind.CANCEL) {
        Map.Entry<String, JsonNode> id = single(fields, "orderId");
        if (id == null) {
          if (!fields.containsKey("orderId")) {
            error("FIELD_MISSING", "order_id", "order_id is required for CANCEL", "Use an id from \"Your open "
                + "orders\".");
          }
        } else {
          orderId = text(id.getValue()).strip();
        }
        rationale(fields, false);
        return;
      }
      if (fields.containsKey("orderId")) {
        for (Map.Entry<String, JsonNode> e : fields.get("orderId")) {
          warn("FIELD_IGNORED", e.getKey(), Text.clip(e.getKey(), 40) + " is ignored on a " + (action == null
              ? "trade" : action.name()), null);
        }
      }
      Map.Entry<String, JsonNode> symbolEntry = single(fields, "symbol");
      if (symbolEntry == null) {
        if (!fields.containsKey("symbol")) {
          error("FIELD_MISSING", "symbol", "symbol is required", null);
        }
      } else {
        symbol = symbol(symbolEntry);
      }
      Map.Entry<String, JsonNode> typeEntry = single(fields, "type");
      boolean typeGiven = fields.containsKey("type");
      if (typeEntry != null) {
        type = type(typeEntry);
      }
      if (!typeGiven) {
        boolean limitGiven = fields.containsKey("limitPrice") || fields.containsKey("price");
        type = limitGiven ? OrderType.LIMIT : OrderType.MARKET;
        warn("TYPE_ASSUMED", "type", "type is missing; read as " + type.name(), "Add \"type\": \"" + type.name()
            + "\".");
      }
      if (fields.containsKey("price")) {
        String target = type == OrderType.LIMIT || type == OrderType.STOP_LIMIT ? "limitPrice"
            : type == OrderType.STOP ? "stopPrice" : null;
        if (target == null) {
          for (Map.Entry<String, JsonNode> e : fields.remove("price")) {
            warn("FIELD_IGNORED", e.getKey(), Text.clip(e.getKey(), 40) + " is ignored on a "
                + (type == null ? "order of this type" : type.name() + " order"), null);
          }
        } else {
          fields.computeIfAbsent(target, k -> new ArrayList<>()).addAll(fields.remove("price"));
        }
      }
      qty = decimal(fields, "qty", false);
      notional = decimal(fields, "notional", false);
      limitPrice = decimal(fields, "limitPrice", false);
      stopPrice = decimal(fields, "stopPrice", false);
      trailAmount = decimal(fields, "trailAmount", false);
      trailPercent = decimal(fields, "trailPercent", true);
      timeInForce = TimeInForce.DAY;
      Map.Entry<String, JsonNode> tif = single(fields, "timeInForce");
      if (tif != null) {
        timeInForce = timeInForce(tif);
      }
      Map.Entry<String, JsonNode> ext = single(fields, "extendedHours");
      if (ext != null) {
        Boolean b = bool(ext.getValue());
        if (b == null) {
          error("FIELD_INVALID", ext.getKey(), ext.getKey() + " must be true or false", null);
        } else {
          extendedHours = b;
        }
      }
      acknowledge(fields);
      rationale(fields, action == ManualActionKind.BUY || action == ManualActionKind.SELL);
      boolean hasQty = fields.containsKey("qty");
      boolean hasNotional = fields.containsKey("notional");
      if (action != null && !hasQty && !hasNotional) {
        error("QTY_OR_NOTIONAL_REQUIRED", "qty", "send qty (shares) or notional (dollars)", "e.g. \"qty\": \"10\" "
            + "or \"notional\": \"250.00\" (MARKET only).");
      } else if (action != null && hasQty && hasNotional) {
        error("FIELD_INVALID", fields.get("notional").get(0).getKey(), "send qty or notional, not both", null);
      }
    }

    /** The one entry of {@code field}, or null when absent or given twice (an error). */
    Map.Entry<String, JsonNode> single(Map<String, List<Map.Entry<String, JsonNode>>> fields, String field) {
      List<Map.Entry<String, JsonNode>> entries = fields.get(field);
      if (entries == null || entries.isEmpty()) {
        return null;
      }
      if (entries.size() > 1) {
        error("FIELD_INVALID", entries.get(1).getKey(), LABEL.getOrDefault(field, field) + " given twice ("
            + String.join(", ", entries.stream().map(e -> Text.clip(e.getKey(), 40)).toList()) + ")", null);
        return null;
      }
      return entries.get(0);
    }

    ManualActionKind action(Map.Entry<String, JsonNode> e) {
      String v = upper(text(e.getValue()));
      if ("CANCEL_ORDER".equals(v)) {
        v = "CANCEL";
      }
      return switch (v) {
        case "BUY" -> ManualActionKind.BUY;
        case "SELL" -> ManualActionKind.SELL;
        case "CANCEL" -> ManualActionKind.CANCEL;
        default -> {
          error("FIELD_INVALID", e.getKey(), e.getKey() + " \"" + Text.clip(text(e.getValue()), 20)
              + "\" is not BUY, SELL or CANCEL", "Use BUY, SELL or CANCEL (SELL with qty to close)");
          yield null;
        }
      };
    }

    String symbol(Map.Entry<String, JsonNode> e) {
      String v = text(e.getValue()).strip();
      if (v.startsWith("$")) {
        v = v.substring(1).strip();
      }
      v = v.toUpperCase(Locale.ROOT);
      if (!SYMBOL.matcher(v).matches()) {
        error("FIELD_INVALID", e.getKey(), e.getKey() + " must be a ticker like \"AAPL\"", null);
        return null;
      }
      return v;
    }

    OrderType type(Map.Entry<String, JsonNode> e) {
      String v = upper(text(e.getValue()));
      OrderType t = switch (v) {
        case "MARKET", "MKT" -> OrderType.MARKET;
        case "LIMIT", "LMT" -> OrderType.LIMIT;
        case "STOP", "STP" -> OrderType.STOP;
        case "STOP_LIMIT", "STOP_LMT", "STPLMT" -> OrderType.STOP_LIMIT;
        case "TRAILING_STOP", "TRAIL", "TRAILING" -> OrderType.TRAILING_STOP;
        default -> null;
      };
      if (t == null) {
        error("FIELD_INVALID", e.getKey(), e.getKey() + " \"" + Text.clip(text(e.getValue()), 20) + "\" is not an "
            + "order type", "Use MARKET, LIMIT, STOP, STOP_LIMIT or TRAILING_STOP.");
      }
      return t;
    }

    TimeInForce timeInForce(Map.Entry<String, JsonNode> e) {
      String v = upper(text(e.getValue()));
      if ("DAY".equals(v)) {
        return TimeInForce.DAY;
      }
      if ("GTC".equals(v) || GTC_LONG.matcher(v).matches()) {
        return TimeInForce.GTC;
      }
      error("FIELD_INVALID", e.getKey(), e.getKey() + " must be DAY or GTC", null);
      return TimeInForce.DAY;
    }

    BigDecimal decimal(Map<String, List<Map.Entry<String, JsonNode>>> fields, String field, boolean percent) {
      Map.Entry<String, JsonNode> e = single(fields, field);
      if (e == null) {
        return null;
      }
      BigDecimal d = parseDecimal(e.getValue(), percent);
      if (d == null || d.signum() <= 0) {
        error("FIELD_INVALID", e.getKey(), Text.clip(e.getKey(), 40) + " must be a positive number like \""
            + EXAMPLE_VALUE.get(field) + "\"", null);
        return null;
      }
      return d;
    }

    void acknowledge(Map<String, List<Map.Entry<String, JsonNode>>> fields) {
      List<String> out = new ArrayList<>();
      Map.Entry<String, JsonNode> ack = single(fields, "acknowledge");
      if (ack != null) {
        JsonNode v = ack.getValue();
        List<JsonNode> items = v.isArray() ? List.copyOf(v.values()) : List.of(v);
        for (JsonNode item : items) {
          if (!item.isString() || item.stringValue().isBlank()) {
            error("FIELD_INVALID", ack.getKey(), ack.getKey() + " must be a list like [\"GFV_RISK\"]", null);
            return;
          }
          out.add(upper(item.stringValue()));
        }
      }
      Map.Entry<String, JsonNode> gfv = single(fields, "acknowledgeGfv");
      if (gfv != null) {
        Boolean b = bool(gfv.getValue());
        if (b == null) {
          error("FIELD_INVALID", gfv.getKey(), gfv.getKey() + " must be true or false", null);
        } else if (b && !out.contains("GFV_RISK")) {
          out.add("GFV_RISK");
        }
      }
      acknowledge = List.copyOf(out);
    }

    void rationale(Map<String, List<Map.Entry<String, JsonNode>>> fields, boolean required) {
      List<Map.Entry<String, JsonNode>> entries = fields.get("rationale");
      if (entries != null && entries.size() > 1) {
        single(fields, "rationale");            // reports "given twice"
        return;
      }
      Map.Entry<String, JsonNode> e = single(fields, "rationale");
      String v = e == null ? null : Text.trimToNull(Text.stripControl(text(e.getValue())));
      if (v == null) {
        if (required) {
          error("RATIONALE_REQUIRED", "rationale", "AI bots must explain each order", "Add \"rationale\": why this "
              + "order, at this size, now.");
        }
        return;
      }
      if (v.length() > RATIONALE_MAX) {
        error("FIELD_INVALID", e.getKey(), e.getKey() + " is longer than " + RATIONALE_MAX + " characters", null);
        return;
      }
      rationale = v;
    }

    void error(String code, String field, String message, String hint) {
      issues.add(issue(ManualIssueSeverity.ERROR, code, field, message, hint));
    }

    void warn(String code, String field, String message, String hint) {
      issues.add(issue(ManualIssueSeverity.WARNING, code, field, message, hint));
    }

    private ManualIssueDto issue(ManualIssueSeverity severity, String code, String field, String message,
                                 String hint) {
      return new ManualIssueDto(severity, code, field == null ? null : Text.clip(field, 40), line, null,
          message + " (line " + line + ")", hint);
    }

    ManualActionDto toDto(boolean allowed) {
      boolean executable = allowed && action != null
          && issues.stream().noneMatch(i -> i.severity() == ManualIssueSeverity.ERROR);
      boolean cancel = action == ManualActionKind.CANCEL;
      return new ManualActionDto(index, line, action, cancel ? null : symbol, cancel ? null : type,
          cancel ? null : qty, cancel ? null : notional, cancel ? null : limitPrice, cancel ? null : stopPrice,
          cancel ? null : trailAmount, cancel ? null : trailPercent, cancel ? null : timeInForce,
          !cancel && extendedHours, cancel ? List.of() : acknowledge, cancel ? orderId : null, rationale, executable,
          List.copyOf(issues), null, null);
    }
  }

  // ------------------------------------------------------------------------------------------- values

  /** JSON null and blank strings count as "not given" (AIs often send {@code "stop_price": null}). */
  private static boolean absent(JsonNode v) {
    return v == null || v.isNull() || (v.isString() && v.stringValue().isBlank());
  }

  private static String text(JsonNode v) {
    return v.isValueNode() ? v.asString() : v.toString();
  }

  /** Upper case with runs of {@code -} and whitespace as {@code _}. */
  private static String upper(String s) {
    return SEPARATORS.matcher(s.strip()).replaceAll("_").toUpperCase(Locale.ROOT);
  }

  /** A number node's value, or a string with an optional {@code $}, {@code %} (percent only) and thousands commas. */
  static BigDecimal parseDecimal(JsonNode v, boolean percent) {
    if (v.isNumber()) {
      return v.decimalValue();
    }
    if (!v.isString()) {
      return null;
    }
    String s = v.stringValue().strip();
    if (s.startsWith("$")) {
      s = s.substring(1).strip();
    }
    if (percent && s.endsWith("%")) {
      s = s.substring(0, s.length() - 1).strip();
    }
    if (s.indexOf(',') >= 0) {
      if (!THOUSANDS.matcher(s).matches()) {
        return null;
      }
      s = s.replace(",", "");
    }
    try {
      return new BigDecimal(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** {@code true/false}, {@code "yes"/"no"}, {@code "y"/"n"}, {@code 1/0} (and those as strings); else null. */
  static Boolean bool(JsonNode v) {
    if (v.isBoolean()) {
      return v.booleanValue();
    }
    String s = v.isNumber() ? v.decimalValue().stripTrailingZeros().toPlainString()
        : v.isString() ? v.stringValue().strip().toLowerCase(Locale.ROOT) : "";
    return switch (s) {
      case "true", "yes", "y", "1" -> Boolean.TRUE;
      case "false", "no", "n", "0" -> Boolean.FALSE;
      default -> null;
    };
  }

  /** Keys are matched in lower case, without {@code _}, {@code -} and spaces. */
  static String norm(String key) {
    return key == null ? "" : key.toLowerCase(Locale.ROOT).replaceAll("[_\\-\\s]", "");
  }

  private static String joinAnd(List<String> items) {
    if (items.size() == 1) {
      return items.get(0);
    }
    return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
  }

  private static ManualIssueDto error(String code, String field, Integer line, Integer column, String message,
                                      String hint) {
    return new ManualIssueDto(ManualIssueSeverity.ERROR, code, field, line, column, message, hint);
  }

  private static ManualIssueDto warning(String code, String field, Integer line, Integer column, String message,
                                        String hint) {
    return new ManualIssueDto(ManualIssueSeverity.WARNING, code, field, line, column, message, hint);
  }
}
