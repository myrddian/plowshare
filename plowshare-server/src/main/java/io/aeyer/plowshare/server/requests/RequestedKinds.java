package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The entry kinds a reading of a log is narrowed to, as both surfaces read them.
 *
 * <p><b>Why a reader narrows at all.</b> A chat draws utterances, answers and summaries, and a log
 * is mostly the rest — a tool result and a hook per call, the thinking, the diagnostics. A reader
 * that is sent every row only to hide most of them pays for the bytes and, worse, for the limit:
 * forty rows of the log may be two turns. Narrowed in the database, forty rows are forty things the
 * reader draws.
 *
 * <p><b>A name this build does not know is refused, not skipped.</b> A misspelt kind read as "none
 * of those" would answer with a shorter page and no complaint, which is a client drawing less than
 * it asked for without knowing why.
 */
public final class RequestedKinds {

  private RequestedKinds() {}

  /**
   * The kinds a request named, or every kind when it named none.
   *
   * @param kinds the request's own {@code kinds}: wire names, as {@link EntryKind#wireName()}
   *     spells them, or null
   * @return {@link EntryStore#EVERY_KIND} when absent, otherwise the kinds named
   * @throws CallerFault if the list is empty or names a kind this build does not know
   */
  public static Set<EntryKind> in(List<String> kinds) {
    if (kinds == null) {
      return EntryStore.EVERY_KIND;
    }
    if (kinds.isEmpty()) {
      throw new CallerFault(
          "'kinds' names the entry kinds to read, and an empty list reads nothing."
              + " Leave it out to read every kind.");
    }
    Set<EntryKind> named = EnumSet.noneOf(EntryKind.class);
    for (String kind : kinds) {
      named.add(
          Arrays.stream(EntryKind.values())
              .filter(each -> each.wireName().equals(kind))
              .findFirst()
              .orElseThrow(
                  () ->
                      new CallerFault(
                          "no entry kind is spelled '"
                              + kind
                              + "'; 'kinds' takes "
                              + Arrays.stream(EntryKind.values())
                                  .map(EntryKind::wireName)
                                  .collect(Collectors.joining(", "))
                              + ".")));
    }
    return named;
  }
}
