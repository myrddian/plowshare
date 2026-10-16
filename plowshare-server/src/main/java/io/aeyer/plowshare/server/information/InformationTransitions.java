package io.aeyer.plowshare.server.information;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Authorises one prepared information mutation and reconciles its durable gate receipt. */
public interface InformationTransitions {
  record Prepared<T extends InformationGateResult>(
      Supplier<T> transition, io.aeyer.plowshare.protocol.InformationContent.Candidate candidate) {
    public Prepared {
      java.util.Objects.requireNonNull(transition);
    }
  }

  <T extends InformationGateResult> T execute(
      InformationContext context,
      UUID request,
      String operation,
      InformationGateIdentity identity,
      List<UUID> sources,
      String session,
      Class<T> resultType,
      Supplier<T> transition);

  /**
   * Resolves a completed receipt before preparing/validating new work, then gates its exact
   * candidate.
   */
  <T extends InformationGateResult> T executePrepared(
      InformationContext context,
      UUID request,
      String operation,
      InformationGateIdentity identity,
      List<UUID> sources,
      String session,
      Class<T> resultType,
      Supplier<Prepared<T>> prepare);
}
