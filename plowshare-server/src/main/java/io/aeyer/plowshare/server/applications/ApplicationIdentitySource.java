package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.protocol.ApplicationIdentity.Declaration;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Reads the admitted manifest through a bounded, unlinked source boundary; never reads a secret.
 */
public record ApplicationIdentitySource(Declaration declaration, String principal) {
  public static ApplicationIdentitySource read(Path root) {
    Path file = root.resolve("plowshare.json");
    try {
      if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        throw new CallerFault("Application identity source is unavailable");
      try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        byte[] bytes = input.readNBytes(65537);
        if (bytes.length > 65536) throw new CallerFault("Application identity source is oversized");
        String source =
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        return new ApplicationIdentitySource(
            ApplicationServiceManifest.declaration(source).orElse(null),
            ApplicationToolScopes.parse(source).executionAccount());
      }
    } catch (java.io.IOException | IllegalArgumentException invalid) {
      throw new CallerFault("Invalid Application identity source");
    }
  }
}
