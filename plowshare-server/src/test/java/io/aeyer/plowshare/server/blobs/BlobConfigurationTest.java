package io.aeyer.plowshare.server.blobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.images.ImagesProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

class BlobConfigurationTest {
  private ApplicationContextRunner runner(DataLayout data) {
    return new ApplicationContextRunner()
        .withUserConfiguration(BlobConfiguration.class)
        .withBean(DataLayout.class, () -> data)
        .withBean(UnitOfWork.class, () -> UnitOfWork.NONE)
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withBean(ImagesProperties.class, ImagesProperties::new);
  }

  @Test
  void no_data_directory_is_explicit_and_cleanup_is_disabled() {
    runner(DataLayout.NONE)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertFalse(context.getBean(BlobProperties.class).isCleanupEnabled());
              var storage = context.getBean(BinaryBlobStorage.class);
              assertInstanceOf(UnavailableBlobStorage.class, storage);
              assertThrows(
                  BlobUnavailableException.class,
                  () ->
                      storage.read(
                          "global",
                          "image",
                          new BlobClaim("image", "image", 0, BlobClaim.Retention.PINNED)));
              assertEquals(0, storage.collect(1));
            });
  }

  @Test
  void configured_data_layout_composes_the_disk_implementation(@TempDir Path root) {
    runner(new DataLayout(root))
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertInstanceOf(
                  DiskBinaryBlobStorage.class, context.getBean(BinaryBlobStorage.class));
              assertEquals(
                  java.time.Duration.ofMinutes(10),
                  context.getBean(BlobProperties.class).getRecoveryGrace());
            });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "plowshare.blobs.cleanup-batch-size=0",
        "plowshare.blobs.cleanup-batch-size=101",
        "plowshare.blobs.recovery-grace=-1s",
        "plowshare.blobs.cleanup-interval=0s",
        "plowshare.blobs.cleanup-interval=2d",
        "plowshare.blobs.recovery-grace=31d"
      })
  void invalid_bounds_fail_at_startup(String property) {
    runner(DataLayout.NONE)
        .withPropertyValues(property)
        .run(context -> assertNotNull(context.getStartupFailure()));
  }
}
