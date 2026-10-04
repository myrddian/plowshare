package io.aeyer.plowshare.server.data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The one directory this server owns on disk, from which every other server-owned path is derived
 * unless an operator names it themselves.
 *
 * <h2>Why there is a root at all, when there were already keys</h2>
 *
 * <p>Before this there were keys naming directories one at a time — an agents directory (since
 * retired, on the classpath seed and this tree's own {@code global/agents/} and {@code
 * global/bots/}), {@code plowshare.llm.sampling-directory}, {@code
 * plowshare.conversations.retention.export-directory} — and no answer to "where does this server
 * keep its things". Each default was relative and each landed somewhere different in the working
 * directory, which is a layout only in the sense that a pile is one. <b>Maven's shape instead:</b>
 * one root, a known tree under it, and every path in that tree still individually overridable. The
 * existing keys become the overrides rather than the primary way to say it.
 *
 * <p>The reason it arrived now rather than at the beginning is in {@code ProjectStore}: exports are
 * the first server-owned <em>files</em> this system has had. Memories are rows, and {@code Scope}
 * argues at length that an archive scope would be "a grant nothing could ever satisfy". That
 * argument is untouched — it is about memories, and memories are still rows.
 *
 * <h2>Why the key is {@code dir} and not {@code directory}</h2>
 *
 * <p>Against the spelling every neighbouring key uses ({@code config-file}, {@code
 * sampling-directory}, {@code export-directory}), and the reason is mechanical rather than
 * aesthetic. Spring's relaxed binding maps the environment variable {@code PLOWSHARE_DATA_DIR} onto
 * {@code plowshare.data.dir} and onto nothing else; {@code plowshare.data.directory} would need
 * {@code PLOWSHARE_DATA_DIRECTORY}, or a {@code ${PLOWSHARE_DATA_DIR:…}} placeholder in {@code
 * application.yml} to bridge the two — and that placeholder is exactly what must not exist here
 * (see {@link #getDir}). One of the two spellings makes the documented variable work with no
 * bridge, and it is this one.
 *
 * <h2>Blank is the default, and the blank is load-bearing</h2>
 *
 * <p>{@code PlowshareServerApplication.main} sets this as a <em>default property</em>, which is
 * Spring's lowest-precedence source, exactly as it does for {@code plowshare.auth.token-file} and
 * for that key's reason: {@link DataLayout#initialise} creates directories on the machine running
 * the build, and a value in {@code application.yml} is a value every test reads too. Dozens of
 * contexts in this repository stand Spring up against a real database; each of them would create
 * and mark a data directory beside the module it ran in.
 *
 * <p>So a context that did not come through {@code main} has no data directory, {@link
 * DataLayout#NONE} is what it gets, and nothing is created. That is a running server — it simply
 * keeps no exports unless {@code plowshare.conversations.retention.export-directory} says where.
 */
@ConfigurationProperties(prefix = "plowshare.data")
public class DataProperties {

  /**
   * Where this server keeps what it owns, or blank for a deployment that keeps nothing on disk.
   *
   * <p><b>It is deliberately not written into {@code application.yml}.</b> A key there would
   * outrank {@code main}'s default property — Spring's config file wins over {@code
   * setDefaultProperties} — so the blank in the YAML would be the value every real server ran with,
   * and {@code main}'s line would be dead. The absence here is what lets {@code main} supply {@code
   * data} for a real boot while every test context supplies nothing.
   *
   * <p>Relative unless it is absolute, resolved against the process's working directory, which is
   * {@code plowshare.llm.sampling-directory}'s shape and its reason: there is no absolute path that
   * is right on two machines.
   *
   * <p><b>An operator moves it with {@code PLOWSHARE_DATA_DIR}</b>, which is the whole point of the
   * key being spelled {@code dir}, or with {@code --plowshare.data.dir=…} on the command line. Both
   * outrank {@code main}'s default.
   */
  private String dir = "";

  public String getDir() {
    return dir;
  }

  public void setDir(String dir) {
    this.dir = dir;
  }
}
