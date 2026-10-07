package io.aeyer.plowshare.server.files;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operator-owned backend configuration; project files never choose executables or runtime mounts.
 */
@ConfigurationProperties(prefix = "plowshare.command-isolation")
public class CommandIsolationProperties {
  private String bubblewrap = "";
  private String launcher = "";
  private String scratchRoot = "";

  public String getScratchRoot() {
    return scratchRoot;
  }

  public void setScratchRoot(String scratchRoot) {
    this.scratchRoot = scratchRoot;
  }

  private List<String> runtimeRoots = List.of();

  public String getBubblewrap() {
    return bubblewrap;
  }

  public void setBubblewrap(String bubblewrap) {
    this.bubblewrap = bubblewrap;
  }

  public String getLauncher() {
    return launcher;
  }

  public void setLauncher(String launcher) {
    this.launcher = launcher;
  }

  public List<String> getRuntimeRoots() {
    return runtimeRoots;
  }

  public void setRuntimeRoots(List<String> runtimeRoots) {
    this.runtimeRoots = List.copyOf(runtimeRoots);
  }
}
