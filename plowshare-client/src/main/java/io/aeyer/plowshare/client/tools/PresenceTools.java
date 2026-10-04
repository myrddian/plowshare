package io.aeyer.plowshare.client.tools;

import static io.aeyer.plowshare.client.tools.MemoryTools.oneLine;
import static io.aeyer.plowshare.client.tools.Schemas.object;
import static io.aeyer.plowshare.client.tools.Schemas.string;

import io.aeyer.plowshare.client.ClientPresence;
import io.aeyer.plowshare.client.files.Rooting;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Saying where this client is: one tool, and it is the only one in the whole MCP surface that acts
 * on this process rather than on the server.
 *
 * <h2>Why there is a command at all</h2>
 *
 * <p>{@code cli.Plowshare} takes {@code --workspace} and refuses to guess. This process is spawned
 * by somebody else's harness, before anyone has said what it should serve, and it must not lend
 * whatever directory it happened to be launched in — that would be the leash granting itself, from
 * a party nobody asked. So the rooting is a thing a person does through their harness, once, and
 * everything downstream follows from it.
 *
 * <h2>It is not a fifth project verb, and the description says so about all four</h2>
 *
 * <p>{@code ProjectTools} records the hazard this has to avoid: a model reads nothing but the
 * descriptions, so two tools that could each answer "point this project at where its files are" are
 * chosen between by coin flip, and the wrong pick is silent because either succeeds at what it
 * does. That file already has one such pair and each of the two names the other.
 *
 * <p>This one is a third thing and the distinction is not a shade of meaning: <b>every {@code
 * project_*} tool talks about a directory on the SERVER's disk, and this one talks about a
 * directory on the machine this process is running on.</b> They cannot be made to overlap, because
 * neither can reach the other's filesystem at all. What could still be confused is the
 * <em>intent</em> — "my project's files are over here" — so {@link #ROOT_DESCRIPTION} opens by
 * naming each of the four and saying which disk it means.
 *
 * <p>Its name carries the same load. Not {@code project_*}: the prefix is the first thing a model
 * groups by, and a fifth member of that family would inherit the family's subject — the server's
 * disk — before a word of the description was read.
 *
 * <h2>Every line at column zero is one this renderer wrote</h2>
 *
 * <p>{@code MemoryTools}' rule. A project name comes from the caller and a path comes from a
 * filesystem, so both go through {@link MemoryTools#oneLine}.
 */
public final class PresenceTools {

  private final ClientPresence presence;

  public PresenceTools(ClientPresence presence) {
    this.presence = Objects.requireNonNull(presence, "presence");
  }

  public void registerOn(ToolRegistry registry) {
    registry.register("client_root_project_here", ROOT_DESCRIPTION, rootSchema(), this::root);
  }

  // --- the tool -------------------------------------------------------------------

  /**
   * Declare that this machine serves one project's files.
   *
   * <p>Rendered from what {@link ClientPresence#root} answered rather than from what was asked for,
   * which is {@code ProjectTools}' rule and is load-bearing here: the root is resolved and the
   * machine name is one this process decided, so an echo of the request would show a caller
   * neither.
   */
  public Object root(Map<String, Object> args) {
    String project = required(args, "project");
    Path root = directory(required(args, "path"));

    Rooting was = presence.rooted();
    Rooting now;
    try {
      now = presence.root(root, project);
    } catch (IOException notAttached) {
      // The old presence was given back before the new claim went out, so
      // this process is now serving nothing — see ClientPresence's note on
      // the order. A caller told only that something failed would go on
      // believing the previous project is still rooted here.
      throw new IllegalStateException(
          "could not open a file channel for '"
              + oneLine(project)
              + "': "
              + notAttached.getMessage()
              + ". This client is now serving no files"
              + " at all"
              + (was == null
                  ? ""
                  : " — it stopped rooting '" + oneLine(was.project()) + "' before it tried")
              + ", so a run started now reaches only what the server itself can"
              + " see. Every other tool still works; try this one again once the"
              + " server is reachable.",
          notAttached);
    }

    StringBuilder out =
        new StringBuilder("This machine, '")
            .append(oneLine(now.machine()))
            .append("', now serves the project '")
            .append(oneLine(now.project()))
            .append("'. Its files are at ")
            .append(oneLine(now.root()))
            .append(".\n\n");
    if (was != null) {
      // Said out loud, because a second call is also a withdrawal and
      // nobody asked for that half.
      out.append("It stopped rooting '")
          .append(oneLine(was.project()))
          .append(
              "' to do it: this client roots one project at a time, because a"
                  + " project is one place. Nothing on the server changed, and that"
                  + " project's memories are untouched — what ended is this machine"
                  + " answering for its files.\n\n");
    }
    return out.append("A run in '")
        .append(oneLine(now.project()))
        .append(
            "' now reads and writes there through this process, whichever client"
                + " started it, as far as the running agent's own declared scopes allow."
                + " That one directory is the whole of what this machine lends: the leash"
                + " is checked here, at the moment a file would be opened, and no path"
                + " outside it is reachable however a run asks.\n\nCall this again to"
                + " serve somewhere else. It reconnects rather than editing what was"
                + " declared, because a presence is declared when the channel opens.")
        .toString();
  }

  // --- description ------------------------------------------------------------------

  static final String ROOT_DESCRIPTION =
      """
            Say that THIS machine — the one this Plowshare client is running on \
            — holds a project's files, and serve them. A run in that project \
            then reads and writes here, through this client, whichever machine \
            it was started from.

            This is the only tool that can talk about the machine you are on. \
            Every project tool talks about the SERVER's disk instead: \
            project_define gives a project a directory there, \
            project_workspace_set points it at a different one, project_lend \
            adds further directories and project_unlend takes them back, \
            project_move \
            renames a project, and project_forget drops the lot. None of \
            them can see the files where you are, and this one changes nothing \
            on the server's disk.

            It roots ONE directory, which is a choice about this tool and not a \
            limit on what a client can lend: `plowshare --workspace a,b` lends \
            both and roots the first, because a project's full name is \
            MACHINE/PATH/NAME and only one directory can be the PATH in it. \
            There is no client-side equivalent of project_lend yet.

            `path` is a directory on this machine and must be ABSOLUTE. A \
            relative path would be read against whatever directory this process \
            happened to be started in, which is the one directory nobody chose \
            to lend.

            Until you call this, this client serves no files at all: a run it \
            starts reaches only what the server itself can see, and says nothing \
            about the difference. That is a smaller capability rather than an \
            error.

            One project at a time, and one machine per project. Calling this \
            again serves somewhere else and stops serving what it served before \
            — the answer says which. If another live Plowshare client already \
            holds this project, the server turns this claim away, and a run \
            started afterwards reports the channel as down rather than quietly \
            reaching the wrong machine.""";

  // --- schema ---------------------------------------------------------------------

  private static Map<String, Object> rootSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("project", string("The project, by the same name a run gives it."));
    properties.put(
        "path",
        string(
            "The absolute path of its directory on this machine — the one this client is"
                + " running on, not the server."));
    return object(properties, List.of("project", "path"));
  }

  // --- arguments ---------------------------------------------------------------------

  /**
   * The directory this machine is being asked to serve.
   *
   * <p>Both checks are here and neither is a containment decision: the leash is {@code
   * ClientEnforcer}'s and is applied when a file would be opened. These are the two ways the
   * argument can be wrong in a manner nothing downstream would report as such — {@code
   * cli.Plowshare} makes the second check on {@code --workspace} for that reason, having found that
   * a typo announced a lending and then served nothing.
   */
  private static Path directory(String typed) {
    Path root;
    try {
      root = Path.of(typed);
    } catch (InvalidPathException notAPath) {
      throw new IllegalArgumentException(
          "'path' is not a path on this machine: " + oneLine(typed) + ". Nothing was rooted.");
    }
    if (!root.isAbsolute()) {
      throw new IllegalArgumentException(
          "'path' must be absolute, and "
              + oneLine(typed)
              + " is not. A relative path"
              + " would be read against whatever directory this client happened to"
              + " be started in, which is the one directory nobody chose to lend."
              + " Nothing was rooted.");
    }
    if (!Files.isDirectory(root)) {
      throw new IllegalArgumentException(
          "'path' names "
              + oneLine(root.toString())
              + ", which is not a directory on"
              + " this machine. A project cannot be served from a place that is not"
              + " there. Nothing was rooted.");
    }
    return root;
  }

  private static String required(Map<String, Object> args, String name) {
    Object value = args.get(name);
    String text = value == null ? null : value.toString();
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("'" + name + "' is required and must not be empty");
    }
    return text.trim();
  }
}
