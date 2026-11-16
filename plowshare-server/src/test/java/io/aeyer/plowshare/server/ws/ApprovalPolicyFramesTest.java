package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.approvals.ApprovalPolicies;
import io.aeyer.plowshare.server.approvals.ApprovalPolicy;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApprovalPolicyFramesTest {
  private final ApprovalPolicies policies = mock(ApprovalPolicies.class);
  private final Callers callers = mock(Callers.class);
  private final ApprovalPolicyFrames frames = new ApprovalPolicyFrames(policies, callers);
  private final Asking alice = new Asking("session", "alice");
  private final ApprovalPolicies.Capabilities capabilities =
      new ApprovalPolicies.Capabilities(List.of(ApprovalPolicy.Kind.COMMAND), false, false);

  @BeforeEach
  void capabilities() {
    when(policies.capabilities()).thenReturn(capabilities);
  }

  @Test
  void ownerComesFromTheCurrentSessionAndCannotBeSelectedByPayload() {
    when(policies.read("alice")).thenReturn(ApprovalPolicy.manual());
    assertEquals(
        new ApprovalPolicyFrames.View(ApprovalPolicy.manual(), capabilities),
        frames.frames().get(FrameTypes.APPROVAL_POLICY_GET).handle(Map.of(), alice).payload());
    verify(callers).requireSession("session", "alice");
    verify(policies).read("alice");
    assertThrows(
        CallerFault.class,
        () ->
            frames
                .frames()
                .get(FrameTypes.APPROVAL_POLICY_GET)
                .handle(Map.of("owner", "bob"), alice));
  }

  @Test
  void saveDecodesTheCompletePolicyBeforeBusinessLogic() {
    ApprovalPolicy saved = new ApprovalPolicy(1, ApprovalPolicy.Mode.MANUAL, null, List.of());
    when(policies.save("alice", ApprovalPolicy.manual())).thenReturn(saved);
    assertEquals(
        new ApprovalPolicyFrames.View(saved, capabilities),
        frames
            .frames()
            .get(FrameTypes.APPROVAL_POLICY_SAVE)
            .handle(Map.of("policy", manual()), alice)
            .payload());
    verify(policies).save("alice", ApprovalPolicy.manual());
  }

  @Test
  void missingFieldsCoercionsAndOwnerInjectionHaveNoPolicySideEffect() {
    for (Map<String, Object> invalid :
        List.of(
            Map.<String, Object>of(),
            Map.<String, Object>of("policy", manual(), "owner", "bob"),
            Map.<String, Object>of("policy", Map.of("mode", "MANUAL")))) {
      assertThrows(
          CallerFault.class,
          () -> frames.frames().get(FrameTypes.APPROVAL_POLICY_SAVE).handle(invalid, alice));
    }
    for (Object revision : List.of("0", 0.5, -1, true)) {
      var invalid = manual();
      invalid.put("revision", revision);
      assertThrows(
          CallerFault.class,
          () ->
              frames
                  .frames()
                  .get(FrameTypes.APPROVAL_POLICY_SAVE)
                  .handle(Map.of("policy", invalid), alice));
    }
    verifyNoInteractions(policies, callers);
  }

  @Test
  void anUnsignedSocketCannotManagePersonalPolicy() {
    assertThrows(
        CallerFault.class,
        () ->
            frames
                .frames()
                .get(FrameTypes.APPROVAL_POLICY_GET)
                .handle(Map.of(), new Asking("session")));
    verifyNoInteractions(policies, callers);
  }

  private static Map<String, Object> manual() {
    Map<String, Object> fields = new HashMap<>();
    fields.put("revision", 0);
    fields.put("mode", "MANUAL");
    fields.put("bot", null);
    fields.put("rules", List.of());
    return fields;
  }
}
