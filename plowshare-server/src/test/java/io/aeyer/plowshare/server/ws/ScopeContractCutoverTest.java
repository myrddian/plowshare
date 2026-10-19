package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.frames.*;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.documents.*;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.fetch.PageFetcher;
import io.aeyer.plowshare.server.information.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Public frame admission over real catalogue policy and mocked owning persistence. */
class ScopeContractCutoverTest {
  final ProjectMembers members = mock(ProjectMembers.class);
  final InformationAccess access = new InformationAccess(new EffectiveProjectPermissions(members));
  final InformationCatalogueRepository records = mock(InformationCatalogueRepository.class);
  final InformationCatalogue catalogue =
      new InformationCatalogue(records, UnitOfWork.NONE, access, mock(InformationLogAccess.class));
  final DocumentStore documents = mock(DocumentStore.class);
  final JobStore jobs = mock(JobStore.class);
  final UUID revision = UUID.randomUUID();

  FrameRouter router() {
    var frames =
        new InformationFrames(
            catalogue,
            access,
            documents,
            mock(RetrievalService.class),
            mock(Deliberation.class),
            jobs,
            new DocumentsProperties(),
            mock(PageFetcher.class),
            mock(InformationContentTypes.class),
            mock(InformationContentSettings.class),
            mock(InformationNavigationService.class),
            mock(InformationInterchangeService.class));
    var router = new FrameRouter(frames.frames());
    router.useAuthorization(
        new ProjectAuthorization(
            mock(ResourceScopeRepository.class), members, mock(AdminStore.class)));
    return router;
  }

  Outcome request(
      FrameRouter router, String type, String account, String client, Map<String, Object> payload) {
    return router.route(
        new Envelope(UUID.randomUUID().toString(), type, Envelope.CURRENT_VERSION, payload),
        new Asking(client, account));
  }

  @Test
  void globalAudienceControlUsesTheAccountAndOwningPolicyAcrossClientLabels() {
    var shared = InformationContext.Selection.globalRead();
    var row =
        new InformationCatalogueRepository.Resource(
            UUID.randomUUID(),
            "global",
            "manual",
            "creator",
            null,
            null,
            BuiltInIdentities.GLOBAL_KNOWLEDGE,
            Optional.of(new AuthorityIds.AccountId(UUID.randomUUID())));
    when(records.lockResource(any(), eq(revision), eq(true)))
        .thenAnswer(
            call -> {
              InformationContext context = call.getArgument(0);
              if (!context.account().equals("admin"))
                throw new NotFoundFault("information is unavailable in this selection");
              assertEquals(shared, context.selection());
              return row;
            });
    when(records.row(revision))
        .thenReturn(
            new InformationCatalogueRepository.Snapshot(
                revision, row.id(), "document", "markdown", "active", "manual", "creator", 1, 0));
    var router = router();
    for (String client : List.of("sdk", "cli", "gui")) {
      var result =
          request(
              router,
              "information.unshare",
              "admin",
              client,
              Map.of(
                  "scope",
                  Map.of("kind", "global_read"),
                  "revision",
                  revision.toString(),
                  "requestId",
                  UUID.randomUUID().toString()));
      assertEquals(Code.OK, result.code());
    }
    for (String account : List.of("reader", "@service/reader")) {
      var result =
          request(
              router,
              "information.unshare",
              account,
              "gui",
              Map.of(
                  "scope",
                  Map.of("kind", "global_read"),
                  "revision",
                  revision.toString(),
                  "requestId",
                  UUID.randomUUID().toString()));
      assertNotEquals(Code.OK, result.code());
    }
    verify(records, times(3)).share(any(), eq(revision), eq(false));
    verifyNoInteractions(jobs, documents);
  }

  @Test
  void explicitSharedReadReachesScopedReaderForServiceWithoutProjectMembership() {
    var router = router();
    when(records.list(any(), anyInt(), anyInt(), any()))
        .thenAnswer(
            call -> {
              InformationContext context = call.getArgument(0);
              assertEquals("@service/reader", context.account());
              assertEquals(InformationContext.Selection.globalRead(), context.selection());
              return List.of();
            });
    var answer =
        request(
            router,
            "information.list",
            "@service/reader",
            "sdk",
            Map.of("scope", Map.of("kind", "global_read")));
    assertEquals(Code.OK, answer.code());
    verify(records).list(any(), eq(20), eq(0), isNull());
    verify(members, never()).requireRole(any(), any(), any());
    verify(records, never()).lockResource(any(), any(), anyBoolean());
    verifyNoInteractions(jobs);
  }

