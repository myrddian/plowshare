package io.aeyer.plowshare.a2a.relay;

import io.aeyer.plowshare.a2a.Ingress;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Explicit local migration of private transport evidence. Public native inspection verifies every
 * owned task tuple; neither imported source nor operator labels grant native execution authority.
 */
public final class LegacyProvenanceImport {
  private LegacyProvenanceImport() {}

  private record Verified(IngressJournal.Imported source, IngressJournal.Projection task) {}

  public static int apply(
      IngressConfiguration config,
      LegacyProvenanceBundle bundle,
      RelayIngressBridge bridge,
      IngressJournal journal,
      Supplier<Instant> clock)
      throws IOException {
    if (config.principal() == null
        || !config.principal().equals(bundle.principal())
        || !config.origin().equals(bundle.origin())
        || !config.binding().project().equals(bundle.project()))
      throw new IOException(
          "Historical export origin, project or principal differs from the verified receiving binding");
    var verified = new ArrayList<Verified>();
    var originals = new HashSet<String>();
    var externalIds = new HashSet<UUID>();
    // Validate all rows before starting an import. A definite foreign/conflicting bundle leaves
    // no migration marker or partly installed source evidence behind.
    for (var row : bundle.tasks()) {
      if (!config.receiver().clients().containsKey(row.client())
          || !config.binding().agent().equals(row.agent()))
        throw new IOException("Historical task client or agent differs from the receiving binding");
      var task =
          bridge.inspectTask(new Ingress.Id(config.binding().project(), row.client(), row.id()));
      var header = task.task();
      if (!header.id().equals(row.id().toString())
          || !header.context().equals(row.context().toString())
          || !header.agent().equals(row.agent())
          || !header.message().equals(row.message())
          || !header.createdAt().equals(row.createdAt()))
        throw new IOException(
            "Historical task identity does not match current scoped native inspection");
      UUID external =
          row.originalContextKnown() && row.source() != null
              ? externalRequest(
                  config.binding().project(), row.agent(), row.client(), row.source().messageId())
              : null;
      var source =
          new IngressJournal.Imported(
              UUID.fromString(header.requestId()),
              external,
              row.client(),
              row.id(),
              row.context(),
              row.agent(),
              row.message(),
              row.createdAt(),
              row.source(),
              row.originalContextKnown(),
              clock.get());
      source = journal.validateImport(source, task);
      if (!originals.add(source.client() + ":" + source.originalRequest())
          || source.externalRequest() != null && !externalIds.add(source.externalRequest()))
        throw new IOException("Historical bundle contains ambiguous request identities");
      verified.add(new Verified(source, task));
    }
    String hash = RelayPort.hash(IngressCodec.write(bundle));
    journal.beginImport(hash);
    for (var value : verified) journal.importSource(value.source(), value.task());
    journal.completeImport(hash);
    return verified.size();
  }

  static UUID externalRequest(String project, String agent, String client, String message)
      throws IOException {
    return UUID.nameUUIDFromBytes(
        IngressCodec.write(List.of(project, agent, client, message))
            .getBytes(StandardCharsets.UTF_8));
  }
}
