package io.aeyer.plowshare.client.files;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * What this machine tells the server it roots: a project, the place it sits, and what this box is
 * called.
 *
 * <p>A session that sends one is the session that serves that project's files, whichever terminal a
 * run is submitted from. A session that sends none lends its files and roots nothing, which is what
 * every client did before presence and is still an ordinary way to run.
 *
 * <h2>The machine is a client property, and it has to be</h2>
 *
 * <p>The server cannot know what box a socket came from. A reverse lookup of the peer address would
 * be a different fact wearing the same name, and behind a network it is usually wrong. So the
 * client asserts it, {@link #thisMachine()} resolves it, and a person who cares sets it.
 *
 * <h2>Necessary, sufficient, and the difference between them</h2>
 *
 * <p>The design spec suspected that declaring a presence was already on the wire as {@code
 * --workspace}. <b>It is half of it.</b> A workspace is what this machine lends and it already
 * travels — as the answer to a {@code ROOTS} request, on a socket that has already opened. What
 * never travelled is <em>which project those directories are</em>: the project name has only ever
 * gone out per run, on the submission, long after the socket was up. So this record is the missing
 * half rather than a new channel, and a client with no {@code --workspace} has nowhere to root and
 * sends none of it.
 *
 * @param machine what this box is called
 * @param root where the project sits on it, absolute
 * @param project the friendly name a person gave it — the same name {@code --project} puts on a run
 */
public record Rooting(String machine, String root, String project) {

  /**
   * The environment variable naming this machine.
   *
   * <p>{@code PlowshareClient.SERVER_URL_ENV}'s shape and resolution order — environment, then a
   * system property so a test or a wrapper can override without exporting anything, then a default.
   * Unlike a token it is safe on a command line: a machine name is a name and not a credential.
   */
  public static final String MACHINE_ENV = "PLOWSHARE_MACHINE";

  /**
   * The system property that overrides it, for a test that must not depend on the hostname of
   * whatever box the suite runs on.
   */
  public static final String MACHINE_PROPERTY = "plowshare.machine";

  /**
   * What is used when neither is set and the hostname cannot be read.
   *
   * <p><b>Not a good name, deliberately.</b> A canonical name built on this is one an operator will
   * want to change, and a plausible-looking default would be one they never notice.
   */
  public static final String UNNAMED_MACHINE = "unnamed-machine";

  public Rooting {
    Objects.requireNonNull(machine, "machine");
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(project, "project");
  }

  /**
   * A rooting for {@code project} at {@code root}, on whatever this machine is called.
   *
   * @param root resolved to an absolute, real path here rather than sent as typed. It is one
   *     component of an identity, and {@code ../ledger} and {@code /Users/example/ledger} naming
   *     one place must not be two identities
   */
  public static Rooting of(Path root, String project) {
    Objects.requireNonNull(root, "root");
    return new Rooting(thisMachine(), absolute(root), project);
  }

  /**
   * What this box is called: the environment, then a system property, then the hostname, then
   * {@link #UNNAMED_MACHINE}.
   *
   * <p>Named {@code thisMachine} rather than {@code machine} because a record component of that
   * name already has the accessor, and a static method cannot share it. The distinction is worth
   * having in the name anyway: one answers what <em>this process</em> is on, and the other what a
   * particular claim said.
   *
   * <p><b>The hostname is a convenience and not a guarantee</b>, and the collision mode is recorded
   * rather than discovered: two machines both defaulting to {@code MacBook-Pro.local} produce
   * colliding canonical names for different projects, and nothing can catch that — the server's
   * refuse-on-conflict rule catches two sessions claiming one project, not two machines sharing a
   * default name. Setup should push an operator to set {@link #MACHINE_ENV}.
   */
  public static String thisMachine() {
    String fromEnv = System.getenv(MACHINE_ENV);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv.trim();
    }
    String fromProperty = System.getProperty(MACHINE_PROPERTY);
    if (fromProperty != null && !fromProperty.isBlank()) {
      return fromProperty.trim();
    }
    return hostname();
  }

  /**
   * The hostname, or {@link #UNNAMED_MACHINE}.
   *
   * <p><b>{@code InetAddress.getLocalHost().getHostName()} and not {@code
   * getCanonicalHostName()}</b>: the second performs a reverse DNS lookup, which can block for the
   * length of a resolver timeout on a box with no network — and this runs on the path that opens a
   * socket. What it would buy is a fully qualified name, which is not what this is for: this is a
   * label an operator recognises, and it is overridable precisely because no automatic answer is
   * authoritative.
   *
   * <p>Every failure is the default rather than an exception. A client that refused to start
   * because it could not name itself would be a client that does not run on a box with a broken
   * {@code /etc/hosts}, which is a real shape of machine and not a misconfiguration worth stopping
   * for.
   */
  private static String hostname() {
    try {
      String named = InetAddress.getLocalHost().getHostName();
      return named == null || named.isBlank() ? UNNAMED_MACHINE : named.trim();
    } catch (UnknownHostException | RuntimeException cannotSayWhereItIs) {
      return UNNAMED_MACHINE;
    }
  }

  /**
   * The path as an identity component: absolute, and real where the filesystem can say so.
   *
   * <p>{@code toRealPath} resolves symlinks, which is what {@code FileAccess} does on both sides of
   * this wire when it decides containment — on macOS every tree under {@code /tmp} or {@code /var}
   * is reached through one — so a root advertised one way and identified the other would be two
   * names for one place. Falls back to {@code toAbsolutePath().normalize()} when the directory
   * cannot be read, which is not a reason to refuse to open a socket.
   */
  private static String absolute(Path root) {
    try {
      return root.toRealPath().toString();
    } catch (IOException | RuntimeException notReadable) {
      return root.toAbsolutePath().normalize().toString();
    }
  }
}
