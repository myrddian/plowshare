package io.aeyer.plowshare.server.hooks.script;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The knobs on project hooks. Spec §6 and §7.
 *
 * <p>{@code enabled} exists for an operator's first bad night, not as a feature switch: the harness
 * is unaffected by it, and a project's hooks are the project's.
 *
 * <p>{@code timeout} bounds a file's load as well as each call: a module's top level is code that
 * runs outside every {@code handle}, and a file whose top level never finishes is a file that did
 * not load.
 */
@ConfigurationProperties(prefix = "plowshare.hooks")
public class HooksProperties {

  private boolean enabled = true;
  private Duration timeout = Duration.ofSeconds(2);
  private int poolMax = Math.min(Runtime.getRuntime().availableProcessors(), 8);
  private Duration idle = Duration.ofMinutes(5);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration timeout) {
    this.timeout = timeout;
  }

  public int getPoolMax() {
    return poolMax;
  }

  public void setPoolMax(int poolMax) {
    this.poolMax = poolMax;
  }

  public Duration getIdle() {
    return idle;
  }

  public void setIdle(Duration idle) {
    this.idle = idle;
  }
}
