package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** SYSTEM identity locates authority; only its live intrinsic grant authorizes the bounded work. */
@Component
public final class BoundSystemOperations implements SystemOperations {
  private final IdentityRepository identities;
  private final ScopedGrantRepository grants;
  private final ScopedAuthority authority;
  private final SystemOperationRecords records;
  private final InferenceCalls calls;

  public BoundSystemOperations(
      IdentityRepository identities,
      ScopedGrantRepository grants,
      ScopedAuthority authority,
      SystemOperationRecords records,
      InferenceCalls calls) {
    this.identities = Objects.requireNonNull(identities);
    this.grants = Objects.requireNonNull(grants);
    this.authority = Objects.requireNonNull(authority);
    this.records = Objects.requireNonNull(records);
    this.calls = Objects.requireNonNull(calls);
  }

  @Override
  public Work admit(
      Purpose purpose, Home home, AgentDefinition definition, String binding, Runnable validity) {
    return admit(purpose, home, null, definition, binding, validity);
  }

  @Override
  public Work admit(
      Purpose purpose,
      Home home,
      AuthorityIds.ScopeId selected,
      AgentDefinition definition,
      String binding,
      Runnable validity) {
    Objects.requireNonNull(purpose);
    Objects.requireNonNull(home);
    Objects.requireNonNull(binding);
    Objects.requireNonNull(validity);
    SystemModelTasks.requireModelOnly(definition);
    if (definition.nativeOnly())
      throw new CallerFault("SYSTEM model purpose cannot execute a script");
    boolean deployment =
        switch (purpose) {
          case STUCK_ADVISOR, CALL_VALIDATOR, COMMAND_JUDGE, SCHEDULE_READER -> true;
          default -> false;
        };
    var scope =
        selected != null
            ? identities.scope(selected)
            : deployment
                ? identities.scope(BuiltInIdentities.ADMINISTRATION)
                : home.isGlobal()
                    ? identities.scope(BuiltInIdentities.GLOBAL_KNOWLEDGE)
                    : identities.project(home.project());
    if (!home.isGlobal() && !scope.equals(identities.project(home.project()))) throw denied();
    var recipient = GrantRecipient.account(BuiltInIdentities.SYSTEM);
    var support =
        grants.live(recipient).stream()
            .filter(g -> g.role() == StandardRole.SYSTEM)
            .filter(
                g ->
                    EnumSet.of(
                            AuthorityAction.READ,
                            AuthorityAction.WRITE,
                            AuthorityAction.CONVERSATION_CREATE,
                            AuthorityAction.RUN_MODEL)
                        .stream()
                        .allMatch(
                            action -> authority.allowsUsing(recipient, scope, action, g.id())))
            .findFirst()
            .orElseThrow(BoundSystemOperations::denied);
    var envelope =
        new SystemOperationRecords.Envelope(
            UUID.randomUUID(),
            purpose,
            scope.id(),
            support.id(),
            definition.name(),
            definition.model(),
            hash(binding));
    Runnable check =
        () -> {
          validity.run();
          if (!scope.equals(identities.scope(scope.id()))
              || !home.isGlobal() && !scope.equals(identities.project(home.project())))
            throw denied();
          for (var action :
              EnumSet.of(
                  AuthorityAction.READ,
                  AuthorityAction.WRITE,
                  AuthorityAction.CONVERSATION_CREATE,
                  AuthorityAction.RUN_MODEL))
            if (!authority.allowsUsing(recipient, scope, action, support.id())) throw denied();
        };
    check.run();
    records.retain(envelope);
    return new Work() {
      public UUID id() {
        return envelope.id();
      }

      public Home home() {
        return home;
      }

      public void require() {
        check.run();
        records.require(envelope);
      }

      public void requireLog(Home target, String agent) {
        require();
        if (!home.equals(target) || !envelope.agent().equals(agent)) throw denied();
      }

      public void requireTask(Home target, AgentDefinition worker, String task) {
        require();
        SystemModelTasks.requireModelOnly(worker);
        if (!home.equals(target)
            || !envelope.agent().equals(worker.name())
            || !envelope.model().equals(worker.model())
            || !envelope.bindingHash().equals(hash(task))) throw denied();
      }

      public CallAuthorization claimed(ChatRequest request, Budget.Claim claim) {
        if (!request.tools().isEmpty() || !request.specifier().equals(envelope.model()))
          throw denied();
        require();
        var captured = calls.claimed(id(), claim, this::require);
        var inputHash = hash(request.messages().toString());
        return new CallAuthorization() {
          public void require() {
            captured.require();
          }

          public InferenceBinding binding() {
            return captured.binding();
          }

          public void requireRequest(ChatRequest asked) {
            require();
            if (!asked.specifier().equals(envelope.model())
                || !asked.tools().isEmpty()
                || !hash(asked.messages().toString()).equals(inputHash)) throw denied();
          }
        };
      }
    };
  }

  private static String hash(String binding) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(binding.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static CallerFault denied() {
    return new CallerFault("SYSTEM operation exceeds its retained purpose or authority");
  }
}