  @Test
  void malformedSelectorAndSharedIntakeRefuseBeforeAnyAdmissionOrJob() {
    var router = router();
    for (var scope :
        List.of(
            Map.of("kind", "global"),
            Map.of("kind", "project", "project", " app "),
            Map.of("kind", "global_read"))) {
      var result =
          request(
              router,
              "information.upload",
              "admin",
              "sdk",
              Map.of(
                  "scope",
                  scope,
                  "requestId",
                  UUID.randomUUID().toString(),
                  "name",
                  "manual",
                  "text",
                  "body"));
      assertNotEquals(Code.OK, result.code());
      assertNotEquals(Code.ACCEPTED, result.code());
    }
    verifyNoInteractions(records, jobs);
  }

  @Test
  void documentListAndItsCountUseTheSameAdmittedGeneralReadView() {
    var scoped = mock(DocumentStore.class);
    when(documents.scoped(eq(access), any()))
        .thenAnswer(
            call -> {
              InformationContext context = call.getArgument(1);
              assertEquals("@service/reader", context.account());
              assertEquals(InformationContext.Selection.globalRead(), context.selection());
              return scoped;
            });
    var frames =
        new DocumentFrames(
            mock(IngestService.class),
            jobs,
            mock(RetrievalService.class),
            mock(CitationStore.class),
            documents,
            mock(Deliberation.class),
            new DocumentsProperties());
    frames.useInformationAccess(access);
    var router = new FrameRouter(frames.frames());
    router.useAuthorization(
        new ProjectAuthorization(
            mock(ResourceScopeRepository.class), members, mock(AdminStore.class)));
    var answer =
        request(
            router,
            "document.list",
            "@service/reader",
            "sdk",
            Map.of("scope", Map.of("kind", "global_read")));
    assertEquals(Code.OK, answer.code());
    var listed =
        assertInstanceOf(
            io.aeyer.plowshare.server.api.DocumentListResponse.class, answer.payload());
    assertEquals(0, listed.total());
    assertTrue(listed.documents().isEmpty());
    verify(scoped)
        .page(null, io.aeyer.plowshare.server.requests.RequestedCorpusPage.DEFAULT_LISTED, 0);
    verify(scoped).count(null);
    verify(documents, never()).page(any(), anyInt(), anyInt());
    verifyNoInteractions(jobs);
  }

  @Test
  void typedNavigationCannotNormalizeAnInvalidExplicitProjectBeforeAdmission() {
    var result =
        request(
            router(),
            "information.documents.list",
            "admin",
            "sdk",
            Map.of("scope", Map.of("kind", "project", "project", " app ")));
    assertEquals(Code.BAD_REQUEST, result.code());
    verifyNoInteractions(records, jobs);
  }

  @Test
  void applicationOmissionIsResolvedBeforeConversationCreationAndCannotChooseFirstProject() {
    var defaults = mock(io.aeyer.plowshare.server.requests.RequestProjects.class);
    when(defaults.implicit("@service/app")).thenReturn(Optional.of("app"));
    var personal =
        new io.aeyer.plowshare.server.personal.PersonalSpaces(
            mock(io.aeyer.plowshare.server.personal.PersonalSpaceRepository.class),
            UnitOfWork.NONE,
            io.aeyer.plowshare.server.data.DataLayout.NONE);
    personal.useRequestProjects(defaults);
    var conversations = mock(ConversationStore.class);
    var home = io.aeyer.plowshare.protocol.Home.of("app");
    var opened = mock(ConversationRecord.class);
    when(opened.home()).thenReturn(home);
    when(opened.id()).thenReturn("cnv_OPEN");
    when(conversations.open(eq(home), any(), any(), eq("@service/app"))).thenReturn(opened);
    when(members.mayWork("app", "@service/app")).thenReturn(true);
    var handler =
        new ConversationOpenHandler(
            conversations,
            new io.aeyer.plowshare.server.api.ConversationsProperties(),
            mock(io.aeyer.plowshare.server.agents.LogStages.class),
            new io.aeyer.plowshare.server.agents.CallerAccess(
                new io.aeyer.plowshare.server.session.SessionRegistry(), members));
    handler.usePersonalSpaces(personal);
    var authorization =
        new ProjectAuthorization(
            mock(ResourceScopeRepository.class), members, mock(AdminStore.class));
    authorization.useRequestProjects(defaults);
    var router = new FrameRouter(Map.of("conversation.open", handler));
    router.useAuthorization(authorization);
    var answer = request(router, "conversation.open", "@service/app", "sdk", Map.of());
    assertEquals(Code.OK, answer.code());
    verify(members).requireRole("app", "@service/app", ProjectRole.CONTRIBUTOR);
    verify(conversations).open(eq(home), any(), any(), eq("@service/app"));
    clearInvocations(conversations);
    when(defaults.implicit("@service/app"))
        .thenThrow(new io.aeyer.plowshare.server.faults.CallerFault("ambiguous"));
    assertEquals(
        Code.BAD_REQUEST,
        request(router, "conversation.open", "@service/app", "sdk", Map.of()).code());
    verifyNoInteractions(conversations);
  }
}
