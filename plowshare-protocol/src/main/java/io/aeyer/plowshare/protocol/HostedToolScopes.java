package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Durable owner assignments. A configured executor is distinct from both owner and credential
 * subject.
 */
public final class HostedToolScopes {
  private HostedToolScopes() {}

  public static final Set<String> WORKER_OPERATIONS =
      Set.of(
          "tool.scope.worker.register",
          "tool.scope.worker.renew",
          "tool.scope.worker.status",
          "tool.scope.worker.credential",
          "tool.scope.worker.publish",
          "tool.scope.worker.consume",
          "tool.scope.worker.ack",
          "tool.scope.worker.read");

  public enum Action {
    ENABLE,
    DISABLE,
    DELETE
  }

  public enum Purpose {
    DISCOVERY,
    EXECUTION
  }

  public record Definition(
      String scope,
      String provider,
      String prefix,
      List<String> grants,
      List<String> agents,
      int leaseSeconds,
      String credentialProject,
      UUID connection,
      @JsonProperty(required = true) String subject,
      String executor) {
    public Definition {
      var checked =
          new ToolScopes.Connect(
              credentialProject, scope, provider, prefix, grants, agents, leaseSeconds);
      grants = checked.grants();
      agents = checked.agents();
      new ProviderCredentials.Target(credentialProject, connection, subject);
      if (executor == null
          || !executor.matches("@service/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"))
        throw new IllegalArgumentException("Choose an exact configured executor principal");
      if (grants.stream().anyMatch(g -> !g.equals("*") && !g.startsWith(prefix)))
        throw new IllegalArgumentException("Hosted grants must belong to their prefix");
    }
  }

  public record Binding(
      UUID binding,
      String project,
      String owner,
      long generation,
      boolean enabled,
      boolean deleted,
      boolean application,
      Definition definition,
      String credentialSubject,
      ToolScopes.Connection route) {
    public Binding {
      Objects.requireNonNull(binding);
      HostedToolScopes.project(project);
      ProviderCredentials.subject(owner);
      Objects.requireNonNull(definition);
      ProviderCredentials.subject(credentialSubject);
      Objects.requireNonNull(route);
      if (generation < 1
          || generation > 9007199254740991L
          || deleted && enabled
          || !route.project().equals(project)
          || !route.account().equals(definition.executor())
          || !route.scope().equals(definition.scope())
          || !route.sourceProvider().equals(definition.provider())
          || !route.prefix().equals(definition.prefix())
          || !route.grants().equals(definition.grants())
          || !route.agents().equals(definition.agents())
          || route.leaseSeconds() != definition.leaseSeconds()
          || !route.provider().equals(HostedToolScopes.route(binding, generation)))
        throw new IllegalArgumentException("Inconsistent hosted binding");
      if (!credentialSubject.equals(definition.subject() == null ? owner : definition.subject()))
        throw new IllegalArgumentException("Foreign hosted credential subject");
    }
  }

  public record Save(
      String project,
      UUID binding,
      UUID requestId,
      @JsonProperty(required = true) Long expectedGeneration,
      Definition definition) {
    public Save {
      HostedToolScopes.project(project);
      Objects.requireNonNull(binding);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(definition);
      if (expectedGeneration != null) HostedToolScopes.generation(expectedGeneration);
    }
  }

  public record Change(
      String project, UUID binding, UUID requestId, long expectedGeneration, Action action) {
    public Change {
      HostedToolScopes.project(project);
      Objects.requireNonNull(binding);
      Objects.requireNonNull(requestId);
      HostedToolScopes.generation(expectedGeneration);
      Objects.requireNonNull(action);
    }
  }

  public record Query(String project) {
    public Query {
      HostedToolScopes.project(project);
    }
  }

  public record Status(String project, UUID binding) {
    public Status {
      HostedToolScopes.project(project);
      Objects.requireNonNull(binding);
    }
  }

  public record ReceiptQuery(String project, UUID requestId) {
    public ReceiptQuery {
      HostedToolScopes.project(project);
      Objects.requireNonNull(requestId);
    }
  }

  public record Receipt(UUID requestId, String operation, Binding binding) {
    public Receipt {
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(binding);
      if (!Set.of("tool.scope.host.save", "tool.scope.host.change").contains(operation))
        throw new IllegalArgumentException("Invalid hosted mutation receipt");
    }
  }

  public record Register(UUID binding, UUID requestId, long expectedGeneration) {
    public Register {
      Objects.requireNonNull(binding);
      Objects.requireNonNull(requestId);
      HostedToolScopes.generation(expectedGeneration);
    }
  }

  public record WorkerQuery(UUID binding) {
    public WorkerQuery {
      Objects.requireNonNull(binding);
    }
  }

