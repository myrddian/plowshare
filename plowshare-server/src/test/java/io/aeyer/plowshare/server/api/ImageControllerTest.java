package io.aeyer.plowshare.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.images.ImageStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code POST /v1/images}: the producer, and the three refusals it answers with three different
 * statuses.
 *
 * <p>Against a real {@link ImageStore} over a {@link TempDir} rather than a mock, because what this
 * route is for is turning bytes into a UID and a mock would assert that the controller called a
 * method rather than that an uploader got an id they can name. The store's own refusals are tested
 * in {@code ImageStoreTest}; what is tested here is that each one reaches the caller as the status
 * whose remedy matches it.
 */
class ImageControllerTest {

  private static final byte[] PNG = {
    (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4
  };

  private ImageStore images;
  private MockMvc mvc;

  @BeforeEach
  void setUp(@TempDir Path tmp) {
    images =
        new ImageStore(
            home -> home.isGlobal() ? tmp.resolve("global") : tmp.resolve(home.project()), 64);
    mvc =
        MockMvcBuilders.standaloneSetup(new ImageController(images))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  private static MockMultipartFile upload(String name, byte[] bytes) {
    return new MockMultipartFile("file", name, "image/png", bytes);
  }

  @Test
  void an_upload_answers_with_the_uid_an_agent_will_be_handed() throws Exception {
    mvc.perform(multipart("/v1/images").file(upload("red.png", PNG)).param("project", "atlas"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(ImageStore.idFor(PNG)))
        .andExpect(jsonPath("$.project").value("atlas"))
        .andExpect(jsonPath("$.format").value("png"))
        .andExpect(jsonPath("$.filename").value("red.png"))
        .andExpect(jsonPath("$.bytes").value(PNG.length));

    assertEquals(
        ImageStore.idFor(PNG),
        images.find(Home.of("atlas"), ImageStore.idFor(PNG)).orElseThrow().id());
  }

  /** Trusted standalone fixtures without account wiring retain the global namespace. */
  @Test
  void an_upload_with_no_project_is_the_global_tier() throws Exception {
    mvc.perform(multipart("/v1/images").file(upload("red.png", PNG)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.project").doesNotExist());

    assertEquals(1, images.find(Home.global(), ImageStore.idFor(PNG)).stream().count());
  }

  /**
   * The three refusals, and the point is that they are three.
   *
   * <p>The remedies differ — send something, convert it, shrink it — so a caller told 415 about a
   * good PNG that was merely too big would convert it, repeatedly, and be refused identically every
   * time.
   */
  @Test
  void an_empty_upload_is_a_bad_request() throws Exception {
    mvc.perform(multipart("/v1/images").file(upload("nothing.png", new byte[0])))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("image_not_stored"));
  }

  @Test
  void bytes_that_are_not_an_image_are_an_unsupported_media_type() throws Exception {
    mvc.perform(
            multipart("/v1/images")
                .file(upload("notes.txt", "hello".getBytes(StandardCharsets.UTF_8))))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("image/png")));
  }

  @Test
  void an_image_over_the_cap_is_a_payload_too_large() throws Exception {
    byte[] big = new byte[128];
    System.arraycopy(PNG, 0, big, 0, 8);

    mvc.perform(multipart("/v1/images").file(upload("big.png", big)))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(
            jsonPath("$.detail")
                .value(org.hamcrest.Matchers.containsString("plowshare.images.max-bytes")));
  }

  /**
   * DocumentController's rule: a caller who meant to name this and sent the field empty is told,
   * not quietly filed under nothing.
   */
  @Test
  void a_blank_name_is_a_bad_request_and_not_a_silent_fallback() throws Exception {
    mvc.perform(multipart("/v1/images").file(upload("red.png", PNG)).param("name", " "))
        .andExpect(status().isBadRequest());

    assertEquals(0, images.find(Home.global(), ImageStore.idFor(PNG)).stream().count());
  }

  @Test
  void a_name_the_caller_chose_is_recorded_over_the_uploaded_filename() throws Exception {
    mvc.perform(
            multipart("/v1/images")
                .file(upload("IMG_4831.png", PNG))
                .param("name", "the schematic"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.filename").value("the schematic"));
  }

  /**
   * A server with no data directory is a running server that holds no images, and it says which key
   * gives it one rather than failing at the first attachment.
   */
  @Test
  void a_server_that_keeps_nothing_says_so_rather_than_writing_somewhere() throws Exception {
    MockMvc none =
        MockMvcBuilders.standaloneSetup(new ImageController(ImageStore.NONE))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    none.perform(multipart("/v1/images").file(upload("red.png", PNG)))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("PLOWSHARE_DATA_DIR")));
  }

  @Test
  void authenticated_uploads_without_a_project_stay_in_the_accounts_personal_space()
      throws Exception {
    var personal =
        org.mockito.Mockito.mock(io.aeyer.plowshare.server.personal.PersonalSpaces.class);
    String scope = io.aeyer.plowshare.server.personal.PersonalSpaces.name("viewer");
    org.mockito.Mockito.when(personal.home(null, "viewer")).thenReturn(Home.of(scope));
    var controller = new ImageController(images);
    controller.usePersonal(personal);
    var authenticated = MockMvcBuilders.standaloneSetup(controller).build();
    authenticated
        .perform(
            multipart("/v1/images")
                .file(upload("red.png", PNG))
                .requestAttr(io.aeyer.plowshare.server.auth.AuthFilter.HANDLE_ATTRIBUTE, "viewer"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.project").value(scope));
    assertEquals(1, images.find(Home.of(scope), ImageStore.idFor(PNG)).stream().count());
    assertEquals(0, images.find(Home.global(), ImageStore.idFor(PNG)).stream().count());
  }
}
