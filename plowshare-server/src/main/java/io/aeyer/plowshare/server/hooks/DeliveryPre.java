package io.aeyer.plowshare.server.hooks;

import java.util.List;

/**
 * What {@code delivery.pre} decided: notes appended to the delivered text. It cannot reroute or
 * withhold a result, because hiding output from a person is not a narrowing (spec
 * 2026-09-28-hooks-reach-the-log §3).
 */
public record DeliveryPre(List<String> notes, List<HookRecord> records) {

  public static final DeliveryPre NOTHING = new DeliveryPre(List.of(), List.of());

  public DeliveryPre {
    notes = List.copyOf(notes);
    records = List.copyOf(records);
  }

  /** The text as it is to be delivered: the original, then each note after a blank line. */
  public String applyTo(String text) {
    return notes.isEmpty() ? text : text + "\n\n" + String.join("\n\n", notes);
  }
}
