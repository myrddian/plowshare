package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.approvals.ReviewWork;
import io.aeyer.plowshare.server.approvals.SecurityPolicyFiles;
import io.aeyer.plowshare.server.approvals.WorkspaceSecurityGuidance;
import io.aeyer.plowshare.server.files.RemoteProvider;
import io.aeyer.plowshare.server.files.SessionChannel;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Reuses registered project configuration and the selected client's definition channel. */
@Component
public final class ProjectSecurityGuidance implements WorkspaceSecurityGuidance {
  private final ProjectConfigurations configurations;
  private final SessionChannel channel;

  public ProjectSecurityGuidance(ProjectConfigurations configurations, SessionChannel channel) {
    this.configurations = configurations;
    this.channel = channel;
  }

  @Override
  public Guidance read(String project, String provider, ReviewWork work) {
    Runnable requireActive =
        () -> {
          if (work.stopped())
            throw new IllegalStateException("Security guidance read expired or was cancelled");
        };
    requireActive.run();
    String workspace = configurations.securityGuidance(project, requireActive);
    String prefix = RemoteProvider.NAME + ":";
    if (!provider.startsWith(prefix)) return new Guidance(workspace, "");

    var definitions = new ChannelDefinitions(channel, provider.substring(prefix.length()));
    String checkout =
        SecurityPolicyFiles.workspace(
            name -> {
              requireActive.run();
              var file = definitions.securityResource(name, work, SecurityPolicyFiles.MAX_BYTES);
              if (file.unreadable() != null)
                throw new IllegalStateException(
                    "The serving checkout's security guidance could not be read");
              return Optional.ofNullable(file.text());
            });
    definitions.requireCurrent();
    return new Guidance(workspace, checkout.equals(workspace) ? "" : checkout);
  }
}
