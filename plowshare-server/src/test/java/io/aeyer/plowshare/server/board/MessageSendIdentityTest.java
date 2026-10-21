package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.agents.SendMessageTool.Request;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageSendIdentityTest {
  private final Request original =
      new Request(
          "reviewer", "exact\nbody", null, true, false, "persistent", "call", 30L, "project", null);

  @Test
  void identicalRetryRecoversButEveryChangedBusinessArgumentConflicts() {
    String fingerprint = MessageSendIdentity.fingerprint(original, false, null);
    var receipt = new BoardMessagingRepository.PriorSend("message", fingerprint);
    assertEquals("message", MessageSendIdentity.recover(receipt, fingerprint));
    for (Request changed :
        List.of(
            new Request(
                "other",
                original.body(),
                null,
                true,
                false,
                "persistent",
                "call",
                30L,
                "project",
                null),
            new Request(
                "reviewer",
                "changed body",
                null,
                true,
                false,
                "persistent",
                "call",
                30L,
                "project",
                null),
            new Request(
                "reviewer",
                original.body(),
                "incoming",
                true,
                false,
                "persistent",
                "call",
                30L,
                "project",
                null),
            new Request(
                "reviewer",
                original.body(),
                null,
                false,
                false,
                "persistent",
                "call",
                30L,
                "project",
                null),
            new Request(
                "reviewer",
                original.body(),
                null,
                true,
                true,
                "persistent",
                "call",
                30L,
                "project",
                null),
            new Request(
                "reviewer",
                original.body(),
                null,
                true,
                false,
                "task",
                "call",
                30L,
                "project",
                null),
            new Request(
                "reviewer",
                original.body(),
                null,
                true,
                false,
                "persistent",
                "call",
                31L,
                "project",
                null),
            new Request(
                "reviewer",
                original.body(),
                null,
                true,
                false,
                "persistent",
                "call",
                30L,
                "other",
                null),
            new Request(
                "reviewer",
                original.body(),
                null,
                true,
                false,
                "persistent",
                "call",
                30L,
                "project",
                "route"))) {
      assertThrows(
          CallerFault.class,
          () ->
              MessageSendIdentity.recover(
                  receipt, MessageSendIdentity.fingerprint(changed, false, null)));
    }
    assertThrows(
        CallerFault.class,
        () ->
            MessageSendIdentity.recover(
                receipt, MessageSendIdentity.fingerprint(original, true, null)));
    assertThrows(
        CallerFault.class,
        () ->
            MessageSendIdentity.recover(
                receipt, MessageSendIdentity.fingerprint(original, false, "failed")));
  }

  @Test
  void legacyReceiptRemainsIdentifiableButCannotAuthorizeBlindRecovery() {
    var refused =
        assertThrows(
            CallerFault.class,
            () ->
                MessageSendIdentity.recover(
                    new BoardMessagingRepository.PriorSend("retained-message", null),
                    "unverifiable"));
    assertTrue(refused.getMessage().contains("retained-message"));
  }

  private UsageAttribution owner(long turn, long step) {
    return UsageAttribution.project("actor", "42", UsageAttribution.Operation.AGENT_CHAT)
        .withExecution(
            UsageLineage.root("conversation"),
            UsageLineage.root("run"),
            UsageLineage.NONE,
            "sender",
            turn,
            step);
  }

  @Test
  void stepAndContinuationTurnDistinguishReusedProviderIdsWhileRetriedOwnershipIsStable() {
    String first = MessageSendIdentity.invocation(owner(1, 1), "same-provider-id");
    assertEquals(first, MessageSendIdentity.invocation(owner(1, 1), "same-provider-id"));
    assertNotEquals(first, MessageSendIdentity.invocation(owner(1, 2), "same-provider-id"));
    assertNotEquals(first, MessageSendIdentity.invocation(owner(2, 1), "same-provider-id"));
    assertThrows(
        CallerFault.class, () -> MessageSendIdentity.invocation(UsageAttribution.LEGACY, "call"));
  }
}
