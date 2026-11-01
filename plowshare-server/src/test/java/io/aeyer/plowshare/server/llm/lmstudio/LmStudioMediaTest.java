package io.aeyer.plowshare.server.llm.lmstudio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.llm.MediaRequirements;
import io.aeyer.plowshare.server.llm.ModelMediaCapabilities;
import io.aeyer.plowshare.server.llm.OpenAiCompatible;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LmStudioMediaTest {
  @Test
  void model_declaration_cannot_override_backend_or_prefill_harness_restrictions() {
    var serializer = mock(OpenAiCompatible.class);
    var model =
        new ModelMediaCapabilities(
            Set.of("text/plain", "image/png", "image/jpeg", "image/gif", "image/webp"),
            Map.of("text", new ModelMediaCapabilities.OutputMode(Set.of("text/plain"))));
    when(serializer.mediaCapabilities("gemma")).thenReturn(model);
    var http = new LmStudio(serializer).mediaCapabilities("gemma");
    assertEquals(Set.of("text/plain", "image/png", "image/jpeg", "image/webp"), http.inputs());
    for (String format : Set.of("image/png", "image/jpeg", "image/webp")) {
      assertTrue(
          new MediaRequirements(Set.of(format), Set.of("text/plain"), Set.of("text/plain"))
              .matches(model, http));
    }
    assertFalse(
        new MediaRequirements(Set.of("image/gif"), Set.of("text/plain"), Set.of("text/plain"))
            .matches(model, http));
    assertEquals(
        ModelMediaCapabilities.TEXT_ONLY,
        new LmStudio(serializer, mock(LmStudioSocket.class)).mediaCapabilities("gemma"));
    when(serializer.mediaCapabilities("gemma")).thenReturn(ModelMediaCapabilities.TEXT_ONLY);
    assertEquals(
        ModelMediaCapabilities.TEXT_ONLY, new LmStudio(serializer).mediaCapabilities("gemma"));
  }
}
