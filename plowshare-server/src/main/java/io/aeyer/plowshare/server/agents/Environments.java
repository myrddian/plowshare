package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.server.files.SessionChannel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What {@code run} may do in a project, on each side, resolved from the Application-root {@code
 * environment.yml}, or the legacy {@code projects/<id>/environment.yml}, and the rooting session's
 * External {@code .plowshare/environment.yml}.
 *
 * <p><b>Read on every call, and cached nowhere.</b> A run asks once per {@code run} call, two small
 * files are cheaper than a stale answer about whether a command may start, and an edited file takes
 * effect on the next call. The one thing kept is the person's caps as last read ({@link #caps}),
 * and only to stand in for a read that could not be had — never in place of one that could.
 */
public final class Environments {

  private static final Logger log = LoggerFactory.getLogger(Environments.class);

  /** No data directory: every side is the defaults, which run nothing. */
  public static final Environments NONE = new Environments(name -> null, id -> null, null);

  private final Function<String, Long> projectIds;
  private final LongFunction<Path> files;
  private final SessionChannel channel;
  private ApplicationResources applicationResources = ApplicationResources.NONE;

  /** Deployed runtime policy belongs to the Application root, including without a data tier. */
  public void useApplicationResources(ApplicationResources resources) {
    applicationResources = Objects.requireNonNull(resources);
  }

  private Function<String, ProjectConfiguration> projectConfiguration =
      project -> ProjectConfiguration.NONE;
  private final Map<String, ProjectCaps> lastManifestCaps = new ConcurrentHashMap<>();

  public void useProjectConfiguration(Function<String, ProjectConfiguration> source) {
    projectConfiguration = Objects.requireNonNull(source);
  }

  /**
   * Each project's caps from the rooting machine's file, as last read — empty for a file read with
   * none; see {@link #caps}. In memory: a restart forgets them until the next read.
   */
  private final Map<String, Optional<EnvironmentFile.Caps>> lastPersonCaps =
      new ConcurrentHashMap<>();

  /**
   * @param projectIds a project's surrogate id by name, or null when it has none
   * @param files where a project's environment file is, by id; null when this server keeps no data
   *     directory
   * @param channel how a session's own file is read; null reads none
   */
  public Environments(
      Function<String, Long> projectIds, LongFunction<Path> files, SessionChannel channel) {
    this.projectIds = Objects.requireNonNull(projectIds, "projectIds");
    this.files = Objects.requireNonNull(files, "files");
    this.channel = channel;
  }

  /**
   * Both sides, with why a side is off when a file made it so.
   *
   * @param localWhy null unless a file forced the local side off, in which case the sentence the
   *     gate's refusal quotes; likewise {@code serverWhy}
   */
  public record Resolved(
      EnvironmentFile.Side local, EnvironmentFile.Side server, String localWhy, String serverWhy) {

    public static final Resolved OFF =
        new Resolved(EnvironmentFile.Side.DEFAULT, EnvironmentFile.Side.DEFAULT, null, null);
  }

  /** A project's surrogate id, or null when it has none or cannot be looked up. */
  public Long projectId(String project) {
    try {
      return project == null ? null : projectIds.apply(project);
    } catch (RuntimeException unknowable) {
      return null;
    }
  }

  /**
   * @param project the run's project, or null for the global tier, where nothing runs
   * @param session the session whose machine the command would reach, or null
   */
  public Resolved resolve(String project, String session) {
    if (project == null) {
      return Resolved.OFF;
    }
    EnvironmentFile.Side local = EnvironmentFile.Side.DEFAULT;
    EnvironmentFile.Side server = EnvironmentFile.Side.DEFAULT;
    String localWhy = null;
    String serverWhy = null;

    try {
      EnvironmentFile.Parsed parsed = serverFile(project);
      local = local.with(parsed.local());
      server = server.with(parsed.server());
      var commands = projectConfiguration.apply(project).commands();
      local = local.with(commands.local());
      server = server.with(commands.server());
    } catch (IOException | IllegalArgumentException | SecurityException unreadable) {
      // Both sides, and not only the one a bad line was in: a file that
      // cannot be read says nothing reliable about either.
      String why =
          "the server's environment.yml for project '"
              + project
              + "' could not be read, so nothing runs on either side until it is fixed: "
              + unreadable.getMessage();
      return new Resolved(local.off(), server.off(), why, why);
    }

    if (session != null && channel != null) {
      String text = new ChannelDefinitions(channel, session).environmentFile();
      if (text != null) {
        try {
          EnvironmentFile.Parsed parsed = EnvironmentFile.parse(text);
          // Only its own side. A server: section is a person's file
          // deciding what the server runs, which spec §1.4 refuses.
          local = local.with(parsed.local());
          if (parsed.server() != null && server.isOff()) {
            serverWhy =
                "the .plowshare/environment.yml on the rooting machine has a"
                    + " server: section, and it is ignored: only the server's own"
                    + " environment.yml decides what runs on the server";
          }
        } catch (EnvironmentFile.Unreadable unreadable) {
          localWhy =
              "the environment.yml in .plowshare on the machine this would run on"
                  + " could not be read, so nothing runs there until it is fixed: "
                  + unreadable.getMessage();
          local = local.off();
        }
      }
      try {
        var commands =
            ProjectConfiguration.local(new ChannelDefinitions(channel, session), project)
                .commands();
        local = local.with(commands.local());
        if (commands.server() != null && server.isOff())
          serverWhy =
              "The local project manifest's server command policy is ignored; only server-owned configuration decides what runs there";
      } catch (RuntimeException invalid) {
        local = local.off();
        localWhy = "The local project manifest could not be read: " + invalid.getMessage();
      }
    }
    return new Resolved(local, server, localWhy, serverWhy);
  }

