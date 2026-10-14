package com.stocks.manualbot;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.TimeInForce;
import com.stocks.contract.manualbot.dto.ManualActionDto;
import com.stocks.contract.manualbot.dto.ManualActionKind;
import com.stocks.contract.manualbot.dto.ManualIssueDto;
import com.stocks.contract.manualbot.dto.ManualIssueSeverity;
import com.stocks.manualbot.ManualReplyParser.ParsedReply;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * §20.8 reply parsing: candidates (fences of any kind, raw balanced regions), leniency (comments, trailing commas,
 * single and curly quotes, CRLF, BOM, zero-width characters), echo handling, the envelope, the row aliases and values,
 * and line numbers in the normalized text.
 */
class ManualReplyParserTest {

  static final String SESSION = "mbs_01JABCDEFGHJKMNPQRSTVWXYZ0";
  static final ManualReplyParser.Limits LIMITS = new ManualReplyParser.Limits(10, 15);

  static ParsedReply parse(String reply) {
    return ManualReplyParser.parse(reply, SESSION, LIMITS);
  }

  static String envelope(String orders) {
    return "{\"format\": \"stocks.manual.v1\", \"session\": \"" + SESSION + "\", \"summary\": \"Bought some AAPL.\", "
        + "\"orders\": [" + orders + "]}";
  }

  static final String BUY = "{\"action\": \"BUY\", \"symbol\": \"AAPL\", \"type\": \"MARKET\", \"qty\": \"1\", "
      + "\"rationale\": \"Strong quarter.\"}";

  static List<String> codes(List<ManualIssueDto> issues) {
    return issues.stream().map(ManualIssueDto::code).toList();
  }

  static List<String> errors(List<ManualIssueDto> issues) {
    return issues.stream().filter(i -> i.severity() == ManualIssueSeverity.ERROR).map(ManualIssueDto::code).toList();
  }

  static ManualActionDto only(ParsedReply r) {
    assertThat(r.actions()).hasSize(1);
    return r.actions().get(0);
  }

  // ------------------------------------------------------------------------------------------- fences and blocks

  @Test
  void aJsonFenceWithProseBeforeAndAfter() {
    ParsedReply r = parse("Here is my plan. AAPL looks cheap.\n\n```json\n" + envelope(BUY) + "\n```\n\nGood luck!");
    assertThat(r.issues()).isEmpty();
    assertThat(r.summary()).isEqualTo("Bought some AAPL.");
    ManualActionDto a = only(r);
    assertThat(a.index()).isEqualTo(1);
    assertThat(a.line()).isEqualTo(4);
    assertThat(a.action()).isEqualTo(ManualActionKind.BUY);
    assertThat(a.symbol()).isEqualTo("AAPL");
    assertThat(a.type()).isEqualTo(OrderType.MARKET);
    assertThat(a.qty()).isEqualByComparingTo("1");
    assertThat(a.timeInForce()).isEqualTo(TimeInForce.DAY);
    assertThat(a.rationale()).isEqualTo("Strong quarter.");
    assertThat(a.executable()).isTrue();
    assertThat(a.issues()).isEmpty();
  }

  @Test
  void aFenceWithoutLanguageATildeFenceAndAnUnclosedFence() {
    assertThat(only(parse("```\n" + envelope(BUY) + "\n```")).executable()).isTrue();
    assertThat(only(parse("~~~~\n" + envelope(BUY) + "\n~~~~\n")).executable()).isTrue();
    ParsedReply unclosed = parse("Plan:\n```json\n" + envelope(BUY) + "\n");
    assertThat(only(unclosed).executable()).isTrue();
    assertThat(codes(unclosed.issues())).containsExactly("FENCE_UNCLOSED");
    assertThat(unclosed.issues().get(0).line()).isEqualTo(2);
  }

  @Test
  void rawJsonWithoutFences() {
    ParsedReply r = parse("My answer:\n" + envelope(BUY) + "\nThat is all.");
    assertThat(r.issues()).isEmpty();
    assertThat(only(r).line()).isEqualTo(2);
  }

