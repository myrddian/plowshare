package io.aeyer.plowshare.protocol;

import java.util.Objects;

/**
 * A caller's request to write a memory, before the archive has decided anything about it.
 *
 * <p>Nothing here is validated and nothing here is a {@link Memory} yet: no id, no {@link
 * MemoryState}, no {@link Home}. Assigning those is the archive's job, once {@code
 * Validation.check} has passed the proposal and a {@code Verdict} has said whether it is new, a
 * supersession, or a merge. A proposal that carried its own id or state would let a caller dictate
 * the archive's semantics instead of asking for them.
 *
 * <p>Ported from Excalibur's {@code archive/validation.py}, minus one field: Python's {@code pin}
 * defaults a proposal straight to the pinned working set. Plowshare has no such shortcut yet —
 * {@link Memory#pinned} is set by the archive's write path, not requested by the caller — so this
 * record carries only the fields validation itself needs.
 *
 * @param summary the claim, stated so it stands alone; must be a single line
 * @param scope prose saying when this memory is worth recalling
 * @param body the full text behind the summary
 * @param formedBy the agent proposing the memory
 * @param formedWhere the situation it was formed in, in prose; may be blank, unlike the fields
 *     above — Excalibur defaults it to the empty string rather than requiring it
 */
public record MemoryProposal(
    String summary, String scope, String body, String formedBy, String formedWhere) {

  /**
   * Rejects {@code null}, and only {@code null}. Blank is a shape the server's {@code
   * Validation.check} decides on, with a message naming the field; a {@code null} here would
   * instead surface as an NPE with no field name, out of whichever method first called {@code
   * .isBlank()} on it.
   */
  public MemoryProposal {
    Objects.requireNonNull(summary, "summary");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(body, "body");
    Objects.requireNonNull(formedBy, "formedBy");
    Objects.requireNonNull(formedWhere, "formedWhere");
  }
}