  /**
   * The server's own file for {@code project}, parsed — {@link EnvironmentFile.Parsed#EMPTY} when
   * the project has no id, no admitted Application or data tier exists, or nobody wrote a file. A
   * missing Application policy never falls back to the legacy tier. Shared by {@link #resolve} and
   * {@link #caps} so the two read one file one way.
   *
   * @throws IOException if the file is there and could not be read
   * @throws EnvironmentFile.Unreadable if it was read and does not parse
   */
  private EnvironmentFile.Parsed serverFile(String project) throws IOException {
    Long id;
    try {
      id = projectIds.apply(project);
    } catch (RuntimeException unknowable) {
      id = null;
    }
    Path file =
        id == null
            ? null
            : applicationResources
                .directory(id, "")
                .map(root -> root.resolve("environment.yml"))
                .orElse(files.apply(id));
    if (file == null) {
      return EnvironmentFile.Parsed.EMPTY;
    }
    try (var input = Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = input.readNBytes(65537);
      if (bytes.length > 65536) throw new IOException("Environment policy exceeds 64 KiB");
      return EnvironmentFile.parse(
          StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString());
    } catch (NoSuchFileException absent) {
      return EnvironmentFile.Parsed.EMPTY;
    }
  }

  /**
   * The person's caps for {@code project} (spec 2026-09-29 §2): the server's file, then the rooting
   * session's {@code .plowshare/environment.yml}, key by key — the person's file wins. Read on
   * every call, like {@link #resolve}, so a {@code /cap} that rewrote the file is what the next
   * read sees.
   *
   * <p><b>An unreadable file sets nothing and says why</b>, rather than failing whatever asked: a
   * cap is a ceiling a person chose, and a typo in it should leave each definition's own ceiling
   * standing — never no ceiling, and never a run that cannot start. Unlike {@link #resolve}, a bad
   * server file does not also void the machine's: caps have no sides, and the file that did parse
   * still says what its author meant.
   *
   * @param project the project, or null for the global tier, which has none
   * @param session the session rooting it, or null for none
   * @return the caps and where each came from
   */
  public ProjectCaps caps(String project, String session) {
    if (project == null) {
      return ProjectCaps.NONE;
    }
    EnvironmentFile.Caps server = null;
    String unreadable = null;
    try {
      server = serverFile(project).caps();
    } catch (IOException | EnvironmentFile.Unreadable | SecurityException failed) {
      unreadable =
          "the server's environment.yml for project '"
              + project
              + "' could not be"
              + " read, so its caps are not used: "
              + failed.getMessage();
    }
    // THE PERSON'S CAPS OUTLIVE A DISCONNECT (Task 12's review). Read from the rooting
    // machine, they vanished whenever nobody rooted the project or one read failed — a
    // closed laptop, a slow client — and every run fell back to its definition's numbers
    // mid-flight. So the last successful read is kept per project, and stands in for a read
    // that could not be had: no rooting session, or one whose file could not be read. A
    // file that is NOT THERE is a successful read of no caps, and replaces what was kept.
    Optional<EnvironmentFile.Caps> kept = lastPersonCaps.getOrDefault(project, Optional.empty());
    EnvironmentFile.Caps local = kept.orElse(null);
    if (session != null && channel != null) {
      ChannelDefinitions.FileRead read =
          new ChannelDefinitions(channel, session).environmentFileRead();
      String failed = read.unreadable();
      if (read.absent()) {
        local = null;
        lastPersonCaps.put(project, Optional.empty());
      } else if (read.text() != null) {
        try {
          local = EnvironmentFile.parse(read.text()).caps();
          lastPersonCaps.put(project, Optional.ofNullable(local));
        } catch (EnvironmentFile.Unreadable parse) {
          failed = parse.getMessage();
        }
      }
      if (failed != null) {
        String why =
            "the .plowshare/environment.yml on the rooting machine could not be"
                + " read ("
                + failed
                + "), so "
                + (kept.isPresent() ? "its caps as last read are used" : "it sets no caps");
        log.warn("project {}: {}", project, why);
        unreadable = unreadable == null ? why : unreadable + "; " + why;
      }
    }
    ProjectCaps manifest = lastManifestCaps.getOrDefault(project, ProjectCaps.NONE);
    if (session != null && channel != null) {
      try {
        manifest =
            ProjectConfiguration.local(new ChannelDefinitions(channel, session), project).caps();
        lastManifestCaps.put(project, manifest);
      } catch (RuntimeException invalid) {
        String why =
            "The local project manifest could not be read ("
                + invalid.getMessage()
                + "); its caps as last read are used";
        unreadable = unreadable == null ? why : unreadable + "; " + why;
      }
    }
    ProjectCaps serverManifest;
    try {
      serverManifest = projectConfiguration.apply(project).caps();
    } catch (RuntimeException invalid) {
      serverManifest =
          new ProjectCaps(
              ProjectCaps.Setting.UNSET,
              ProjectCaps.Setting.UNSET,
              ProjectCaps.Setting.UNSET,
              "The server manifest caps are not used: " + invalid.getMessage());
    }
    return overlay(
        overlay(merged(server, null, unreadable), serverManifest),
        overlay(merged(null, local, null), manifest));
  }

