package io.aeyer.plowshare.server.proc;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ProcCursorTest {
  private static final String CALL = "0f8fad5b-d9cb-469f-a165-70867728950e";

  @Test
  void callCursorRoundTripsMicrosecondTimeAndCanonicalId() {
    var at = Instant.parse("2026-11-12T10:15:30.123456Z");
    String cursor = ProcCursor.timed(at, CALL);
    assertEquals("1794478530123456:" + CALL, cursor);
    var parsed = ProcCursor.call(cursor);
    assertEquals(at, parsed.at());
    assertEquals(CALL, parsed.key());
    assertEquals(at.atOffset(ZoneOffset.UTC), parsed.time());
    assertEquals(Instant.EPOCH, ProcCursor.call("0:" + CALL).at());
    assertEquals(Instant.parse("1969-12-31T23:59:59.999999Z"), ProcCursor.call("-1:" + CALL).at());
    // Every encodable position parses back, including far-future and maximum values.
    for (String time : new String[] {"2999-01-01T00:00:00Z", "0001-01-01T00:00:00Z"}) {
      var edge = Instant.parse(time);
      assertEquals(edge, ProcCursor.call(ProcCursor.timed(edge, CALL)).at());
    }
    assertEquals(
        Instant.EPOCH.plus(Long.MAX_VALUE, java.time.temporal.ChronoUnit.MICROS),
        ProcCursor.call(Long.MAX_VALUE + ":" + CALL).at());
    assertThrows(
        IllegalArgumentException.class,
        () -> ProcCursor.timed(Instant.parse("2026-01-01T00:00:00.000000001Z"), CALL));
  }

  @Test
  void callCursorRejectsOldFormatsAndMalformedValuesWithoutEchoingThem() {
    for (String invalid :
        new String[] {
          CALL, // A bare call id is the pre-chronology cursor.
          "zzzz",
          ":" + CALL,
          "1:",
          "1",
          "x1:" + CALL,
          "+1:" + CALL,
          "1.5:" + CALL,
          "9223372036854775808:" + CALL, // Overflows long.
          "12345678901234567890:" + CALL, // More digits than any long.
          "-62135596800000001:" + CALL, // Before 0001-01-01.
          "1:" + CALL.toUpperCase(),
          "1:" + CALL.replace("-", ""),
          "1:" + CALL + ":extra",
          "1:not-a-uuid-at-all-not-a-uuid-at-all",
        }) {
      var fault = assertThrows(CallerFault.class, () -> ProcCursor.call(invalid), invalid);
      assertTrue(fault.getMessage().startsWith("PROC call cursor is malformed"));
      assertFalse(fault.getMessage().contains(invalid), "faults never echo caller text");
    }
  }

  @Test
  void queueCursorNeedsRevisionPrefixedRowId() {
    var at = Instant.parse("2026-11-12T10:15:30Z");
    for (String row :
        new String[] {
          CALL + ":stages", CALL + ":chunks:code", CALL + ":eligible:autoTag", CALL + ":paragraphs"
        }) {
      var parsed = ProcCursor.queue(ProcCursor.timed(at, row));
      assertEquals(at, parsed.at());
      assertEquals(row, parsed.key());
    }
    for (String invalid :
        new String[] {
          CALL + ":stages", // Old cursor: the bare row id.
          "summary:stages:processing",
          "1:" + CALL,
          "1:" + CALL + ":",
          "1:" + CALL + "stages",
          "1:" + CALL + ": stages",
          "1:abc:stages",
        }) assertThrows(CallerFault.class, () -> ProcCursor.queue(invalid), invalid);
  }

  @Test
  void toolCursorSplitsConversationNumericOrdinalAndColonBearingToolId() {
    var parsed = ProcCursor.tool("cnv_019A2B3C4D5E6F:10:call_1");
    assertEquals("cnv_019A2B3C4D5E6F", parsed.conversation());
    assertEquals(10, parsed.ordinal());
    assertEquals("call_1", parsed.tool());
    var colon = ProcCursor.tool("cnv_1:2:functions.web_search:7");
    assertEquals("cnv_1", colon.conversation());
    assertEquals(2, colon.ordinal());
    assertEquals("functions.web_search:7", colon.tool());
    assertEquals(Integer.MAX_VALUE, ProcCursor.tool("c:2147483647:t").ordinal());
    for (String invalid :
        new String[] {
          "cnv_1",
          "cnv_1:2",
          "cnv_1:2:",
          ":2:t",
          "cnv_1::t",
          "cnv_1:x:t",
          "cnv_1:-1:t",
          "cnv_1:2147483648:t",
          "cnv_1:12345678901:t",
          "cnv_1:1e3:t"
        }) assertThrows(CallerFault.class, () -> ProcCursor.tool(invalid), invalid);
  }
}
