package io.aeyer.plowshare.server.images;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImageSidecarsTest {
  @TempDir Path directory;
  private static final String ID = "img_0123456789abcdef0123456789abcdef";
  private static final String WIRE =
      """
      {"id":"img_0123456789abcdef0123456789abcdef","project":"atlas","format":"png",
      "filename":"chart.png","bytes":128,"at":"2026-10-01T00:00:00Z"}
      """;

  @Test
  void historical_held_sidecar_without_path_retains_its_identity_and_project() throws Exception {
    var file = Files.writeString(directory.resolve(ID + ".json"), WIRE);
    var image = ImageSidecars.read(Home.of("atlas"), file);
    assertEquals(ID, image.id());
    assertNull(image.path());
    assertThrows(IOException.class, () -> ImageSidecars.read(Home.global(), file));
    var foreign =
        Files.writeString(directory.resolve("img_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.json"), WIRE);
    assertThrows(IOException.class, () -> ImageSidecars.read(Home.of("atlas"), foreign));
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"128\"", "128.5", "0", "null", "true"})
  void byte_counts_are_never_coerced_or_invented(String value) throws Exception {
    var file =
        Files.writeString(
            directory.resolve(ID + ".json"), WIRE.replace("\"bytes\":128", "\"bytes\":" + value));
    assertThrows(IOException.class, () -> ImageSidecars.read(Home.of("atlas"), file));
  }

  @Test
  void duplicate_fields_trailing_values_and_untyped_optional_paths_are_refused() throws Exception {
    for (String wire :
        java.util.List.of(
            WIRE.replace("\"bytes\":128", "\"bytes\":128,\"bytes\":1"),
            WIRE + "{}",
            WIRE.replace("\"filename\":\"chart.png\"", "\"filename\":42"),
            WIRE.replace("\"bytes\":128", "\"path\":false,\"bytes\":128"),
            WIRE.replace("\"bytes\":128", "\"path\":\"../foreign\",\"bytes\":128"),
            WIRE.replace("\"at\":\"2026-10-01T00:00:00Z\"", "\"at\":0"))) {
      var file = Files.writeString(directory.resolve(ID + ".json"), wire);
      assertThrows(IOException.class, () -> ImageSidecars.read(Home.of("atlas"), file));
    }
  }
}
