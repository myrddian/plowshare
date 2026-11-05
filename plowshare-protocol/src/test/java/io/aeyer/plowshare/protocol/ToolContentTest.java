package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ToolContentTest {
  ToolContent.Part image() {
    return new ToolContent.Part(
        ToolContent.Kind.IMAGE, null, "img_" + "a".repeat(32), 0, ToolContent.Use.OBSERVATION);
  }

  @Test
  void order_and_image_only_results_preserve_the_exact_text_projection() {
    var body =
        new ToolContent.Body(
            "content/1",
            List.of(ToolContent.Part.text("before"), image(), ToolContent.Part.text("after")));
    assertEquals("beforeafter", body.text());
    assertEquals(image(), body.parts().get(1));
    assertEquals("", new ToolContent.Body("content/1", List.of(image())).text());
  }

  @Test
  void count_text_slot_and_variant_boundaries_are_closed() {
    assertThrows(
        IllegalArgumentException.class, () -> new ToolContent.Body("content/2", List.of(image())));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolContent.Body("content/1", List.of(image(), image())));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolContent.Body("content/1", List.of(ToolContent.Part.text(" "))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolContent.Body(
                "content/1",
                List.of(ToolContent.Part.text("a".repeat(16384)), ToolContent.Part.text("b"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolContent.Part(
                ToolContent.Kind.IMAGE, null, "file:///private.png", 0, ToolContent.Use.BOTH));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolContent.Part(ToolContent.Kind.TEXT, "words", image().imageId(), null, null));
  }
}
