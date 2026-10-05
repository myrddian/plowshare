package io.aeyer.plowshare.server.board;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Validated persisted seat wake. Absent fields preserve the original opened-wake defaults. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SeatWake(String reason, String message, String by, Integer maxTurns) {
  public SeatWake {
    WakeRules.Reason.fromWire(reason == null ? "opened" : reason);
    if (message != null) identity(message, "message");
    if (by != null
        && (by.length() > 1024
            || by.codePoints()
                .anyMatch(
                    code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029)))
      throw new IllegalArgumentException("seat wake author is invalid");
    if (maxTurns != null && maxTurns < 1)
      throw new IllegalArgumentException("the member retry has an invalid step limit");
  }

  WakeRules.Reason reasonKind() {
    return WakeRules.Reason.fromWire(reason == null ? "opened" : reason);
  }

  private static void identity(String value, String field) {
    if (value.isBlank()
        || !value.equals(value.strip())
        || value.length() > 1024
        || value
            .codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029))
      throw new IllegalArgumentException("seat wake " + field + " is invalid");
  }
}
