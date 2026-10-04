package io.aeyer.plowshare.server.images;

import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ImageDirectories;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.data.DataLayout;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires {@link ImageStore}, and decides in one place whether this deployment holds images at all.
 *
 * <p>{@link ImageStore} takes no stereotype annotation of its own, on {@code DataLayout}'s and
 * {@code Archive}'s reasoning: it stays framework-free, and whatever wires it supplies the
 * mechanism it will not name.
 *
 * <p><b>There is no "keep images somewhere else" branch, and the asymmetry with {@code
 * ArchiveConfig.payloadExport} is deliberate.</b> That method has three outcomes because {@code
 * plowshare.conversations.retention.export-directory} predates the data directory and had to be
 * demoted rather than removed. Images have no such key, so there are two outcomes: a data
 * directory, or nothing. {@code DataLayout.imagesFor} argues why a second way to say where an image
 * lives is worth refusing.
 */
@Configuration
@EnableConfigurationProperties(ImagesProperties.class)
public class ImagesConfig {

  @Bean
  public ImageStore imageStore(
      DataLayout data, JdbcTemplate jdbc, ImagesProperties props, ProjectStore projects) {
    return data.keepsAnything()
        ? new ImageStore(
            ImageDirectories.under(data, jdbc), props.getMaxBytes(), fenceOver(projects))
        : ImageStore.NONE;
  }

  /**
   * The binding that makes a named workspace image resolvable, and makes it stop resolving when the
   * project's reach changes.
   *
   * <h2>{@code ProjectRecord.reach} and nothing assembled here</h2>
   *
   * <p>That method is the single expression a project's containment is — its roots paired with its
   * effective exclusions — and {@code LocalProvider} reads a file through the very same call. A
   * copy of it written out at this bean would be the second one, and the two that drift are the
   * pair deciding what an agent may read. {@link ImageFence} carries why the check happens at all,
   * which is Enzo's reason and is liveness before it is permission.
   *
   * <h2>Three ways to answer no, and all three are ordinary</h2>
   *
   * <p><b>The global tier reaches no file.</b> {@code LocalProvider.leash} says it in as many words
   * — the global tier is every project at once and so names no single filesystem — so there is no
   * root a global run could have named a picture out of, and nothing to re-permit. A record that
   * got there some other way resolves to a refusal rather than to bytes nobody fenced.
   *
   * <p><b>A project with no row reaches none either.</b> An operator who ran {@code forget} has
   * revoked the workspace, and the id named out of it goes with it — which is the whole property
   * this fence exists for, arriving as an ordinary absent lookup rather than as a special case.
   *
   * <p><b>And the path is asked as it was recorded</b>, which is the canonical one {@code
   * LocalProvider} had already resolved and checked. {@code FileAccess.canonical} is not idempotent
   * past its budget — that class says so, and names the test — so re-canonicalising here could ask
   * about a different spelling from the one that was permitted, which is the one mistake this call
   * must not make.
   *
   * <p><b>Public and static, so a test uses the binding rather than a copy of it.</b> {@code
   * ImageDirectories.under} makes the same argument for the same reason: a directory scheme, or a
   * containment rule, is exactly the kind of thing that gets edited in one place and asserted in
   * another.
   */
  public static ImageFence fenceOver(ProjectStore projects) {
    return (Home home, Path file) -> {
      if (home.isGlobal()) {
        return false;
      }
      Optional<ProjectRecord> row = projects.find(home.project());
      if (row.isEmpty()) {
        return false;
      }
      ProjectRecord project = row.get();
      FileAccess reach = project.reach(projects.effectiveExclusions(project));
      return reach.permits(file);
    };
  }
}
