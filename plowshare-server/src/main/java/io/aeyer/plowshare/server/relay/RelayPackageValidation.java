package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.nio.file.*;

/** Source validation through Relay's own versioned boundary; it never invokes route handlers. */
public final class RelayPackageValidation {
  private RelayPackageValidation() {}

  public static void validate(Path root, RelayRouteProgram programs) {
    try {
      Path relay = root.resolve("Relay");
      if (!Files.exists(relay)) return;
      Path active = relay.resolve("active.json");
      if (Files.exists(active))
        for (String name : RelayRouteCodec.active(Files.readString(active))) {
          Path script = relay.resolve(name).resolve("routes.js");
          if (!Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS))
            throw new CallerFault("Active Relay package is missing routes.js");
          programs.manifest(
              RelayDeliveries.SourcePin.of(name + "/routes.js", Files.readString(script)));
        }
      Path policies = relay.resolve("topics.json");
      if (Files.exists(policies)) RelayRouteCodec.policies(Files.readString(policies));
      try (var files = Files.walk(relay)) {
        for (Path file : files.filter(Files::isRegularFile).toList()) {
          if (file.getFileName().toString().equals("manifest.json"))
            RelayRouteCodec.manifest(Files.readString(file));
        }
      }
    } catch (IOException invalid) {
      throw new CallerFault("Application Relay source could not be validated");
    }
  }
}