  @Test
  void apostrophesInProseBeforeARawBlockDoNotOpenAString() {
    ParsedReply r = parse("NVDA's run isn't over, but it's time to add AAPL.\n{\n  \"session\": \"" + SESSION
        + "\",\n  \"summary\": \"Added AAPL.\",\n  \"orders\": [\n    " + BUY + "\n  ]\n}");
    assertThat(r.issues()).isEmpty();
    assertThat(only(r).line()).isEqualTo(6);
    assertThat(r.summary()).isEqualTo("Added AAPL.");
  }

  @Test
  void zeroWidthCharactersAreIgnored() {
    String sneaky = envelope(BUY).replace("\"BUY\"", "\"B\u200BUY\"").replace("\"orders\"", "\"ord\u2060ers\"");
    ParsedReply r = parse("```json\n\uFEFF" + sneaky + "\n```");
    assertThat(r.issues()).isEmpty();
    assertThat(only(r).action()).isEqualTo(ManualActionKind.BUY);
  }

  // ------------------------------------------------------------------------------------------- leniency

  @Test
  void commentsTrailingCommasAndSingleQuotes() {
    String reply = """
        ```json
        {
          // my session
          'session': '%s',
          summary: 'Two buys.',  # yaml-style comment
          'orders': [
            {'action': 'BUY', 'symbol': 'AAPL', 'type': 'LIMIT', 'qty': 2, 'limit_price': 180.5,
             'rationale': 'Support at 180',},
          ],
        }
        ```""".formatted(SESSION);
    ParsedReply r = parse(reply);
    assertThat(r.issues()).isEmpty();
    ManualActionDto a = only(r);
    assertThat(a.line()).isEqualTo(7);
    assertThat(a.limitPrice()).isEqualByComparingTo("180.5");
    assertThat(a.qty()).isEqualByComparingTo("2");
    assertThat(a.executable()).isTrue();
  }

  @Test
  void curlyQuotesAreRepairedWithAWarning() {
    String curly = envelope(BUY).replace('"', '\u201C').replace("\u201C,", "\u201D,").replace("\u201C:", "\u201D:")
        .replace("\u201C}", "\u201D}").replace("\u201C]", "\u201D]");
    ParsedReply r = parse("```json\n" + curly + "\n```");
    assertThat(codes(r.issues())).containsExactly("CURLY_QUOTES_FIXED");
    assertThat(only(r).symbol()).isEqualTo("AAPL");
    assertThat(only(r).executable()).isTrue();
  }

  @Test
  void crlfAndBom() {
    String reply = "\uFEFFPlan\r\n```json\r\n{\r\n\"session\": \"" + SESSION + "\",\r\n\"summary\": \"x\",\r\n"
        + "\"orders\": [\r\n" + BUY + "\r\n]}\r\n```\r\n";
    ParsedReply r = parse(reply);
    assertThat(r.issues()).isEmpty();
    assertThat(only(r).line()).isEqualTo(7);
  }

  // ------------------------------------------------------------------------------------------- echoes

  @Test
  void theExampleBlockNextToTheRealOneIsIgnored() {
    ParsedReply r = parse("```json\n" + ManualPromptBuilder.EXAMPLE_JSON + "\n```\n\nMine:\n```json\n"
        + envelope(BUY) + "\n```");
    assertThat(codes(r.issues())).containsExactly("EXAMPLE_IGNORED");
    assertThat(only(r).symbol()).isEqualTo("AAPL");
  }

  @Test
  void aRealBlockThatReusesTheExampleSessionIsUsedWithAWarning() {
    ParsedReply r = parse("```json\n" + envelope(BUY).replace(SESSION, "EXAMPLE") + "\n```");
    assertThat(codes(r.issues())).containsExactly("SESSION_UNEXPECTED");
    assertThat(r.issues().get(0).severity()).isEqualTo(ManualIssueSeverity.WARNING);
    assertThat(r.issues().get(0).message()).contains("EXAMPLE").contains(SESSION);
    assertThat(only(r).executable()).isTrue();
  }

