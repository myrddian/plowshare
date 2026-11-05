package io.aeyer.plowshare.server.blobs;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.images.ImagesProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composition only; storage location comes exclusively from the existing configured data layout.
 */
@Configuration
@EnableConfigurationProperties(BlobProperties.class)
public class BlobConfiguration {
  @Bean
  @DependsOnDatabaseInitialization
  public BlobRepository blobRepository(JdbcTemplate jdbc) {
    return new JdbcBlobRepository(jdbc);
  }

  @Bean
  public BinaryBlobStorage binaryBlobStorage(
      DataLayout data,
      BlobRepository repository,
      UnitOfWork work,
      BlobProperties properties,
      ImagesProperties images) {
    if (!data.keepsAnything()) return new UnavailableBlobStorage();
    return new DiskBinaryBlobStorage(
        data.root(),
        repository,
        work,
        Clock.systemUTC(),
        properties.getRecoveryGrace(),
        Math.max(properties.getMaxBytes(), images.getMaxBytes()));
  }

  @Bean(destroyMethod = "close")
  public BlobMaintenance blobMaintenance(BinaryBlobStorage blobs, BlobProperties properties) {
    return new BlobMaintenance(blobs, properties);
  }
}
