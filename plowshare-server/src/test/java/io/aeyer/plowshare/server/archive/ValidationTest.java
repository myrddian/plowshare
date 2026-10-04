package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.MemoryProposal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationTest {

  private static MemoryProposal proposal(String summary, String scope, String body) {
    return new MemoryProposal(summary, scope, body, "probe", "test");
  }

  /**
   * Python's {@code REQUIRED_TEXT_FIELDS} is summary, scope, body and formed_by — four fields, not
   * three. {@code formedWhere} is excluded deliberately (see below), so the fourth case here is
   * spelled out directly rather than through the three-arg {@code proposal} helper.
   */
  @Test
  void every_field_is_required() {
    for (var p :
        List.of(
            proposal("", "scope", "body"),
            proposal("summary", "", "body"),
            proposal("summary", "scope", ""),
            new MemoryProposal("summary", "scope", "body", "", "test"))) {
      assertThrows(ValidationException.class, () -> Validation.check(p, 8000));
    }
  }

  /**
   * A summary is the one line the index shows and the thing recall matches against. A multi-line
   * summary breaks the index's shape silently.
   */
  @Test
  void a_summary_must_be_one_line() {
    var p = proposal("first line\nsecond line", "scope", "body");
    var e = assertThrows(ValidationException.class, () -> Validation.check(p, 8000));
    assertTrue(e.getMessage().contains("single line"));
  }

  @Test
  void a_body_over_the_limit_is_refused_naming_the_limit() {
    var p = proposal("summary", "scope", "x".repeat(8001));
    var e = assertThrows(ValidationException.class, () -> Validation.check(p, 8000));
    assertTrue(e.getMessage().contains("8000"));
  }

  @Test
  void a_valid_proposal_passes() {
    assertDoesNotThrow(() -> Validation.check(proposal("s", "sc", "b"), 8000));
  }

  /**
   * No semantic quality gate — a thin memory is the caller's problem, not something validation
   * refuses.
   */
  @Test
  void a_shallow_but_well_formed_proposal_passes() {
    assertDoesNotThrow(() -> Validation.check(proposal("s", "sc", "ok"), 8000));
  }
}
