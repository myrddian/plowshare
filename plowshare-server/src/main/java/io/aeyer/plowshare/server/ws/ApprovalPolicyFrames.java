package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.approvals.ApprovalPolicies;
import io.aeyer.plowshare.server.approvals.ApprovalPolicy;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Policy owner comes from the authenticated socket, never a payload-selected account. */
@Component
public final class ApprovalPolicyFrames implements FrameArea {
  private final ApprovalPolicies policies;
  private final Callers callers;

  public ApprovalPolicyFrames(ApprovalPolicies policies, Callers callers) {
    this.policies = policies;
    this.callers = callers;
  }

  public record Save(ApprovalPolicy policy) {}

  public record View(ApprovalPolicy policy, ApprovalPolicies.Capabilities capabilities) {}

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.APPROVAL_POLICY_GET, this::read, FrameTypes.APPROVAL_POLICY_SAVE, this::save);
  }

  private Outcome read(Map<String, Object> payload, Asking asking) {
    if (payload != null && !payload.isEmpty())
      throw new CallerFault("Policy get takes no account or scope fields");
    String owner = owner(asking, FrameTypes.APPROVAL_POLICY_GET);
    return Outcome.ok(new View(policies.read(owner), policies.capabilities()));
  }

  private Outcome save(Map<String, Object> payload, Asking asking) {
    Save body = Payloads.exact(payload, Save.class, FrameTypes.APPROVAL_POLICY_SAVE);
    if (body.policy() == null) throw new CallerFault("Approval policy is required");
    String owner = owner(asking, FrameTypes.APPROVAL_POLICY_SAVE);
    return Outcome.ok(new View(policies.save(owner, body.policy()), policies.capabilities()));
  }

  private String owner(Asking asking, String operation) {
    String owner = asking.requireHandle(operation);
    callers.requireSession(asking.sessionId(), owner);
    return owner;
  }
}
