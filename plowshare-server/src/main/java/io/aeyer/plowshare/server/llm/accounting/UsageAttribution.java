package io.aeyer.plowshare.server.llm.accounting;

import java.util.Objects;

/**
 * Server-validated ownership carried across queues and async work on the request itself. A pool is
 * capacity, not an owner. These values are metadata, never prompts or credentials. Construct this
 * at an authorized entry point; structural validation does not grant access.
 */
public record UsageAttribution(
    String accountHandle,
    String projectId,
    Scope scope,
    UsageLineage conversations,
    UsageLineage runs,
    UsageLineage orchestrations,
    String agentName,
    Long turnOrdinal,
    Long stepOrdinal,
    Operation operation,
    Status status) {

  public static final UsageAttribution LEGACY =
      new UsageAttribution(
          null,
          null,
          Scope.GLOBAL,
          UsageLineage.NONE,
          UsageLineage.NONE,
          UsageLineage.NONE,
          null,
          null,
          null,
          Operation.LEGACY_UNKNOWN,
          Status.LEGACY_UNATTRIBUTED);

  public UsageAttribution {
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(conversations, "conversations");
    Objects.requireNonNull(runs, "runs");
    Objects.requireNonNull(orchestrations, "orchestrations");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(status, "status");
    if (scope == Scope.PROJECT) {
      UsageLineage.requireId(projectId);
    } else if (projectId != null) {
      throw new IllegalArgumentException("global usage cannot name a project");
    }
    if (status == Status.ATTRIBUTED) {
      UsageLineage.requireId(accountHandle);
    } else if (accountHandle != null) {
      throw new IllegalArgumentException("system or legacy usage cannot claim an account");
    }
    if (agentName != null) {
      UsageLineage.requireId(agentName);
    }
    if ((turnOrdinal != null && (turnOrdinal < 0 || conversations.id() == null))
        || (stepOrdinal != null && (stepOrdinal < 0 || runs.id() == null))) {
      throw new IllegalArgumentException("usage ordinals need a nonnegative value and identity");
    }
    if (status == Status.LEGACY_UNATTRIBUTED) {
      if (scope != Scope.GLOBAL
          || agentName != null
          || conversations.id() != null
          || runs.id() != null
          || orchestrations.id() != null
          || operation != Operation.LEGACY_UNKNOWN) {
        throw new IllegalArgumentException("legacy usage must not claim ownership");
      }
    } else if (operation == Operation.LEGACY_UNKNOWN) {
      throw new IllegalArgumentException("owned usage needs an explicit operation");
    }
  }

  public static UsageAttribution project(String account, String project, Operation operation) {
    return owned(account, project, Scope.PROJECT, operation, Status.ATTRIBUTED);
  }

  public static UsageAttribution global(String account, Operation operation) {
    return owned(account, null, Scope.GLOBAL, operation, Status.ATTRIBUTED);
  }

  public static UsageAttribution system(String project, Operation operation) {
    return owned(
        null, project, project == null ? Scope.GLOBAL : Scope.PROJECT, operation, Status.SYSTEM);
  }

  private static UsageAttribution owned(
      String account, String project, Scope scope, Operation operation, Status status) {
    return new UsageAttribution(
        account,
        project,
        scope,
        UsageLineage.NONE,
        UsageLineage.NONE,
        UsageLineage.NONE,
        null,
        null,
        null,
        operation,
        status);
  }

  public UsageAttribution withExecution(
      UsageLineage conversation,
      UsageLineage run,
      UsageLineage orchestration,
      String agent,
      Long turn,
      Long step) {
    return new UsageAttribution(
        accountHandle,
        projectId,
        scope,
        conversation,
        run,
        orchestration,
        agent,
        turn,
        step,
        operation,
        status);
  }

  public UsageAttribution withOperation(Operation next) {
    return new UsageAttribution(
        accountHandle,
        projectId,
        scope,
        conversations,
        runs,
        orchestrations,
        agentName,
        turnOrdinal,
        stepOrdinal,
        next,
        status);
  }

  /** A side call keeps the originating execution while naming the model actor and operation. */
  public UsageAttribution forOperation(Operation next, String agent) {
    if (status == Status.LEGACY_UNATTRIBUTED) {
      return this;
    }
    return withOperation(next)
        .withExecution(conversations, runs, orchestrations, agent, turnOrdinal, stepOrdinal);
  }

  public enum Scope {
    PROJECT,
    GLOBAL
  }

  public enum Status {
    ATTRIBUTED,
    SYSTEM,
    LEGACY_UNATTRIBUTED
  }

  public enum Operation {
    AGENT_CHAT,
    FOLD,
    REVIEW,
    HOOK_MODEL,
    DOCUMENT_SUMMARY,
    LEARNING,
    DIGEST,
    NAVIGATION,
    SCHEDULE_READ,
    EMBEDDING_WRITE,
    EMBEDDING_QUERY,
    EMBEDDING_REPAIR,
    LEGACY_UNKNOWN
  }
}
