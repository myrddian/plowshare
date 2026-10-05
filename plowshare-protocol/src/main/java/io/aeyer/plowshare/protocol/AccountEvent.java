package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import java.util.UUID;

/** Explicit account invalidation contracts. Clients reconcile dropped hints with durable reads. */
public sealed interface AccountEvent extends ServerPush {
  @JsonProperty
  String kind();

  record InboxChanged(int unread) implements AccountEvent {
    public InboxChanged {
      if (unread < 0) throw new IllegalArgumentException("negative unread count");
    }

    @Override
    @JsonProperty
    public String kind() {
      return "inbox.changed";
    }
  }

  record InformationChanged(long sequence, UUID revision, int generation) implements AccountEvent {
    public InformationChanged {
      if (sequence < 1 || generation < 1)
        throw new IllegalArgumentException("invalid information event position");
      Objects.requireNonNull(revision);
    }

    @Override
    @JsonProperty
    public String kind() {
      return "information.changed";
    }
  }

  /**
   * Explicit recovery acknowledgment for account listeners; correlated with the durable receipt.
   */
  record OrchestrationResumed(String orchestration, UUID requestId) implements AccountEvent {
    public OrchestrationResumed {
      orchestration = ContractValues.identity(orchestration, "orchestration", 1024);
      Objects.requireNonNull(requestId, "requestId");
    }

    @Override
    @JsonProperty
    public String kind() {
      return "orchestration.resumed";
    }
  }

  record OrchestrationChanged(String orchestration, String state) implements AccountEvent {
    public OrchestrationChanged {
      orchestration = ContractValues.identity(orchestration, "orchestration", 1024);
      if (!java.util.Set.of(
              "running", "asking", "waiting", "finished", "failed", "capped", "cancelled")
          .contains(state)) throw new IllegalArgumentException("invalid orchestration state");
    }

    @Override
    @JsonProperty
    public String kind() {
      return "orchestration.changed";
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record OrchestrationRecorded(String root, int through, Integer settled) implements AccountEvent {
    public OrchestrationRecorded {
      root = ContractValues.identity(root, "root", 1024);
      if (through < 0 || settled != null && settled < 0)
        throw new IllegalArgumentException("invalid record position");
    }

    @Override
    @JsonProperty
    public String kind() {
      return "orchestration.recorded";
    }
  }

  record TodosChanged(String conversation) implements AccountEvent {
    public TodosChanged {
      conversation = ContractValues.identity(conversation, "conversation", 1024);
    }

    @Override
    @JsonProperty
    public String kind() {
      return "todos.changed";
    }
  }
}
