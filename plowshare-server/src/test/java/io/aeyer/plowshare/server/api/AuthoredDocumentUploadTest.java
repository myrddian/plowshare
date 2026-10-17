package io.aeyer.plowshare.server.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.aeyer.plowshare.protocol.InformationContent;
import io.aeyer.plowshare.protocol.SchemaValidation;
import io.aeyer.plowshare.server.access.EffectiveProjectPermissions;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.documents.*;
import io.aeyer.plowshare.server.information.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Multipart delegates typed intent to the same admission service before creating a waiting job. */
class AuthoredDocumentUploadTest {
  InformationCatalogue catalogue;
  JobStore jobs;
  MockMvc mvc;
  final ProjectMembers members = mock(ProjectMembers.class);
  final io.aeyer.plowshare.server.requests.RequestProjects defaults =
      mock(io.aeyer.plowshare.server.requests.RequestProjects.class);

  @BeforeEach
  void setup() {
    catalogue = mock(InformationCatalogue.class);
    jobs = mock(JobStore.class);
    var controller =
        new DocumentController(
            mock(IngestService.class),
            jobs,
            mock(RetrievalService.class),
            mock(CitationStore.class),
            mock(DocumentStore.class),
            mock(Deliberation.class),
            new DocumentsProperties());
    controller.useInformationAccess(
        new InformationAccess(new EffectiveProjectPermissions(members), defaults));
    controller.useInformationCatalogue(catalogue);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  MockHttpServletRequestBuilder request() {
    return multipart("/v1/documents")
        .file(
            new MockMultipartFile(
                "file",
                "incident.md",
                "text/markdown",
                "Incident".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .requestAttr(AuthFilter.HANDLE_ATTRIBUTE, "author")
        .param("view", InformationContent.VIEW);
  }

  @Test
  void missing_receipt_or_typing_intent_and_duplicate_members_fail_before_admission()
      throws Exception {
    mvc.perform(request().param("typing", "{\"mode\":\"clear\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(request().param("expectedRevision", UUID.randomUUID().toString()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            request()
                .param("requestId", UUID.randomUUID().toString())
                .param("typing", "{\"mode\":\"clear\",\"mode\":\"inherit\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(catalogue, jobs);
  }

  @Test
  void metadata_refusal_retains_field_diagnostics_and_creates_no_waiting_job() throws Exception {
    var issue =
        new SchemaValidation.Issue("/severity", "/required", "required", "Required field missing");
    var invalid =
        new InformationMetadataFault(
            new SchemaValidation.Result(SchemaValidation.Status.INVALID, List.of(issue), false));
    when(catalogue.admitForSession(
            any(), any(), anyString(), any(), any(), any(), any(), any(), any()))
        .thenThrow(invalid);
    mvc.perform(
            request()
                .param("requestId", UUID.randomUUID().toString())
                .param(
                    "typing",
                    "{\"mode\":\"set\",\"version\":\"" + UUID.randomUUID() + "\",\"metadata\":{}}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("validation_failed"))
        .andExpect(jsonPath("$.detail").value("INFORMATION_METADATA_INVALID"))
        .andExpect(jsonPath("$.metadataValidation.issues[0].instancePointer").value("/severity"));
    verifyNoInteractions(jobs);
  }

  @Test
  void applicationUploadUsesTrustedProjectAndRetainsItsOriginalRequestId() throws Exception {
    String actor = "@service/" + UUID.randomUUID();
    UUID requestId = UUID.randomUUID(), revision = UUID.randomUUID();
    when(defaults.implicit(actor)).thenReturn(java.util.Optional.of("app"));
    when(members.role("app", actor))
        .thenReturn(
            java.util.Optional.of(io.aeyer.plowshare.server.archive.ProjectRole.CONTRIBUTOR));
    when(catalogue.admitForSession(
            any(), eq(requestId), anyString(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              InformationContext context = call.getArgument(0);
              assertEquals(actor, context.account());
              assertEquals(InformationContext.Scope.PROJECT, context.selection().scope());
              assertEquals("app", context.selection().project());
              assertFalse(context.selection().includeShared());
              return new InformationCatalogue.Admission(revision, UUID.randomUUID(), true);
            });
    when(jobs.submitInformation(anyString(), any(), any(), anyList(), any()))
        .thenReturn("job_WAIT");
    mvc.perform(
            request()
                .requestAttr(AuthFilter.HANDLE_ATTRIBUTE, actor)
                .param("requestId", requestId.toString()))
        .andExpect(status().isAccepted());
    verify(jobs)
        .submitInformation(
            anyString(),
            eq(io.aeyer.plowshare.protocol.Home.of("app")),
            any(),
            eq(List.of(revision)),
            any());
  }

  @Test
  void invalidExplicitProjectOrAmbiguousContextCannotBeReplacedByUploadDefault() throws Exception {
    when(defaults.implicit("author"))
        .thenThrow(new io.aeyer.plowshare.server.faults.CallerFault("ambiguous"));
    mvc.perform(request()).andExpect(status().isBadRequest());
    for (String project :
        List.of(
            " app ",
            "",
            io.aeyer.plowshare.server.access.BuiltInIdentities.GLOBAL.value().toString())) {
      mvc.perform(request().param("project", project)).andExpect(status().isBadRequest());
    }
    verifyNoInteractions(catalogue, jobs);
  }
}