  /** Manifest caps override YAML defaults on the same side; local configuration wins key by key. */
  private static ProjectCaps overlay(ProjectCaps base, ProjectCaps override) {
    String why =
        base.unreadable() == null
            ? override.unreadable()
            : override.unreadable() == null
                ? base.unreadable()
                : base.unreadable() + "; " + override.unreadable();
    return new ProjectCaps(
        choose(base.steps(), override.steps()),
        choose(base.budget(), override.budget()),
        choose(base.autoContinue(), override.autoContinue()),
        choose(base.time(), override.time()),
        choose(base.failedChecks(), override.failedChecks()),
        override.autoIncrease().value() == null ? base.autoIncrease() : override.autoIncrease(),
        why);
  }

  private static ProjectCaps.Setting choose(
      ProjectCaps.Setting base, ProjectCaps.Setting override) {
    return override.value() == null || ProjectCaps.DEFAULT.equals(override.source())
        ? base
        : override;
  }

  /**
   * The two files' caps, the session's winning key by key — so a person who set only {@code steps}
   * keeps the server's {@code budget}. Nothing set is {@link ProjectCaps#NONE}, the records being
   * equal.
   *
   * @param server the server's file's caps, or null for none
   * @param session the rooting machine's file's caps, or null for none
   * @param unreadable why a file was not read, or null
   * @return each cap and its source
   */
  static ProjectCaps merged(
      EnvironmentFile.Caps server, EnvironmentFile.Caps session, String unreadable) {
    return new ProjectCaps(
        pick(session == null ? null : session.steps(), server == null ? null : server.steps()),
        pick(session == null ? null : session.budget(), server == null ? null : server.budget()),
        pick(
            session == null ? null : session.autoContinue(),
            server == null ? null : server.autoContinue()),
        pick(session == null ? null : session.time(), server == null ? null : server.time()),
        orDefault(
            pick(
                session == null ? null : session.failedChecks(),
                server == null ? null : server.failedChecks())),
        pick(
            session == null ? null : session.autoIncrease(),
            server == null ? null : server.autoIncrease()),
        unreadable);
  }

  private static ProjectCaps.BooleanSetting pick(Boolean session, Boolean server) {
    return session != null
        ? new ProjectCaps.BooleanSetting(session, ProjectCaps.PROJECT_FILE)
        : server != null
            ? new ProjectCaps.BooleanSetting(server, ProjectCaps.SERVER_FILE)
            : ProjectCaps.BooleanSetting.UNSET;
  }

  /** The failed-checks limit no file set is {@link ProjectCaps#DEFAULT_FAILED_CHECKS}. */
  private static ProjectCaps.Setting orDefault(ProjectCaps.Setting picked) {
    return picked.value() == null ? ProjectCaps.FAILED_CHECKS_DEFAULT : picked;
  }

  private static ProjectCaps.Setting pick(Integer session, Integer server) {
    return session != null
        ? new ProjectCaps.Setting(session, ProjectCaps.PROJECT_FILE)
        : server != null
            ? new ProjectCaps.Setting(server, ProjectCaps.SERVER_FILE)
            : ProjectCaps.Setting.UNSET;
  }
}
