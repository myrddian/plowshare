package io.aeyer.plowshare.server.files;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where this server's own configuration file is, so that no workspace can cover it.
 *
 * <p>One key, and it exists because {@code ProjectStore.mandatoryExclusions} takes a path and
 * nothing was passing one. The spec's rule is absolute — the configuration holds the model API key,
 * and a {@code file_read} reaching it defeats in one call the one rule this project keeps without
 * exceptions — but a rule stated over a path nobody supplies is a rule about nothing.
 *
 * <p><b>{@code application.yml}, relative, is the default</b>, resolved against the process's
 * working directory, which is {@code plowshare.llm.sampling-directory}'s reasoning applied again:
 * there is no absolute path that is right on two machines, and a server started with no key at all
 * should still fence off the file an operator dropped beside it.
 *
 * <p><b>It is not the whole of the rule, and must not be read as it.</b> Spring Boot loads
 * configuration from {@code file:./} and {@code file:./config/} as well, under every name,
 * extension and profile it accepts — so {@code ProjectStore.mandatoryExclusions} excludes the
 * server's <em>working directory</em> and does not rely on this path alone. A security review
 * measured what this key covered by itself: {@code config/application.yml}, which outranks it in
 * Spring's own precedence order, was readable. This key remains for the deployment that keeps its
 * configuration somewhere the working directory does not cover.
 *
 * <p><b>It may name a file that is not there, and that is not an error.</b> A deployment whose
 * configuration is inside the jar, or whose API key arrives as an environment variable, has no
 * on-disk file holding the secret — so the exclusion covers a path nothing resolves to, and there
 * is correspondingly nothing for it to protect. What must not happen is the other way round: a
 * deployment that <em>does</em> keep the file on disk and points this key somewhere else. That is
 * why this is written out in {@code application.yml} rather than left to the default, where an
 * operator would have to guess that a key exists before they could set it.
 */
@ConfigurationProperties(prefix = "plowshare.workspace")
public class WorkspaceProperties {

  /** The server's own configuration file — the one holding the model API key. */
  private String configFile = "application.yml";

  public String getConfigFile() {
    return configFile;
  }

  public void setConfigFile(String configFile) {
    this.configFile = configFile;
  }
}
