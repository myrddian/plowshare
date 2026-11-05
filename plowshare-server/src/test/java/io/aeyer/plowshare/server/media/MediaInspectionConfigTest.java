package io.aeyer.plowshare.server.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.server.archive.ProjectDirectories;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.images.ImagesConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MediaInspectionConfigTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(ImagesConfig.class)
          .withBean(DataLayout.class, () -> DataLayout.NONE)
          .withBean(ProjectDirectories.class, () -> mock(ProjectDirectories.class))
          .withBean(
              io.aeyer.plowshare.server.blobs.BinaryBlobStorage.class,
              () -> mock(io.aeyer.plowshare.server.blobs.BinaryBlobStorage.class))
          .withBean(ProjectStore.class, () -> mock(ProjectStore.class));

  @Test
  void inspection_is_available_without_disk_and_does_not_enable_storage() {
    runner.run(
        context -> {
          assertNull(context.getStartupFailure());
          assertEquals(ImageStore.NONE, context.getBean(ImageStore.class));
          assertEquals(
              3,
              context
                  .getBean(MediaInspection.class)
                  .inspectImage(
                      MediaInspectionTest.png(
                          3, 2, java.awt.image.BufferedImage.TYPE_INT_RGB, false))
                  .width());
        });
  }

  @Test
  void binds_configured_limits_and_reuses_the_image_byte_ceiling() {
    runner
        .withPropertyValues(
            "plowshare.images.max-bytes=8",
            "plowshare.media.inspection.max-pixels=5",
            "plowshare.media.inspection.max-dimension=4",
            "plowshare.media.inspection.max-concurrent=1")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertEquals(5, context.getBean(MediaInspectionProperties.class).getMaxPixels());
              assertEquals(4, context.getBean(MediaInspectionProperties.class).getMaxDimension());
              assertEquals(1, context.getBean(MediaInspectionProperties.class).getMaxConcurrent());
              assertEquals(
                  MediaInspectionException.Reason.TOO_LARGE,
                  assertThrows(
                          MediaInspectionException.class,
                          () -> context.getBean(MediaInspection.class).inspectImage(new byte[9]))
                      .reason());
            });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "plowshare.images.max-bytes=0",
        "plowshare.media.inspection.max-pixels=0",
        "plowshare.media.inspection.max-pixels=64000001",
        "plowshare.media.inspection.max-dimension=-1",
        "plowshare.media.inspection.max-dimension=32769",
        "plowshare.media.inspection.max-concurrent=0",
        "plowshare.media.inspection.max-concurrent=9"
      })
  void invalid_limits_fail_at_startup(String property) {
    runner.withPropertyValues(property).run(context -> assertNotNull(context.getStartupFailure()));
  }

  @Test
  void inspection_does_not_impose_a_new_upper_limit_on_the_existing_upload_byte_setting() {
    runner
        .withPropertyValues("plowshare.images.max-bytes=67108865")
        .run(context -> assertNull(context.getStartupFailure()));
  }
}