  public record Worker(UUID binding, UUID registration) {
    public Worker {
      Objects.requireNonNull(binding);
      Objects.requireNonNull(registration);
    }
  }

  public record Registration(
      Binding binding, UUID registration, OffsetDateTime registeredAt, OffsetDateTime expiresAt) {
    public Registration {
      Objects.requireNonNull(binding);
      Objects.requireNonNull(registration);
      Objects.requireNonNull(registeredAt);
      Objects.requireNonNull(expiresAt);
      if (!registeredAt.isBefore(expiresAt))
        throw new IllegalArgumentException("Invalid worker lease");
    }
  }

  public record WorkerStatus(
      UUID binding, @JsonProperty(required = true) Registration registration) {
    public WorkerStatus {
      Objects.requireNonNull(binding);
      if (registration != null && !binding.equals(registration.binding().binding()))
        throw new IllegalArgumentException("Foreign worker status");
    }
  }

  public record CredentialRequest(
      UUID binding,
      UUID registration,
      Purpose purpose,
      @JsonProperty(required = true) UUID invocation) {
    public CredentialRequest {
      new Worker(binding, registration);
      Objects.requireNonNull(purpose);
      if ((purpose == Purpose.EXECUTION) != (invocation != null))
        throw new IllegalArgumentException("Execution requires an exact invocation");
    }
  }

  /**
   * The only secret-bearing reply: an upstream header, never refresh/client secrets or a verifier.
   */
  public record Credential(
      UUID binding,
      long generation,
      UUID registration,
      Purpose purpose,
      @JsonProperty(required = true) UUID invocation,
      ProviderCredentials.Connection connection,
      String subject,
      UUID credentialGeneration,
      String header,
      String value) {
    public Credential {
      new CredentialRequest(binding, registration, purpose, invocation);
      HostedToolScopes.generation(generation);
      Objects.requireNonNull(connection);
      ProviderCredentials.subject(subject);
      Objects.requireNonNull(credentialGeneration);
      if (!Set.of("Authorization", "X-API-Key", "Api-Key").contains(header)
          || value == null
          || value.isEmpty()
          || value.length() > 8200
          || value.chars().anyMatch(c -> c < 32 || c > 126))
        throw new IllegalArgumentException("Invalid upstream credential header");
    }

    @Override
    public String toString() {
      return "HostedCredential[binding=" + binding + ", value=<redacted>]";
    }
  }

  public enum ResultState {
    COMPLETED,
    REJECTED,
    UNKNOWN
  }

  public record Read(UUID binding, UUID registration, UUID invocation) {
    public Read {
      new Worker(binding, registration);
      Objects.requireNonNull(invocation);
    }
  }

  /** Only a verified original result can settle the executor's journal. */
  public record Reconciliation(
      UUID binding,
      UUID registration,
      UUID invocation,
      boolean verified,
      ResultState state,
      String text) {
    public Reconciliation {
      new Read(binding, registration, invocation);
      Objects.requireNonNull(state);
      if (text == null || text.length() > 65536)
        throw new IllegalArgumentException("Invalid hosted result text");
    }
  }

  public record Publish(UUID binding, UUID registration, RelayPort.Publish publication) {
    public Publish {
      new Worker(binding, registration);
      Objects.requireNonNull(publication);
    }
  }

  public record Consume(UUID binding, UUID registration, RelayPort.Consume consumption) {
    public Consume {
      new Worker(binding, registration);
      Objects.requireNonNull(consumption);
    }
  }

  public record Ack(UUID binding, UUID registration, RelayPort.Ack acknowledgement) {
    public Ack {
      new Worker(binding, registration);
      Objects.requireNonNull(acknowledgement);
    }
  }

  public static String route(UUID binding, long generation) {
    Objects.requireNonNull(binding);
    HostedToolScopes.generation(generation);
    return "host-" + RelayPort.hash(binding + "/" + generation).substring(0, 32);
  }

  public static boolean hosted(String route) {
    return route != null && route.matches("host-[0-9a-f]{32}");
  }

  public static void generation(long value) {
    if (value < 1 || value > 9007199254740991L)
      throw new IllegalArgumentException("Invalid hosted generation");
  }

  public static void project(String value) {
    RelayPort.identity(value);
    if (value.startsWith("client:"))
      throw new IllegalArgumentException("Instance projects cannot own durable hosts");
    // ADR 0042, list (c): a durable tool host is reachable by its route for as long as it exists,
    // independently of who created it, which is precisely an address nobody has to name again.
    if (ContainedProjects.name(value))
      throw new IllegalArgumentException("Contained projects cannot own durable hosts");
  }
}
