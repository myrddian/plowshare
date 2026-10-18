package io.aeyer.plowshare.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.archive.EntryPage;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntryViewTest {
  @Test
  void inference_usage_preserves_zero_and_unknown_without_entering_message_text() {
    var row =
        new EntryPage.Row(
            1,
            1,
            EntryKind.ANSWER,
            "",
            0,
            null,
            null,
            null,
            List.of(),
            null,
            null,
            20L,
            "primary",
            "model",
            "called_tools",
            null,
            null,
            "job_test",
            100,
            0,
            null);
    var view = EntryView.of(row);
    assertEquals(100, view.promptTokens());
    assertEquals(0, view.completionTokens());
    assertNull(view.reasoningTokens());
    assertEquals(20L, view.tookMillis());
    assertEquals("", view.excerpt());
  }
}
