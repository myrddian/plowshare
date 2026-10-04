package io.aeyer.plowshare.server.hooks;

import java.util.List;

/**
 * What a gating log stage decided — {@code stage.pre}, {@code stage.post} and {@code approval.pre}:
 * the refusal, or nothing refused and the notes to pass on, and the record of every decision (spec
 * 2026-09-28-hooks-reach-the-log §3).
 *
 * <p>These stages fail closed (§2.4): a hook that breaks produces a {@code denied} sentence, never
 * a pass. There is no allow: a gate can only narrow (decision 5).
 *
 * @param denied the refusal the run is shown, {@code "'<hook>': <reason>"}, or {@code null}
 */
public record Gate(String denied, List<String> notes, List<HookRecord> records) {

  public static final Gate NOTHING = new Gate(null, List.of(), List.of());

  public Gate {
    notes = List.copyOf(notes);
    records = List.copyOf(records);
  }

  public boolean isDenied() {
    return denied != null;
  }

  /**
   * The text with every note after it, a blank line apart; the notes alone for no text. What a
   * {@code todo_write} result and an approval's question carry (§3).
   */
  public String applyTo(String text) {
    if (notes.isEmpty()) {
      return text;
    }
    String joined = String.join("\n\n", notes);
    return text == null || text.isBlank() ? joined : text + "\n\n" + joined;
  }
}
