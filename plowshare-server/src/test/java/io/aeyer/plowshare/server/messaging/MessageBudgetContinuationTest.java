package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.approvals.BudgetExtension;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.EventPayload;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.messaging.MessageService.Definitions;
import io.aeyer.plowshare.server.messaging.MessageService.Instance;
import io.aeyer.plowshare.server.messaging.MessageService.Voice;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MessageBudgetContinuationTest {
  @Test
  void budget_approval_queues_the_original_message_root_without_another_default_allowance() {
    var repository = mock(MessagingRepository.class);
    var allowances = mock(AllowanceRepository.class);
    var conversations = mock(ConversationStore.class);
    var firings = mock(FiringStore.class);
    var voice = mock(Voice.class);
    var definitions = mock(Definitions.class);
    Instance recipient =
        new Instance("ins_original", "owner", "project", "helper", "cnv_root", "task", true);
    when(repository.byConversation("cnv_root")).thenReturn(List.of(recipient));
    when(repository.lockHandling("ins_original")).thenReturn(List.of("bdm_original"));
    var service =
        new RelayMessaging(
            repository,
            allowances,
            conversations,
            firings,
            mock(MessageInboxes.class),
            UnitOfWork.NONE,
            voice,
            definitions,
            target -> {},
            Instant::now);
    var approval =
        new RunApproval(
            "apr_original",
            1,
            "cnv_root",
            "cnv_child",
            "owner",
            "helper",
            "server",
            List.of(),
            "",
            "Finish original message",
            RunApproval.ALLOWED,
            RunApproval.ONCE,
            null,
            "reviewer",
            Instant.EPOCH,
            null,
            Instant.EPOCH,
            null,
            null,
            new BudgetExtension(4, 2));
    assertTrue(service.continueApproved(approval, "Budget approved"));
    ArgumentCaptor<EventPayload.Message> wake = ArgumentCaptor.forClass(EventPayload.Message.class);
    verify(firings).oweMessage(eq("ins_original"), anyString(), wake.capture(), any());
    assertEquals("bdm_original", wake.getValue().wake().message());
    assertEquals("apr_original", wake.getValue().wake().approval());
    assertNull(
        wake.getValue().wake().delegate(),
        "Runtime restores the nested child from the original root");
    verify(repository, never()).addAllowance(anyString(), anyInt());
    verifyNoInteractions(allowances, conversations, definitions, voice);
  }
}
