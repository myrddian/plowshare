package io.aeyer.plowshare.server.information;

import java.util.List;
import java.util.UUID;

/** Persisted input lineage and readability; selection policy remains in InformationJobs. */
public interface JobInformationRepository {
  record Input(String owner, UUID revision, InformationContext.Selection selection) {}

  boolean hasInputs(String log);

  void bind(String job, InformationContext context, List<UUID> revisions);

  List<UUID> inputsOf(String log);

  void inherit(String source, String target);

  List<Input> inputs(String job);

  boolean readable(UUID revision, String account, InformationContext.Selection selection);

  boolean logAllowed(String log, String account);
}
