package io.aeyer.plowshare.server.data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Explicit runtime-owned data directory. {@code PLOWSHARE_DATA_DIR} binds directly to {@code
 * plowshare.data.dir}; the entrypoint supplies no filesystem default.
 *
 * <p>A blank value gives an embedded context {@link DataLayout#NONE} without creating directories.
 * Deployments that use persistent resources or first-run setup must supply their data directory.
 * Initialisation refuses an occupied unmarked directory; the configured root is excluded from agent
 * workspaces so adding a runtime-owned subtree cannot expose it to an agent.
 */
@ConfigurationProperties(prefix = "plowshare.data")
public class DataProperties {

  /** Explicit directory, resolved against the process working directory when relative. */
  private String dir = "";

  public String getDir() {
    return dir;
  }

  public void setDir(String dir) {
    this.dir = dir;
  }
}
