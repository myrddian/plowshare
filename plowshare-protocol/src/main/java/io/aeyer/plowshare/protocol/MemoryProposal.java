package io.aeyer.plowshare.protocol;

import java.util.Objects;

/**
 * A caller's request to write a memory, before the archive has decided anything about it.
 *
 * <p>All structural fields are bounded and validated before this value can enter application code.
 * The archive assigns identity, home and state, enforces its configured body limit, and decides the
 * verdict under its existing ownership rules. A proposal cannot choose a target to retire.
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

  /** Normalizes the summary and author identity; narrative fields retain their content. */
  public MemoryProposal {
    summary = ContractValues.text(summary, "summary", 32768, true).strip();
    if (summary
        .codePoints()
        .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029))
      throw new IllegalArgumentException("field 'summary' must be a single line");
    scope = ContractValues.text(scope, "scope", 32768, true);
    body = ContractValues.text(body, "body", 1048576, true);
    formedBy = ContractValues.identity(formedBy, "formedBy", 1024);
    formedWhere =
        Objects.requireNonNull(
            ContractValues.text(formedWhere, "formedWhere", 32768, false), "formedWhere");
  }
}
