package io.aeyer.plowshare.server.images;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ImageFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Converts the persisted sidecar to a validated image before any lookup/fence logic runs. */
final class ImageSidecars {
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .build();

  static {
    for (var shape :
        new CoercionInputShape[] {
          CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean
        }) JSON.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
  }

  private ImageSidecars() {}

  record Record(
      String id,
      String project,
      String format,
      String filename,
      long bytes,
      String at,
      String path) {
    Record {
      if (!ImageStore.isUid(id)) throw new IllegalArgumentException("invalid sidecar image id");
      if (project != null) Home.of(project);
      if (bytes < 1) throw new IllegalArgumentException("invalid sidecar byte count");
      Instant.parse(at);
      boolean known = false;
      for (var candidate : ImageFormat.values())
        if (candidate.declared().equals(format)) known = true;
      if (!known) throw new IllegalArgumentException("unrecognized sidecar image format");
      if (path != null
          && (path.length() > 8192
              || !Path.of(path).isAbsolute()
              || !Path.of(path).equals(Path.of(path).normalize())))
        throw new IllegalArgumentException(
            "sidecar workspace path must be absolute and normalized");
    }
  }

  static StoredImage read(Home home, Path sidecar) throws IOException {
    byte[] bytes;
    try (var input = Files.newInputStream(sidecar)) {
      bytes = input.readNBytes(65537);
    }
    if (bytes.length > 65536) throw new IOException("image sidecar exceeds 64 KiB");
    try {
      var node = JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
      if (node == null
          || !node.isObject()
          || !node.has("project")
          || !node.path("bytes").isIntegralNumber())
        throw new IOException("incomplete image sidecar");
      var record = JSON.treeToValue(node, Record.class);
      if (!home.equals(record.project() == null ? Home.global() : Home.of(record.project()))
          || !sidecar.getFileName().toString().equals(record.id() + ".json"))
        throw new IOException("foreign image sidecar identity or project");
      ImageFormat format =
          java.util.Arrays.stream(ImageFormat.values())
              .filter(value -> value.declared().equals(record.format()))
              .findFirst()
              .orElseThrow();
      return new StoredImage(
          record.id(),
          home,
          format,
          record.filename(),
          record.bytes(),
          Instant.parse(record.at()),
          record.path() == null ? null : Path.of(record.path()));
    } catch (IllegalArgumentException invalid) {
      throw new IOException("invalid image sidecar contract", invalid);
    }
  }
}
