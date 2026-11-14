package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.api.DocumentDetailResponse;
import io.aeyer.plowshare.server.api.RetrieveResponse;
import io.aeyer.plowshare.server.documents.ScopedDocumentReading;
import io.aeyer.plowshare.server.embedding.QueryEmbeddings.Origin;
import io.aeyer.plowshare.server.information.InformationAccess;
import io.aeyer.plowshare.server.information.InformationActor;
import io.aeyer.plowshare.server.information.InformationContext;
import io.aeyer.plowshare.server.information.InformationJobs;
import io.aeyer.plowshare.server.information.InformationReadSelection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScopedDocumentToolTest {
  private static final String SCOPE = UUID.randomUUID().toString();
  private static final UUID DOCUMENT = UUID.randomUUID();
  private final ScopedDocumentReading service = mock(ScopedDocumentReading.class);
  private final InformationJobs inputs = mock(InformationJobs.class);
  private final InformationAccess access = mock(InformationAccess.class);
  private final Home home = Home.of("execution");

  ScopedDocumentTool bound(String name, InformationActor actor, String boundary) {
    return new ScopedDocumentTool(name, () -> service)
        .forRun(access, actor, inputs, "log", boundary, () -> {});
  }

  @Test
  void outline_uses_the_acting_identity_and_server_boundary_with_separate_audit_ownership() {
    var actor = InformationActor.contained(UUID.randomUUID().toString(), "container");
    var tool = bound("document_outline", actor, "execution");
    when(service.outline(any(), eq(DOCUMENT)))
        .thenReturn(
            new DocumentDetailResponse(
                DOCUMENT, "source", "title", null, null, Instant.EPOCH, "fixture", 0, List.of()));
    assertTrue(
        tool.run("{\"scopes\":[\"" + SCOPE + "\"],\"document\":\"" + DOCUMENT + "\"}", home)
            .contains("source"));
    var selected = ArgumentCaptor.forClass(InformationReadSelection.class);
    verify(service).outline(selected.capture(), eq(DOCUMENT));
    assertEquals(actor.acting(), selected.getValue().reader().actor());
    assertEquals("container", selected.getValue().reader().audit().attribution());
    assertEquals("log", selected.getValue().reader().audit().log());
    assertEquals("execution", selected.getValue().reader().boundaryHome());
    assertEquals(List.of(SCOPE), selected.getValue().scopes());
    verify(inputs, times(2)).requireLog("log", "container");
    verifyNoInteractions(access);
  }

  @Test
  void retrieval_preserves_the_run_home_and_retained_call_identity() {
    var actor = InformationActor.of("reader");
    var context =
        new InformationContext("reader", InformationContext.Selection.project("execution"));
    when(access.forRun(actor, home)).thenReturn(context);
    when(service.retrieve(any(), eq(context), any(), eq("question"), eq(DOCUMENT), eq(5)))
        .thenAnswer(
            call -> {
              var origin = call.getArgument(2, Origin.class);
              assertEquals("log:document_retrieve:call-1", origin.invocation());
              origin.validity().run();
              return new RetrieveResponse("question", DOCUMENT, 5, List.of());
            });
    var tool = bound("document_retrieve", actor, null);
    tool.calledAs("call-1");
    assertTrue(
        tool.run(
                "{\"scopes\":[\""
                    + SCOPE
                    + "\"],\"question\":\"question\",\"document\":\""
                    + DOCUMENT
                    + "\"}",
                home)
            .contains("hits"));
    verify(service).retrieve(any(), eq(context), any(), eq("question"), eq(DOCUMENT), eq(5));
  }

  @Test
  void malformed_or_spoofed_inputs_never_reach_the_service() {
    var tool = bound("document_retrieve", InformationActor.of("reader"), null);
    tool.calledAs("call");
    String prefix = "{\"scopes\":[\"" + SCOPE + "\"],\"question\":\"q\"";
    for (String invalid :
        List.of(
            "{}",
            "null",
            prefix + ",\"limit\":\"5\"}",
            prefix + ",\"limit\":1.5}",
            prefix + ",\"scopes\":[\"global_read\"]}",
            prefix + ",\"document\":\"1-1-1-1-1\"}",
            prefix + ",\"actor\":\"admin\"}",
            prefix + ",\"boundaryHome\":null}",
            prefix + ",\"project\":\"other\"}",
            prefix + ",\"limit\":11}",
            prefix + ",\"conversation\":\"hidden\"}")) {
      assertFalse(tool.run(invalid, home).contains("hits"), invalid);
    }
    verifyNoInteractions(service, access);
  }

  @Test
  void execution_cancellation_and_missing_durable_identity_fail_closed() {
    var tool =
        new ScopedDocumentTool("document_outline", () -> service)
            .forRun(
                access,
                InformationActor.of("reader"),
                inputs,
                "log",
                null,
                () -> {
                  throw new IllegalStateException("Run ended");
                });
    assertThrows(IllegalStateException.class, () -> tool.run("{}", home));
    assertTrue(
        new ScopedDocumentTool("document_outline", () -> service)
            .run("{}", home)
            .contains("authenticated"));
    verifyNoInteractions(service);
  }
}
