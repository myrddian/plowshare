package io.aeyer.plowshare.server.images;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.blobs.BlobRepository;
import io.aeyer.plowshare.server.media.MediaInspection;
import io.aeyer.plowshare.server.relay.tools.ApplicationToolRegistry;
import io.aeyer.plowshare.server.relay.tools.HostedInvocationAuthority;
import io.aeyer.plowshare.server.relay.tools.RelayToolRepository;
import java.time.Clock;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Composition only; invocation/receipt SQL stays in specialist repositories. */
@Configuration
public class ArtifactConfiguration {
  @Bean
  @DependsOnDatabaseInitialization
  public ArtifactRepository artifactRepository(JdbcTemplate jdbc, BlobRepository blobs) {
    return new JdbcArtifactRepository(jdbc, blobs);
  }

  @Bean
  public ArtifactAuthority artifactAuthority(
      RelayToolRepository invocations,
      ProjectMembers members,
      ProjectWorkspaces projects,
      AdminStore accounts,
      ApplicationToolRegistry tools,
      HostedInvocationAuthority hosted) {
    // Adapt in composition: adding an interface to AdminStore changes its exception-translation
    // proxy type and breaks the legacy consumers that still inject its concrete class.
    return new InvocationArtifactAuthority(
        invocations, members, projects, accounts::isEnabled, tools, hosted);
  }

  @Bean
  public ArtifactAdmissions artifactAdmissions(
      ArtifactAuthority authority,
      ArtifactRepository repository,
      ImageStore images,
      MediaInspection inspection,
      UnitOfWork work,
      ImagesProperties properties) {
    return new InvocationArtifactAdmissions(
        authority,
        repository,
        images,
        inspection,
        work,
        Clock.systemUTC(),
        properties::isInvocationAdmissionEnabled);
  }
}
