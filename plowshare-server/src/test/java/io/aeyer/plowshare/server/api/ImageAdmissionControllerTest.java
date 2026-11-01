package io.aeyer.plowshare.server.api;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.plowshare.protocol.ImageAdmissions;
import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.images.ArtifactAdmissions;
import io.aeyer.plowshare.server.images.ImageStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ImageAdmissionControllerTest {
  @Test
  void profile_selects_invocation_authority_and_does_not_accept_owner_or_project_override()
      throws Exception {
    var admissions = mock(ArtifactAdmissions.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new ImageController(ImageStore.NONE, admissions))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    var invocation = UUID.randomUUID();
    var request =
        new ImageAdmissions.Request(invocation, 0, ImageAdmissions.requestId(invocation, 0));
    var receipt =
        new ImageAdmissions.Receipt(
            request,
            "atlas",
            "owner",
            "producer",
            "img_" + "a".repeat(32),
            "image/png",
            2,
            2,
            12,
            "a".repeat(64),
            "2026-10-22T00:00:00Z");
    when(admissions.admit(eq("producer"), eq(request), eq("image.png"), any())).thenReturn(receipt);
    var file = new MockMultipartFile("file", "image.png", "text/plain", new byte[] {1});
    mvc.perform(
            multipart("/v1/images")
                .file(file)
                .param("profile", ImageAdmissions.PROFILE)
                .param("invocation", invocation.toString())
                .param("ordinal", "0")
                .param("requestId", request.requestId().toString())
                .requestAttr(AuthFilter.HANDLE_ATTRIBUTE, "producer"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.owner").value("owner"));
    clearInvocations(admissions);
    mvc.perform(
            multipart("/v1/images")
                .file(file)
                .param("profile", ImageAdmissions.PROFILE)
                .param("invocation", invocation.toString())
                .param("ordinal", "0")
                .param("requestId", request.requestId().toString())
                .param("project", "foreign")
                .requestAttr(AuthFilter.HANDLE_ATTRIBUTE, "producer"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(admissions);
  }
}
