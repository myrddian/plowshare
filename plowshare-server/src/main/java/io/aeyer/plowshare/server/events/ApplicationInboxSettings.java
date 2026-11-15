package io.aeyer.plowshare.server.events;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Strict manifest boundary for optional successful-workflow attention overrides. */
public record ApplicationInboxSettings(List<String> attentionOrchestrations) {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public ApplicationInboxSettings {
    if (attentionOrchestrations == null)
      throw new IllegalArgumentException("attentionOrchestrations is required");
    attentionOrchestrations = List.copyOf(attentionOrchestrations);
    if (attentionOrchestrations.size() > 64
        || attentionOrchestrations.stream().distinct().count() != attentionOrchestrations.size())
      throw new IllegalArgumentException("Choose up to 64 unique attention orchestrations");
    attentionOrchestrations.forEach(
        name -> ScheduledWork.identity(name, "attention orchestration"));
  }

  public static Optional<ApplicationInboxSettings> read(Path root) {
    Path file = root.resolve("plowshare.json");
    try {
      if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        throw new CallerFault("Application inbox policy is unavailable");
      try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        byte[] bytes = input.readNBytes(65537);
        if (bytes.length > 65536) throw new CallerFault("Application inbox policy is oversized");
        var manifest = JSON.readTree(bytes);
        if (manifest == null || !manifest.isObject())
          throw new CallerFault("Invalid Application manifest");
        var policy = manifest.get("inbox");
        if (policy == null) return Optional.empty();
        if (!policy.isObject()) throw new CallerFault("Application inbox must be an object");
        return Optional.of(JSON.treeToValue(policy, ApplicationInboxSettings.class));
      }
    } catch (IOException | IllegalArgumentException invalid) {
      throw new CallerFault("Invalid Application inbox policy");
    }
  }
}