  @Test
  void anEchoedTemplatePlusTheRealBlockIsNotTwoBlocks() {
    String template = "{\"format\": \"stocks.manual.v1\", \"session\": \"" + SESSION + "\", \"summary\": \""
        + ManualPromptBuilder.TEMPLATE_SUMMARY + "\", \"orders\": []}";
    ParsedReply r = parse("```json\n" + template + "\n```\nAnd my answer:\n```json\n" + envelope(BUY) + "\n```");
    assertThat(codes(r.issues())).containsExactly("TEMPLATE_IGNORED");
    assertThat(only(r).symbol()).isEqualTo("AAPL");
  }

  @Test
  void twoDifferentBlocksAreAnErrorNamingBothLines() {
    String other = envelope(BUY.replace("AAPL", "MSFT"));
    ParsedReply r = parse("Option A:\n```json\n" + envelope(BUY) + "\n```\nOption B:\n```json\n" + other + "\n```");
    assertThat(errors(r.issues())).containsExactly("MULTIPLE_JSON_BLOCKS");
    assertThat(r.issues().get(0).message()).contains("lines 3 and 7");
    assertThat(r.actions()).isEmpty();
    // textually identical blocks are one
    ParsedReply same = parse("```json\n" + envelope(BUY) + "\n```\nAgain:\n```json\n" + envelope(BUY) + "\n```");
    assertThat(same.issues()).isEmpty();
    assertThat(same.actions()).hasSize(1);
  }

  @Test
  void noBlockAtAll() {
    ParsedReply r = parse("I would buy AAPL today.");
    assertThat(errors(r.issues())).containsExactly("NO_JSON_BLOCK");
    assertThat(r.actions()).isEmpty();
  }

  // ------------------------------------------------------------------------------------------- envelope

  @Test
  void anotherSessionIsAnErrorAndAMissingSessionAWarning() {
    ParsedReply other = parse(envelope(BUY).replace(SESSION, "mbs_01JZZZZZZZZZZZZZZZZZZZZZZZ"));
    assertThat(errors(other.issues())).containsExactly("SESSION_MISMATCH");
    assertThat(other.issues().get(0).message()).contains("not " + SESSION);
    ParsedReply missing = parse("{\"summary\": \"x\", \"orders\": [" + BUY + "]}");
    assertThat(codes(missing.issues())).containsExactly("SESSION_MISSING");
    assertThat(only(missing).executable()).isTrue();
  }

  @Test
  void elevenOrdersAreTooManyAndAnEmptyListIsFine() {
    String eleven = String.join(",", java.util.Collections.nCopies(11, BUY));
    ParsedReply r = parse(envelope(eleven));
    assertThat(errors(r.issues())).containsExactly("TOO_MANY_ORDERS");
    assertThat(r.issues().get(0).message()).isEqualTo("The reply has 11 orders; at most 10.");
    assertThat(r.actions()).hasSize(11).noneMatch(ManualActionDto::executable);
    ParsedReply many = parse(envelope(String.join(",", java.util.Collections.nCopies(20, BUY))));
    assertThat(many.actions()).hasSize(15);
    ParsedReply none = parse(envelope(""));
    assertThat(none.issues()).isEmpty();
    assertThat(none.actions()).isEmpty();
    assertThat(none.summary()).isEqualTo("Bought some AAPL.");
  }

  @Test
  void aTopLevelArrayIsTheOrders() {
    ParsedReply r = parse("```json\n[" + BUY + "]\n```");
    assertThat(codes(r.issues())).containsExactly("NO_ENVELOPE");
    assertThat(r.summary()).isNull();
    assertThat(only(r).executable()).isTrue();
    assertThat(only(r).line()).isEqualTo(2);
  }

