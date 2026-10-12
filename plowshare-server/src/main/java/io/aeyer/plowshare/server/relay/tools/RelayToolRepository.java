package io.aeyer.plowshare.server.relay.tools;

import java.time.Instant;
import java.util.UUID;

/**
 * Owning ledger. Submission and publication are one transaction; repeat identities never publish.
 */
public interface RelayToolRepository {
  /** Missing or legacy ancestry cannot authorize another Relay effect. */
  java.util.Optional<io.aeyer.plowshare.protocol.RelayCausation> ancestry(
      long projectId, io.aeyer.plowshare.server.llm.accounting.UsageAttribution owner);

  Stored submit(Intent intent);

  Stored finish(Intent intent, RelayToolCodec.Result result);

  java.util.Optional<Stored> find(long projectId, String account, UUID id);

  default java.util.Optional<Stored> find(long projectId, String account, UUID id, boolean lock) {
    if (lock) throw new IllegalStateException("Invocation locking is unavailable");
    return find(projectId, account, id);
  }

  record Intent(
      UUID id,
      long projectId,
      String fingerprint,
      RelayToolDefinition binding,
      RelayToolCodec.Request request,
      Instant occurredAt,
      io.aeyer.plowshare.protocol.RelayCausation causation,
      String agent,
      HostedInvocation hosted) {
    public Intent(
        UUID id,
        long projectId,
        String fingerprint,
        RelayToolDefinition binding,
        RelayToolCodec.Request request,
        Instant occurredAt,
        io.aeyer.plowshare.protocol.RelayCausation causation) {
      this(id, projectId, fingerprint, binding, request, occurredAt, causation, null, null);
    }

    public Intent {
      java.util.Objects.requireNonNull(id);
      java.util.Objects.requireNonNull(binding);
      java.util.Objects.requireNonNull(request);
      java.util.Objects.requireNonNull(occurredAt);
      java.util.Objects.requireNonNull(causation);
      if (projectId < 1
          || fingerprint == null
          || !fingerprint.matches("[0-9a-f]{64}")
          || !id.toString().equals(request.invocationId())
          || !binding.project().equals(request.project())
          || !binding.provider().equals(request.provider())
          || !binding.name().equals(request.tool())
          || causation.depth() < 1
          || causation.depth() >= 32)
        throw new IllegalArgumentException("Invalid tool invocation intent");
      if (hosted != null
          && (!hosted.owner().equals(request.account())
              || !hosted.executor().equals(binding.account())
              || !hosted.agent().equals(agent)
              || !io.aeyer.plowshare.protocol.HostedToolScopes.route(
                      hosted.binding(), hosted.generation())
                  .equals(binding.provider())))
        throw new IllegalArgumentException("Foreign hosted invocation selection");
      binding.validate(new RelayToolDefinition.Arguments(request.arguments()));
      if (RelayToolCodec.write(request).length() > 32768)
        throw new IllegalArgumentException("Tool request exceeds portable envelope bound");
    }
  }

  /**
   * Empty result means waiting or unknown; only a validated provider result completes the record.
   */
  record Stored(Intent intent, RelayToolCodec.Result result) {}
}
