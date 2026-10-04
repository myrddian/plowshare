package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code inbox_read}'s rendering of a run's result versus a notice such as a sync conflict. */
class InboxToolTest {

  private final Inbox inbox = mock(Inbox.class);
  private final InboxTool tool = new InboxTool(inbox, "enzo");

  @Test
  void a_run_item_is_headed_by_its_ending_and_names_its_conversation() {
    InboxItem item =
        new InboxItem(
            "inb_1",
            "enzo",
            InboxStore.KIND_RUN,
            "fir_1",
            "cnv_1",
            "ANSWERED",
            "three PRs",
            Instant.parse("2026-09-13T09:02:00Z"),
            null);
    when(inbox.list("enzo", true, 0, InboxTool.SHOWN + 1)).thenReturn(List.of(item));

    String out = tool.run("{}", null);

    assertTrue(out.contains("ANSWERED"), out);
    assertTrue(out.contains("conversation cnv_1"), out);
    assertFalse(out.contains("null"), out);
  }

  @Test
  void a_notice_item_is_headed_by_its_kind_and_names_no_conversation() {
    InboxItem item =
        new InboxItem(
            "inb_2",
            "enzo",
            InboxStore.KIND_SYNC_CONFLICT,
            null,
            null,
            null,
            "ledger: src/a.ts conflicted",
            Instant.parse("2026-09-14T10:00:00Z"),
            null);
    when(inbox.list("enzo", true, 0, InboxTool.SHOWN + 1)).thenReturn(List.of(item));

    String out = tool.run("{}", null);

    assertTrue(out.contains("sync conflict"), out);
    assertFalse(out.contains("conversation"), out);
    assertFalse(out.contains("null"), out);
  }

  /**
   * What reading left: the items shown are marked read, and it says so — and when more were unread
   * than one answer shows, it says those are still unread, rather than a model taking the twenty it
   * saw for all there was.
   */
  @Test
  void it_says_what_it_marked_read_and_that_more_remain_when_they_do() {
    List<InboxItem> many = new java.util.ArrayList<>();
    for (int i = 0; i <= InboxTool.SHOWN; i++) {
      many.add(
          new InboxItem(
              "inb_" + i,
              "enzo",
              InboxStore.KIND_RUN,
              "fir_" + i,
              null,
              "ANSWERED",
              "result " + i,
              Instant.parse("2026-09-13T09:02:00Z"),
              null));
    }
    when(inbox.list("enzo", true, 0, InboxTool.SHOWN + 1)).thenReturn(many);

    String out = tool.run("{}", null);

    assertFalse(out.contains("result " + InboxTool.SHOWN), "one past the shown is not shown");
    assertTrue(
        out.endsWith(
            "These "
                + InboxTool.SHOWN
                + " are marked read now; more are still"
                + " unread — call inbox_read again for them."),
        out);
    org.mockito.Mockito.verify(inbox)
        .read("enzo", many.subList(0, InboxTool.SHOWN).stream().map(InboxItem::id).toList());
  }

  @Test
  void one_item_read_is_said_to_be_marked_read_and_nothing_more() {
    InboxItem item =
        new InboxItem(
            "inb_1",
            "enzo",
            InboxStore.KIND_RUN,
            "fir_1",
            null,
            "ANSWERED",
            "done",
            Instant.parse("2026-09-13T09:02:00Z"),
            null);
    when(inbox.list("enzo", true, 0, InboxTool.SHOWN + 1)).thenReturn(List.of(item));

    assertTrue(tool.run("{}", null).endsWith("This one is marked read now."));
  }
}