  @Test
  void aLongSummaryIsClippedAndCleaned() {
    String text = "Line one.\nLine\ttwo " + "x".repeat(600);
    ParsedReply r = parse(envelope(BUY).replace("Bought some AAPL.", text.replace("\n", "\\n").replace("\t", "\\t")));
    assertThat(codes(r.issues())).containsExactly("SUMMARY_CLIPPED");
    assertThat(r.summary()).hasSize(500).startsWith("Line one. Line two xx");
    ParsedReply missing = parse("{\"session\": \"" + SESSION + "\", \"orders\": []}");
    assertThat(codes(missing.issues())).containsExactly("SUMMARY_MISSING");
  }

  @Test
  void unknownKeysWarnAndDuplicateKeysAreSyntaxErrorsWithLineAndColumn() {
    ParsedReply unknown = parse(envelope(BUY).replace("\"orders\"", "\"mood\": \"bullish\", \"orders\""));
    assertThat(codes(unknown.issues())).containsExactly("FIELD_IGNORED");
    assertThat(unknown.issues().get(0).field()).isEqualTo("mood");

    String dup = "```json\n{\n  \"session\": \"" + SESSION + "\",\n  \"summary\": \"a\",\n  \"summary\": \"b\",\n"
        + "  \"orders\": []\n}\n```";
    ParsedReply r = parse(dup);
    assertThat(errors(r.issues())).containsExactly("JSON_SYNTAX");
    ManualIssueDto e = r.issues().get(0);
    assertThat(e.line()).isEqualTo(5);
    assertThat(e.column()).isNotNull();
    assertThat(e.message()).startsWith("Line 5, column ").containsIgnoringCase("duplicate");
  }

  // ------------------------------------------------------------------------------------------- rows

  @Test
  void aliasesAreAccepted() {
    String row = "{\"side\": \"buy\", \"ticker\": \"$msft\", \"orderType\": \"LMT\", \"quantity\": 3, "
        + "\"limitPrice\": \"412.50\", \"tif\": \"good til cancelled\", \"outside_rth\": \"no\", \"reason\": \"Dip\"}";
    ManualActionDto a = only(parse(envelope(row)));
    assertThat(a.issues()).isEmpty();
    assertThat(a.action()).isEqualTo(ManualActionKind.BUY);
    assertThat(a.symbol()).isEqualTo("MSFT");
    assertThat(a.type()).isEqualTo(OrderType.LIMIT);
    assertThat(a.qty()).isEqualByComparingTo("3");
    assertThat(a.limitPrice()).isEqualByComparingTo("412.50");
    assertThat(a.timeInForce()).isEqualTo(TimeInForce.GTC);
    assertThat(a.extendedHours()).isFalse();
    assertThat(a.rationale()).isEqualTo("Dip");

    ManualActionDto mkt = only(parse(envelope("{\"action\": \"SELL\", \"symbol\": \"AAPL\", \"type\": \"MKT\", "
        + "\"qty\": \"1\", \"price\": \"190\", \"rationale\": \"Trim\"}")));
    assertThat(mkt.type()).isEqualTo(OrderType.MARKET);
    assertThat(mkt.limitPrice()).isNull();
    assertThat(codes(mkt.issues())).containsExactly("FIELD_IGNORED");
    assertThat(mkt.executable()).isTrue();

    ManualActionDto price = only(parse(envelope("{\"action\": \"BUY\", \"symbol\": \"AAPL\", \"type\": \"LIMIT\", "
        + "\"qty\": \"1\", \"price\": \"190\", \"rationale\": \"Bid\"}")));
    assertThat(price.limitPrice()).isEqualByComparingTo("190");
    ManualActionDto assumed = only(parse(envelope("{\"action\": \"BUY\", \"symbol\": \"AAPL\", \"qty\": \"1\", "
        + "\"price\": \"190\", \"rationale\": \"Bid\"}")));
    assertThat(assumed.type()).isEqualTo(OrderType.LIMIT);
    assertThat(codes(assumed.issues())).containsExactly("TYPE_ASSUMED");
  }

