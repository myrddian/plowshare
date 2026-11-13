package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Native requester/return lifecycle. Callers supply repository-proven execution snapshots, never
 * transport assertions; owning export services enforce their channel and return publisher
 * authority.
 */
public interface NativeMessageExports {
  /** Exact retained run proof produced by the owning export repository in the publication unit. */
  record Source(
      UsageAttribution owner,
      String project,
      Origin origin,
      Optional<MessageService.Instance> participant,
      RelayCausation causation) {
    public Source(
        UsageAttribution owner,
        String project,
        Origin origin,
        Optional<MessageService.Instance> participant) {
      this(owner, project, origin, participant, null);
    }

    public Source {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(origin, "origin");
      participant = Objects.requireNonNull(participant, "participant");
      if (Home.of(project).isGlobal()
          || owner.status() != UsageAttribution.Status.ATTRIBUTED
          || owner.scope() != UsageAttribution.Scope.PROJECT
          || owner.accountHandle() == null
          || owner.agentName() == null
          || owner.conversations().id() == null
          || owner.runs().id() == null
          || owner.turnOrdinal() == null
          || owner.turnOrdinal() < 1
          || (origin != Origin.TURN && origin != Origin.MESSAGE)
          || origin == Origin.MESSAGE && participant.isEmpty())
        throw new IllegalArgumentException(
            "An export requires an exact supported execution source");
      participant.ifPresent(
          instance -> {
            if (!instance.account().equals(owner.accountHandle())
                || !instance.project().equals(project)
                || !instance.agent().equals(owner.agentName())
                || !instance.conversation().equals(owner.conversations().id()))
              throw new IllegalArgumentException(
                  "Export participant differs from its execution proof");
          });
    }
  }

  /** Immutable native thread endpoints; return calls recheck these against the original route. */
  record Export(
      String requestMessage, String requester, String external, String account, String project) {
    public Export {
      requestMessage = MessagePayload.identifier(requestMessage, "bdm_");
      requester = MessagePayload.identifier(requester, "ins_");
      external = MessagePayload.identifier(external, "ins_");
      account = RelayPort.identity(account);
      if (Home.of(project).isGlobal() || requester.equals(external))
        throw new IllegalArgumentException("Invalid native export endpoints");
    }
  }

  /** Retains a non-runnable external request atomically; equal source-event retries reuse it. */
  Export export(Source source, String channel, UUID sourceEvent, String body);

  record Return(UUID requestId, String body, boolean finalReply, RelayCausation causation) {
    public Return {
      Objects.requireNonNull(requestId);
      body = RelayPort.text(body);
    }
  }

  /**
   * Retains one correlated native reply. Final settles only the exported request. Native source
   * activity, cancellation, archive, approval and ordinary message ordering remain authoritative.
   */
  sealed interface Attempt permits Accepted, Unavailable, Capacity {}

  record Accepted(String message) implements Attempt {
    public Accepted {
      message = MessagePayload.identifier(message, "bdm_");
    }
  }

  enum UnavailableReason {
    CLOSED,
    INPUTS_WITHHELD,
    BODY_LIMIT
  }

  record Unavailable(UnavailableReason reason) implements Attempt {
    public Unavailable {
      Objects.requireNonNull(reason);
    }
  }

  record Capacity() implements Attempt {}

  /** Expected prewrite refusals are values, so an outer owner can settle its debt atomically. */
  Attempt tryReturnReply(Export retained, Return returned);

  String returnReply(Export retained, Return returned);

  /** Explicit compatibility seam for noncausal owning callers; external returns supply ancestry. */
  default String returnReply(Export retained, UUID request, String body, boolean finalReply) {
    return returnReply(retained, new Return(request, body, finalReply, null));
  }
}
