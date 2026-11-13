package io.aeyer.plowshare.a2a.relay;

import io.aeyer.plowshare.a2a.Ingress;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import okhttp3.HttpUrl;

/** Private operator export from the archived pre-cutover SDK; never an external HTTP claim. */
public record LegacyProvenanceBundle(
    String schema, String origin, String project, String principal, List<Row> tasks) {
  public record Row(
      String client,
      UUID id,
      UUID context,
      String agent,
      String message,
      Instant createdAt,
      Ingress.Source source,
      boolean originalContextKnown) {
    public Row {
      IngressWire.checkClient(client);
      Objects.requireNonNull(id);
      Objects.requireNonNull(context);
      RelayWork.identity(agent);
      RelayWork.identity(message);
      Objects.requireNonNull(createdAt);
      if (source == null && originalContextKnown)
        throw new IllegalArgumentException("Missing source cannot prove original context");
    }
  }

  public LegacyProvenanceBundle {
    if (!"plowshare-a2a-provenance/1".equals(schema))
      throw new IllegalArgumentException("Unsupported historical provenance bundle");
    var url = HttpUrl.parse(origin);
    if (url == null
        || !url.username().isEmpty()
        || !url.password().isEmpty()
        || url.query() != null
        || url.fragment() != null
        || !url.encodedPath().equals("/"))
      throw new IllegalArgumentException("Invalid exported origin");
    origin = url.toString();
    RelayWork.identity(project);
    RelayWork.identity(principal);
    tasks = List.copyOf(tasks);
    if (tasks.isEmpty()
        || tasks.size() > 256
        || tasks.stream().map(row -> row.client() + ":" + row.id()).distinct().count()
            != tasks.size()) throw new IllegalArgumentException("Export1..256distinct known tasks");
  }

  public static LegacyProvenanceBundle read(Path file) throws IOException {
    if (Files.isSymbolicLink(file) || Files.size(file) > IngressCodec.MAX_BYTES)
      throw new IOException("Invalid private provenance bundle bound");
    var permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
    if (permissions.stream()
        .anyMatch(
            permission ->
                permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")))
      throw new IOException("Historical provenance bundle must be private to its owner");
    try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      return IngressCodec.exported(input.readNBytes(IngressCodec.MAX_BYTES + 1));
    }
  }
}