  @Test
  void anAliasCollisionIsAnError() {
    ManualActionDto a = only(parse(envelope("{\"action\": \"BUY\", \"symbol\": \"AAPL\", \"type\": \"LIMIT\", "
        + "\"qty\": \"1\", \"limit_price\": \"190\", \"price\": \"191\", \"rationale\": \"Bid\"}")));
    assertThat(errors(a.issues())).containsExactly("FIELD_INVALID");
    assertThat(a.issues().get(0).message()).isEqualTo("limit price given twice (limit_price, price) (line 1)");
    assertThat(a.executable()).isFalse();
  }

  @Test
  void dollarsCommasAndPercents() {
    ManualActionDto notional = only(parse(envelope("{\"action\": \"BUY\", \"symbol\": \"VTI\", \"type\": \"MARKET\", "
        + "\"notional\": \"$1,250.00\", \"rationale\": \"Index\"}")));
    assertThat(notional.notional()).isEqualByComparingTo("1250.00");
    assertThat(notional.executable()).isTrue();
    ManualActionDto trail = only(parse(envelope("{\"action\": \"SELL\", \"symbol\": \"NVDA\", \"type\": \"trailing "
        + "stop\", \"qty\": \"2\", \"trail_percent\": \"5%\", \"rationale\": \"Protect\"}")));
    assertThat(trail.type()).isEqualTo(OrderType.TRAILING_STOP);
    assertThat(trail.trailPercent()).isEqualByComparingTo("5");
    ManualActionDto bad = only(parse(envelope("{\"action\": \"BUY\", \"symbol\": \"AAPL\", \"type\": \"MARKET\", "
        + "\"qty\": \"1,2\", \"rationale\": \"x\"}")));
    assertThat(errors(bad.issues())).containsExactly("FIELD_INVALID");
    assertThat(bad.issues().get(0).message()).isEqualTo("qty must be a positive number like \"10\" (line 1)");
    ManualActionDto negative = only(parse(envelope("{\"action\": \"BUY\", \"symbol\": \"AAPL\", \"type\": "
        + "\"MARKET\", \"qty\": -3, \"rationale\": \"x\"}")));
    assertThat(errors(negative.issues())).containsExactly("FIELD_INVALID");
  }

  @Test
  void qtyAndNotionalBothOrNeither() {
    ManualActionDto both = only(parse(envelope("{\"action\": \"BUY\", \"symbol\": \"AAPL\", \"type\": \"MARKET\", "
        + "\"qty\": \"1\", \"notional\": \"100\", \"rationale\": \"x\"}")));
    assertThat(errors(both.issues())).containsExactly("FIELD_INVALID");
    assertThat(both.issues().get(0).message()).startsWith("send qty or notional, not both");
    ManualActionDto neither = only(parse(envelope("{\"action\": \"BUY\", \"symbol\": \"AAPL\", \"type\": "
        + "\"MARKET\", \"rationale\": \"x\"}")));
    assertThat(errors(neither.issues())).containsExactly("QTY_OR_NOTIONAL_REQUIRED");
  }

  @Test
  void aRationaleIsRequiredOnTradesButNotOnCancels() {
    ManualActionDto buy = only(parse(envelope("{\"action\": \"BUY\", \"symbol\": \"AAPL\", \"type\": \"MARKET\", "
        + "\"qty\": \"1\"}")));
    assertThat(errors(buy.issues())).containsExactly("RATIONALE_REQUIRED");
    assertThat(buy.executable()).isFalse();
    ManualActionDto cancel = only(parse(envelope("{\"action\": \"cancel_order\", \"order_id\": "
        + "\"ord_01JABCDEFGHJKMNPQRSTVWXYZ0\"}")));
    assertThat(cancel.issues()).isEmpty();
    assertThat(cancel.action()).isEqualTo(ManualActionKind.CANCEL);
    assertThat(cancel.orderId()).isEqualTo("ord_01JABCDEFGHJKMNPQRSTVWXYZ0");
    assertThat(cancel.executable()).isTrue();
  }

  @Test
  void aCancelNeedsAnOrderId() {
    ManualActionDto a = only(parse(envelope("{\"action\": \"CANCEL\", \"rationale\": \"stale\"}")));
    assertThat(errors(a.issues())).containsExactly("FIELD_MISSING");
    assertThat(a.issues().get(0).field()).isEqualTo("order_id");
    assertThat(a.executable()).isFalse();
  }

  @Test
  void aRepeatedRowWarnsAndBadActionsAreRejected() {
    ParsedReply r = parse(envelope(BUY + "," + BUY + ",{\"action\": \"CLOSE\", \"symbol\": \"AAPL\"}, 7"));
    assertThat(r.actions()).hasSize(4);
    assertThat(codes(r.actions().get(1).issues())).containsExactly("DUPLICATE_ROW");
    assertThat(r.actions().get(1).executable()).isTrue();
    ManualActionDto close = r.actions().get(2);
    assertThat(close.issues().get(0).code()).isEqualTo("FIELD_INVALID");
    assertThat(close.issues().get(0).hint()).isEqualTo("Use BUY, SELL or CANCEL (SELL with qty to close)");
    assertThat(close.executable()).isFalse();
    assertThat(r.actions().get(3).issues().get(0).message()).isEqualTo("order #4 is not an object (line 1)");
  }

  // ------------------------------------------------------------------------------------------- line mapping

  @Test
  void aSyntaxErrorMapsToTheReplyLine() {
    StringBuilder reply = new StringBuilder();
    for (int i = 1; i <= 10; i++) {
      reply.append("Thought ").append(i).append(".\n");
    }
    reply.append("```json\n");                                   // line 11; the block starts at line 12
    reply.append("{\n  \"session\": \"").append(SESSION).append("\",\n  \"orders\": [ }\n}\n```\n");
    ParsedReply r = parse(reply.toString());
    assertThat(errors(r.issues())).containsExactly("JSON_SYNTAX");
    assertThat(r.issues().get(0).line()).isEqualTo(14);
    assertThat(r.issues().get(0).message()).startsWith("Line 14, column ");
  }

  @Test
  void rowLinesAreThoseOfEachObject() {
    String reply = "Plan\n```json\n{\n  \"session\": \"" + SESSION + "\",\n  \"summary\": \"x\",\n  \"orders\": [\n"
        + "    " + BUY + ",\n\n    {\n      \"action\": \"SELL\", \"symbol\": \"MSFT\", \"type\": \"MARKET\",\n"
        + "      \"qty\": \"2\", \"rationale\": \"Trim\"\n    }\n  ]\n}\n```";
    ParsedReply r = parse(reply);
    assertThat(r.actions()).extracting(ManualActionDto::line).containsExactly(7, 9);
  }

  @Test
  void redactingABearerTokenAcrossALineBreakKeepsTheLines() {
    String reply = "Auth header: Bearer\nabcdefghijklmnopqrstuvwxyz0123456789\nKey sk-abcdefghijklmnop0123\n```json\n"
        + envelope(BUY) + "\n```";
    String redacted = ManualBotService.KEY_SHAPED_INLINE.matcher(reply).replaceAll(ManualBotService.REDACTED);
    assertThat(redacted).contains("Key [redacted]").contains("Bearer\nabcdef");
    assertThat(redacted.split("\n", -1)).hasSameSizeAs(reply.split("\n", -1));
    assertThat(only(parse(redacted)).line()).isEqualTo(5);
    assertThat(ManualBotService.KEY_SHAPED_INLINE.matcher("Bearer abcdefghijklmnopqrstuvwxyz").replaceAll("[r]"))
        .isEqualTo("[r]");
  }
}
