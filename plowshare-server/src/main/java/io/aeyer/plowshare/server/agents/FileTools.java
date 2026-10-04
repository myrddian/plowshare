package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.FileResult;
import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Replacement;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.agents.ToolArguments.BadArguments;
import io.aeyer.plowshare.server.files.Changed;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.FileWords;
import io.aeyer.plowshare.server.files.ProviderRouter;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The file tools: read a window of one file, measure one without reading it, find files by name and
 * lines by what is in them, write one file, and ask what you can see at all.
 *
 * <h2>The two kinds of failure, at the one place they become visible</h2>
 *
 * <p>This is where the containment work meets a model, so it is where 3a's split is finally spent.
 * A {@link WorkspaceRefusedException} — a path outside every root, an absolute pattern, a read-only
 * grant, an ambiguity between two machines — is <b>a caller's mistake</b> and becomes the tool
 * result, exactly as {@code memory_recall}'s {@code limit} does. A {@code
 * WorkspaceUnavailableException} is <b>infrastructure</b> and is deliberately not caught here: it
 * leaves {@code run}, reaches {@code JobRuntime.dependencyFailure}, and ends the run. Neither is
 * ever an empty result dressed as an answer.
 *
 * <p>Nothing here re-words a refusal. The provider's sentences already distinguish states that a
 * tool cannot tell apart from the outside — which of the several reasons {@code LocalProvider}
 * enumerates leaves a root list empty, whether a workspace moved or was never defined — and they
 * are written for a model to read. Passing them through is the design; paraphrasing would be a
 * second copy of a distinction with no way to keep the two in step, and this javadoc proved it by
 * paraphrasing the count and getting it wrong.
 *
 * <p><b>Every tool's {@code run}, constructor null-check and {@code schema} are byte-identical,
 * deliberately.</b> {@link ToolArguments} owns that argument — the shape is written out there, and
 * the reason it is copied rather than factored into a base class is that a base class would fix the
 * entry shape for tools nobody has written yet. {@code MemoryTools} carries the same repetition for
 * the same reason.
 *
 * <h2>Why this class is in {@code agents} and not in {@code files}</h2>
 *
 * <p>The plan's file structure says {@code files/FileTools.java} and its interface list says this
 * consumes {@code ToolArguments.requireText}. Only one of those is possible: {@link ToolArguments}
 * is package-private to {@code agents} on purpose, and its {@code BadArguments} is a nested type of
 * it precisely so that <em>nothing outside this package can throw one into a tool's catch
 * clause</em>. Making it public to move this file would trade a structural guard for a directory
 * name. A tool is what this is — {@code MemoryTools} and {@code AgentRunTool} are its neighbours —
 * and {@code files/} keeps the seam.
 *
 * <h2>{@code home} is the runtime's, and every tool here proves it</h2>
 *
 * <p>No schema here has a project or a tier in it. {@link AgentTool} argues why at length; {@code
 * no_file_tool_lets_an_agent_name_its_own_tier} is what holds every one of them to it at once,
 * because a rule stated once and implemented once per tool is the rule the newest implementation
 * gets wrong.
 *
 * <h2>These are built per run, and that is not what this javadoc used to say</h2>
 *
 * <p>It said they were safe to share across jobs because a {@link ProviderRouter} holds no job's
 * state. The first half is still true of the router and the second half was never available to
 * these: {@code LocalProvider} carries <b>one agent's grants</b>, so the router a file tool holds
 * has to have been built knowing which definition is running, and one shared instance could not be.
 * {@link #of} is therefore called from {@code JobRuntime.offeredTo} once per run, over a router
 * built there — the same treatment {@link AgentRunTool} gets, and for the same reason.
 *
 * <p>What the router still buys, and the reason the tools take one rather than a provider, is that
 * the provider list is re-asked on <em>every</em> call: a job whose client connects or disappears
 * mid-run sees that, and a job whose project workspace is moved out from under it sees that too.
 */
public final class FileTools {

  public static final String CODE_MAP_NAME = CodeMapTool.NAME;
  public static final String READ_NAME = "file_read";
  public static final String STAT_NAME = "file_stat";
  public static final String GLOB_NAME = "file_glob";
  public static final String GREP_NAME = "file_grep";
  public static final String EDIT_NAME = "file_edit";
  public static final String DELETE_NAME = "file_delete";
  public static final String MOVE_NAME = "file_move";
  public static final String ROOTS_NAME = "file_roots";

  /**
   * How much of an answer a model is shown before it is cut.
   *
   * <p><b>A display limit, and not a second refusal.</b> {@code LocalProvider} refuses at 8 MiB and
   * refuses rather than truncating, on the argument that a silently shortened file is a wrong
   * answer. This is the other thing: the file is fine, the read succeeded, and what is bounded is
   * how much of it goes into one turn. Excalibur applies the same 100 000 characters in its own
   * {@code file_read}. The cut therefore <b>says so, and says how long the text really was</b> — an
   * answer that simply stopped would read to a model as the end of the file, which is the
   * confident-wrong-answer shape again with the tool's own hand on it.
   *
   * <h2>Kept, though a window now bounds a read before this can</h2>
   *
   * <p>{@link Window#MAX_WINDOW_BYTES} is 96 KiB of UTF-8 <em>and the joined text is never longer
   * in characters than it is in bytes</em>, so an ordinary window plus its continuation note lands
   * short of this ceiling and this constant does nothing to it. Removing it was the alternative,
   * and it is refused for two reasons that are separately sufficient.
   *
   * <p><b>No window a provider in this build cuts can reach it, and that is what a backstop is.</b>
   * One oversized line used to get past the window — {@link Window#cut} returned it whole — and
   * that was the one shape a read could reach this constant through. {@code cut} now refuses such a
   * line instead, so the arithmetic above has no exception left in it: every window is at most
   * {@link Window#MAX_WINDOW_BYTES} of UTF-8 and therefore at most that many characters, and a
   * continuation note is a couple of hundred more.
   *
   * <p>What is left is not this build's arithmetic. <b>{@code RemoteProvider} returns the span a
   * client sent, uncut</b> — deliberately, since re-cutting here would be the second answer to a
   * question the far side already answered — so the bound on a remote read is the bound the client
   * applied, and the two halves ship separately. A client that returned more than the window it was
   * asked for is the case this now stands against, and it is exactly the direction {@code
   * FileReply}'s javadoc argues for: a frame from a build this one does not know is read
   * conservatively rather than trustingly. {@code
   * a_window_from_a_client_that_ignored_it_is_cut_like_any _other_answer} is that shape and is what
   * holds this.
   *
   * <p><b>{@code file_glob} and {@code file_roots} are not windowed at all.</b> Neither a root list
   * nor a refusal sentence is bounded by {@link FileProvider}, and a remote provider assembles both
   * on another machine, so for those two this is the ordinary bound rather than a backstop.
   *
   * <p><b>Nothing pins the ceiling itself</b>, and saying so is better than a test that appears to.
   * The right number is a fraction of a model's context window, which lives in configuration this
   * class cannot see, and 100 000 is Excalibur's judgement carried over rather than a measurement.
   * {@code a_file_of_fifty_thousand_characters_is_shown_whole} holds the accepted side, and its
   * number is a literal rather than an expression over this constant: a fixture derived from the
   * value it pins moves with it and holds nothing.
   */
  static final int MAX_DISPLAY_CHARS = 100_000;

  /**
   * The most lines one {@code file_read} returns, whatever was asked for.
   *
   * <p><b>A bound on what a model can think about in one turn, and not on what a socket can
   * carry.</b> {@link Window#MAX_WINDOW_LINES} and {@link Window#MAX_WINDOW_BYTES} are the
   * transport's; they stay exactly where they are, and the reason there are two numbers now is that
   * one number serving both jobs is how the 1009 close happened. 96 KiB is a sane thing to put on a
   * socket and an unreasonable thing to put in front of a 9B model in one turn, and until this
   * constant existed the window a model got was sized only for the first of those.
   *
   * <h2>Where 300 comes from, which is a live run and not a round number</h2>
   *
   * <p>Measured 2026-09-01 against {@code qwen3.5-9b} at a loaded context of 128 000. The old
   * default of {@link Window#MAX_WINDOW_LINES} spent {@link Window#MAX_WINDOW_BYTES} at roughly 1
   * 600 lines of an ordinary markdown plan — 98 304 bytes, about 25 000 tokens, <b>a fifth of that
   * context in one tool result</b>. The prompt took over two minutes, the pool's 60-second {@code
   * chat-timeout} ended the run {@code UNAVAILABLE}, and the transport then retried by resending
   * the same oversized prompt.
   *
   * <p>Two points on that box are what this number is fitted to: <b>a 400-line read answered in 73
   * seconds and a ~1 600-line read did not answer at all.</b> 300 sits under the one that worked
   * rather than on it, and that gap is headroom in the dimension a line count cannot see for itself
   * — lines are not a constant size. Measured over this repository, {@code implementation
   * rationale} costs 60.2 bytes a line and this file costs 52.6, so 300 lines is roughly 16 000 to
   * 18 500 bytes; at the 3.9 bytes a token the live run's own figures imply, that is about 4 000 to
   * 4 700 tokens, near 3.5% of the context rather than 20%.
   *
   * <p><b>Not 100, though the operator's words offered it.</b> A turn on this box carries a large
   * fixed cost — the 400-line read spent 73 seconds on about 6 000 tokens of text — so a smaller
   * window does not buy time back in proportion; it multiplies the turns and pays that cost again
   * on each. The 4 795-line plan that failed is 16 reads at 300 and 48 at 100, and 48 turns of a
   * minute each is a different way to fail the same task.
   *
   * <p><b>Nothing here is derived from {@link #MAX_DISPLAY_CHARS} or from {@link Window}.</b> That
   * constant's javadoc argues the same separation from the other side: these three answer to
   * different pressures — a context window, a socket buffer, and an answer's readable length — and
   * a value that moved for one would move silently for the others.
   */
  static final int MAX_READ_LINES = 300;

  /**
   * Every name, for a runtime deciding what it can serve this boot.
   *
   * <p><b>Not a second list of tools.</b> {@link #of} is the only thing that builds one, and every
   * name here is a name it answers to — {@code
   * every_known_file_tool_name_builds_the_tool_of_that_name} is what holds the two together,
   * because a name in this set that {@code of} cannot build is exactly the superset {@code
   * JobRuntime.knownTools} exists to prevent: a definition would load naming it and then never be
   * offered it.
   *
   * <p>The other direction is why {@link #STAT_NAME} is in here before any agent file names it. A
   * name this set is missing refuses a shipped definition at boot, which is loud; a name it carries
   * that nothing serves is silent, and the agent simply cannot do what its prompt describes. Adding
   * the name first makes only the loud failure possible.
   */
  public static final Set<String> NAMES =
      Set.of(
          CODE_MAP_NAME,
          READ_NAME,
          STAT_NAME,
          GLOB_NAME,
          GREP_NAME,
          EDIT_NAME,
          DELETE_NAME,
          MOVE_NAME,
          ROOTS_NAME);

  /**
   * The files one run has read, which is what lets {@code file_edit} replace a whole file.
   *
   * <p>A whole-file write over a file the run never read is how an agent destroys a person's work
   * without having seen it, and nothing on the channel carries a modification time or a hash to
   * catch it any other way. So a write whose path is not in here goes out as {@link
   * FileProvider#create}, and the machine that owns the file refuses it if the file is there. What
   * is left is a change made after the read, which a whole write still overwrites; the spec
   * (2026-09-14, file_edit) records that.
   *
   * <p><b>{@code file_read} and hash-verified {@code code_map read} count.</b> {@code file_stat}
   * and {@code file_grep} see a length or a line, and neither is the file. A file the run wrote
   * whole or moved counts too: its contents are the run's own.
   *
   * <p><b>An edit of one piece of text does not</b>, though its answer shows the lines around the
   * change: those lines are a region and not the file, and a run that has seen ten lines of a
   * thousand-line file has not seen the rest of it. The one exception is an answer that is every
   * line of the file, uncut — which the file side's facts say ({@link Changed#showedWholeFile}),
   * and not a header in the words, since the words are this server's own and the facts are what the
   * machine holding the file found. That is a read in all but name. An edit never forgets a read,
   * either: the text it replaced was text the run had already seen or had just matched exactly.
   *
   * <p>Built once per run and shared by every file tool that run is offered, beside the router, in
   * {@code JobRuntime.offeredTo}. Concurrent, because a turn's tool calls may run at once.
   */
  public static final class Reads {

    private final Set<Path> paths = ConcurrentHashMap.newKeySet();
    private final Set<Path> hinted = ConcurrentHashMap.newKeySet();
    private volatile Set<String> offered = Set.of();
    private io.aeyer.plowshare.server.files.WorkspaceCodeMap codeMap;

    public Reads() {}

    public Reads(io.aeyer.plowshare.server.files.WorkspaceCodeMap codeMap) {
      this.codeMap = codeMap;
    }

    void offered(Set<String> names) {
      offered = Set.copyOf(names);
    }

    boolean canUse(String name) {
      return offered.contains(name);
    }

    boolean codeHint(Path path) {
      return canUse(CODE_MAP_NAME) && hinted.add(path.normalize());
    }

    void saw(Path path) {
      paths.add(path.normalize());
    }

    void forget(Path path) {
      paths.remove(path.normalize());
    }

    boolean seen(Path path) {
      return paths.contains(path.normalize());
    }
  }

  /**
   * One file tool, built over one run's router.
   *
   * <p>They are built per run rather than registered once, and that is forced rather than chosen: a
   * provider carries the running agent's grants, so nothing shared by every job can hold one.
   * {@link AgentRunTool} is built per run for the same shape of reason and {@code
   * JobRuntime.offeredTo} is where both happen.
   *
   * @throws IllegalArgumentException if the name is not one of {@link #NAMES}. An exception and not
   *     a tool result: the name here came from the runtime's own dispatch and never from a model,
   *     so this is a wiring bug and there is no turn that could correct it
   */
  public static AgentTool of(String name, ProviderRouter router) {
    return of(name, router, new Reads());
  }

  /** {@link #of(String, ProviderRouter)}, over the run's shared {@link Reads}. */
  public static AgentTool of(String name, ProviderRouter router, Reads reads) {
    Objects.requireNonNull(reads, "reads");
    if (CODE_MAP_NAME.equals(name)) {
      if (reads.codeMap == null)
        reads.codeMap = new io.aeyer.plowshare.server.files.WorkspaceCodeMap(router, () -> false);
      return new CodeMapTool(reads.codeMap, reads);
    }
    AgentTool tool =
        switch (name) {
          case READ_NAME -> new Read(router, reads);
          case STAT_NAME -> new Stat(router);
          case GLOB_NAME -> new Glob(router);
          case GREP_NAME -> new Grep(router);
          case EDIT_NAME -> new Edit(router, reads);
          case DELETE_NAME -> new Delete(router, reads);
          case MOVE_NAME -> new Move(router, reads);
          case ROOTS_NAME -> new Roots(router);
          default ->
              throw new IllegalArgumentException(
                  "there is no file tool called '"
                      + name
                      + "'; this class builds "
                      + new TreeSet<>(NAMES));
        };
    if (reads.codeMap == null || !Set.of(EDIT_NAME, DELETE_NAME, MOVE_NAME).contains(name))
      return tool;
    return new AgentTool() {
      @Override
      public ToolSchema schema() {
        return tool.schema();
      }

      @Override
      public String run(String arguments, Home home) {
        reads.codeMap.beforeMutation(home);
        try {
          return tool.run(arguments, home);
        } finally {
          var changed = new ArrayList<Path>();
          try {
            var args = ToolArguments.parse(arguments, name, "a file change");
            for (String key : List.of("path", "to"))
              if (args.path(key).isTextual())
                changed.add(Path.of(args.path(key).asText()).normalize());
          } catch (BadArguments | InvalidPathException ignored) {
          }
          reads.codeMap.afterMutation(home, changed);
        }
      }
    };
  }

  private FileTools() {}

  // --- file_read ---------------------------------------------------------------

  /**
   * {@code file_read} — one window of one file's text, and, when the window does not reach the end,
   * the call that fetches the next one.
   */
  public static final class Read implements AgentTool {

    private final ProviderRouter router;
    private final Reads reads;
    private final ToolSchema schema;

    public Read(ProviderRouter router) {
      this(router, new Reads());
    }

    public Read(ProviderRouter router, Reads reads) {
      this.router = Objects.requireNonNull(router, "router");
      this.reads = Objects.requireNonNull(reads, "reads");
      Map<String, Object> properties = new LinkedHashMap<>();
      properties.put(
          "path",
          ToolArguments.string("The absolute path of the file to read, as file_roots spells it."));
      properties.put(
          "offset",
          ToolArguments.integer(
              "Which line to start at. The first line of a file is 0. Omit to start"
                  + " at the beginning."));
      properties.put(
          "limit",
          ToolArguments.integer(
              "How many lines to read at most, from 1 to "
                  + MAX_READ_LINES
                  + ". Omit for "
                  + MAX_READ_LINES
                  + "; a larger number is brought"
                  + " down to it rather than refused, and the answer says so."));
      this.schema =
          new ToolSchema(
              READ_NAME, READ_DESCRIPTION, ToolArguments.object(properties, List.of("path")));
    }

    @Override
    public ToolSchema schema() {
      return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
      // Outside the try: a null here is the runtime's bug and not the
      // model's. See AgentTool.
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      Objects.requireNonNull(home, "home");
      try {
        return answer(argumentsJson, home);
      } catch (BadArguments | WorkspaceRefusedException correctable) {
        // The two halves of "the caller was wrong", and nothing else.
        // WorkspaceUnavailableException shares no supertype with the
        // second of these, which is what lets this clause take one and
        // not the other — see WorkspaceUnavailableException, which owns
        // that argument.
        return correctable.getMessage();
      }
    }

    private String answer(String argumentsJson, Home home) {
      JsonNode args =
          ToolArguments.parse(
              argumentsJson,
              READ_NAME,
              "{\"path\": \"/srv/repo/src/Main.java\", \"offset\": 0, \"limit\": 500}");
      Path path = path(args, READ_NAME);
      Asked asked = window(args);
      FileProvider provider = router.providerFor(home, path);
      Span span = provider.read(path, asked.window());
      reads.saw(path);
      var type = io.aeyer.plowshare.protocol.DocumentType.classify(path.toString(), null);
      String rendered = render(span, path, asked);
      return type.isCode()
          ? "[file_read metadata: corpus=code; type=code; subtype="
              + type.subtype()
              + "; language inferred from filename; offsets count from 0. "
              + "This is a live file window, not an indexed document revision."
              + (reads.codeHint(path)
                  ? " Harness hint: use code_map overview for the repository map,"
                      + " symbols for a declaration-name prefix, or outline for this absolute path"
                      + " within the selected pattern. code_map read uses the returned source_hash"
                      + " and UTF-16 offsets; file_read offsets count lines."
                      + " Check coverage before treating a missing symbol as absent."
                  : "")
              + "]\n\n"
              + rendered
          : rendered;
    }

    /**
     * The window that was read, beside the {@code limit} the model actually sent.
     *
     * <p><b>{@link Span} cannot carry this and should not learn to.</b> It is what a read returned,
     * assembled by whichever provider ran and possibly on another machine; the number a model typed
     * is a fact about the request, known only here, and it stops being available the moment {@link
     * #window} clamps it. Keeping the two together for the length of one call is the whole of what
     * {@link #render} needs to tell a reduced window apart from a short file.
     */
    private record Asked(Window window, int limit) {

      /**
       * Whether the number that came back is the model's or this tool's.
       *
       * <p>Written against {@code window.limit()} rather than against {@link
       * FileTools#MAX_READ_LINES} so that it stays true of whatever actually bounded the window.
       * {@link Window#of} clamps too, and today its clamp is unreachable from here — {@code
       * the_cap_on_a_turn_is_below_the_cap_on_the_wire} is what keeps that so — but a comparison
       * against a constant would go quietly wrong on the day it is not.
       */
      boolean reduced() {
        return limit > window.limit();
      }
    }

    /**
     * The window a model asked for, or the refusal saying what to send instead.
     *
     * <p><b>Both floors are checked here and not left to {@link Window}.</b> That record's
     * constructor refuses a negative offset and a limit below one with an {@link
     * IllegalArgumentException}, which shares no supertype with the two this tool catches — so a
     * model that typed a minus sign would end its own job rather than get a sentence back. Its
     * javadoc says the throw is the guard from every direction except {@link Window#of}; this is
     * that direction.
     *
     * <p>The ceiling is not refused either, for {@link Window#of}'s reason: a caller that has not
     * read the file yet cannot know how long it is, so asking for more than a window holds is what
     * it <em>should</em> do, and a refusal would cost it a turn to learn a number this method
     * already knows. What differs from {@code Window.of} is which ceiling. <b>The clamp here is
     * {@link FileTools#MAX_READ_LINES} and it happens before {@code Window.of} ever sees the
     * number</b>, so the transport's bound is left holding the wire and this one holds the turn.
     * The omitted-limit default is the same constant rather than the transport's: an omitted limit
     * is a model asking for as much as it can get, and the honest answer to that is the cap.
     *
     * <p>Nothing about either clamp is silent — {@link #more} says which number won when the two
     * disagree.
     */
    private static Asked window(JsonNode args) {
      int offset = ToolArguments.optionalInt(args, "offset", 0, Read::badOffset);
      if (offset < 0) {
        throw new BadArguments(
            READ_NAME
                + " was given an 'offset' of "
                + offset
                + ". It is a line number, the first line of a file is 0, and there is"
                + " nothing before that. Leave it out to start at the beginning.");
      }
      int limit = ToolArguments.optionalInt(args, "limit", MAX_READ_LINES, Read::badLimit);
      if (limit < 1) {
        throw new BadArguments(
            READ_NAME
                + " was given a 'limit' of "
                + limit
                + ". It must be 1 or more: a window carrying no lines while still"
                + " reporting that the file continues is a loop with no way out of it."
                + " Leave it out for "
                + MAX_READ_LINES
                + ".");
      }
      return new Asked(Window.of(offset, Math.min(limit, MAX_READ_LINES)), limit);
    }

    private static BadArguments badOffset(JsonNode offset) {
      return new BadArguments(
          READ_NAME
              + " could not read 'offset': it must be a whole"
              + " number, the line to start at, not "
              + offset
              + ". Leave it out to start"
              + " at the beginning of the file.");
    }

    private static BadArguments badLimit(JsonNode limit) {
      return new BadArguments(
          READ_NAME
              + " could not read 'limit': it must be a whole"
              + " number from 1 to "
              + MAX_READ_LINES
              + ", not "
              + limit
              + ". Leave it out for "
              + MAX_READ_LINES
              + ".");
    }

    /**
     * A window, and — when it is not the end of the file — the call that fetches the next one.
     *
     * <h2>The note goes in front of the text, and that is not a style choice</h2>
     *
     * <p>{@link #display} cuts the tail, so a continuation appended after the lines is the first
     * thing deleted. {@code file_glob} has the worked example of what that costs, in a comment
     * recording a note that vanished behind 1941 paths. It is reachable here rather than
     * theoretical, and no longer through this build's own arithmetic: {@link Window#cut} used to
     * return an oversized line whole and now refuses it, so what is left is a provider on the other
     * side of a wire returning a span wider than the window it was asked for — which {@code
     * RemoteProvider} passes through uncut, as {@link #MAX_DISPLAY_CHARS} explains at length.
     * {@code the_continuation_note_survives_an_answer_long_enough_to_be_cut} is still what holds
     * it, over that fixture instead.
     *
     * <p><b>A read that reaches the end carries no note at all</b>, which is what keeps "the file's
     * own lines and nothing the tool added" true of every read that could be confused with one.
     * There is nothing to say: no next call exists, and {@link Span#more()} being false is the fact
     * the absence states.
     *
     * <h2>Three answers that are not the file's text</h2>
     *
     * <p>An empty file, an offset past the end, and a window with more after it. The first two are
     * separate deliberately: {@code totalLines} is what distinguishes a file with nothing in it
     * from a caller that has paged one window too far, and the earlier version of this method could
     * not tell them apart — it tested the joined text for emptiness and would have called a
     * 4000-line file empty for an offset of 5000. A model that believed that stops looking.
     *
     * <p>None of the three goes through {@link #display}, for {@code file_edit}'s reason: each is
     * built from a path and numbers this class already holds.
     */
    private static String render(Span span, Path path, Asked asked) {
      if (span.totalLines() == 0) {
        // AgentTool: never blank. An empty string handed back reads to a
        // model as a tool that does not work, where "the file is empty"
        // is a fact it can act on.
        return "The file " + path + " is empty.";
      }
      if (span.lines().isEmpty()) {
        return "The file "
            + path
            + " has "
            + span.totalLines()
            + " lines, so there is"
            + " nothing at offset "
            + span.offset()
            + " — the last line is at offset "
            + (span.totalLines() - 1)
            + ". This is the end of the file and not a"
            + " failure; read from offset 0 to start again.";
      }
      // Joined with "\n", not the file's own bytes: the lines are the
      // reply's only carrier now and a terminator is not part of the line
      // it ended, so a file's trailing newline and its CRLFs are already
      // gone before this line runs. FileReply and LocalProvider.lines own
      // that argument.
      String content = String.join("\n", span.lines());
      if (!span.more()) {
        // A reduced limit says nothing here, deliberately, and that is
        // the one place this note is suppressed. The model asked for
        // more lines than this tool returns and got the whole file
        // anyway: the cap cost it nothing, there is no next call, and a
        // sentence about a limit would send it paging into an offset
        // past the end. `more` is the right test rather than "did the
        // span come back shorter than the cap" — the two differ for a
        // file that is exactly the cap long, and only one of them is
        // about whether anything was withheld.
        return display(content, "the file " + path);
      }
      return display(more(span, asked) + "\n\n" + content, "the file " + path);
    }

    /**
     * What was shown, how long the file is, and the exact next call.
     *
     * <p><b>Three facts, and the third is the one this codebase had nowhere else.</b> {@link
     * #display}'s cut says how much was kept and how much there was and stops there, which leaves a
     * model to work out for itself that there is a next call and what to put in it. The research
     * this slice followed rejected the alternative of teaching that in a prompt explicitly: the
     * failure arrives mid-task, and a static instruction does not reliably reach the retry
     * decision, where the tool's own answer is present exactly when the model must act.
     *
     * <p><b>The next offset is a number and not an instruction to add two.</b> Every count here is
     * in the same coordinate as the {@code offset} argument — from zero — so that nothing in the
     * sentence has to be converted before it is typed back.
     *
     * <p><b>{@link Span#BYTES} gets its own sentence</b>, because it carries a fact {@link
     * Span#LINES} does not: a wider {@code limit} would return this same text. Without it a model
     * reads a short window as a small allowance and spends its next turn asking for a bigger one,
     * which is precisely the wasted turn {@code Span} splits the two constants apart to prevent.
     *
     * <h2>A fourth fact, when the tool overruled the model</h2>
     *
     * <p><b>{@link FileTools#MAX_READ_LINES} is a thing this tool knows and the model does not, so
     * it has to be said rather than inferred.</b> A model that sent {@code limit: 800} and received
     * 300 lines sees a range and a next offset — which are <em>exactly</em> the clues an ordinary
     * short window gives it — and the reading available from those alone is that the file is
     * shorter than it asked about. It is not; its own argument was overruled. That is the whole
     * reason this sentence exists, and it is the half of this change that is easy to leave out
     * because everything still works without it and only the model is wrong.
     *
     * <p>It goes <b>first</b>, ahead of the range: it is the fact that changes how the rest of the
     * note reads, and it is a correction to something the caller already believes rather than an
     * addition to something it does not. Both sentences are inside the one bracket, so {@link
     * #display}'s tail cut cannot reach either.
     *
     * <p><b>It names both numbers.</b> The one that was asked for, so the model can see which of
     * its own arguments was overruled rather than guessing, and the one that won, so the next call
     * is not another attempt at the first. And it says a repeat would come back the same: without
     * that, "reduced" reads as a transient shortage and the obvious next move is to ask again.
     *
     * <p>Distinct in wording from {@link Span#BYTES}' sentence on purpose, because both can be true
     * at once — a dense file whose window filled on size after a limit was already reduced. {@code
     * a_window_cut_by_the_turn_cap_reads_differently_from_one_that_merely_has_more} is what holds
     * the distinction that matters more: this sentence is absent from every window whose limit was
     * honoured.
     */
    private static String more(Span span, Asked asked) {
      int next = span.offset() + span.lines().size();
      StringBuilder note = new StringBuilder("[");
      if (asked.reduced()) {
        note.append("Your 'limit' of ")
            .append(asked.limit())
            .append(" was reduced to ")
            .append(asked.window().limit())
            .append(", which is the most ")
            .append(READ_NAME)
            .append(" returns in one answer whatever is asked for. That is this")
            .append(" tool's own bound and not the length of the file, and sending")
            .append(" ")
            .append(asked.limit())
            .append(" again returns this same window. ");
      }
      note.append("Lines ")
          .append(span.offset())
          .append(" to ")
          .append(next - 1)
          .append(" of ")
          .append(span.totalLines())
          .append(", counting from 0 as offset does.");
      if (Span.BYTES.equals(span.stoppedBy())) {
        note.append(
            " This window filled up on size before it ran out of lines, so a"
                + " larger limit would return this same text.");
      }
      note.append(" There is more of this file: call ")
          .append(READ_NAME)
          .append(" again with offset=")
          .append(next)
          .append(".]");
      return note.toString();
    }
  }

  // --- file_stat ---------------------------------------------------------------

  /**
   * {@code file_stat} — how many lines a file has, without any of them.
   *
   * <p>Free in the sense {@code file_roots} is: it costs a turn and no model call, and it turns a
   * read into something a model can plan. {@link FileProvider#stat} owns the argument for why the
   * answer is a line count and not a byte size, and for why it refuses everything a read refuses.
   */
  public static final class Stat implements AgentTool {

    private final ProviderRouter router;
    private final ToolSchema schema;

    public Stat(ProviderRouter router) {
      this.router = Objects.requireNonNull(router, "router");
      this.schema =
          new ToolSchema(
              STAT_NAME,
              STAT_DESCRIPTION,
              pathSchema("The absolute path of the file to measure, as file_roots spells it."));
    }

    @Override
    public ToolSchema schema() {
      return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      Objects.requireNonNull(home, "home");
      try {
        return answer(argumentsJson, home);
      } catch (BadArguments | WorkspaceRefusedException correctable) {
        return correctable.getMessage();
      }
    }

    private String answer(String argumentsJson, Home home) {
      JsonNode args =
          ToolArguments.parse(argumentsJson, STAT_NAME, "{\"path\": \"/srv/repo/src/Main.java\"}");
      Path path = path(args, STAT_NAME);
      FileProvider provider = router.providerFor(home, path);
      Span span = provider.stat(path);
      if (span.totalLines() == 0) {
        // The same sentence file_read gives, deliberately: a model that
        // stats an empty file and then reads it must not get two
        // different accounts of the same fact.
        return "The file " + path + " is empty.";
      }
      // How many windows that is, is NOT worked out here. It would be
      // (total + MAX_READ_LINES - 1) / MAX_READ_LINES only for a file whose
      // lines are short enough that the byte ceiling never fires, and a
      // number that is right for most files and quietly low for the ones
      // worth stat-ing is the confident wrong answer again.
      //
      // MAX_READ_LINES and not MAX_WINDOW_LINES: this sentence is the one
      // thing that turns a length into a plan, and quoting the transport's
      // bound here would have a model plan its reads in units six times
      // larger than file_read will actually hand it.
      return "The file "
          + path
          + " has "
          + span.totalLines()
          + " lines. "
          + READ_NAME
          + " carries at most "
          + MAX_READ_LINES
          + " of them in one call, and"
          + " fewer when the lines are long.";
    }
  }

  // --- file_glob ---------------------------------------------------------------

  /** {@code file_glob} — which files exist, without opening any of them. */
  public static final class Glob implements AgentTool {

    private final ProviderRouter router;
    private final ToolSchema schema;

    public Glob(ProviderRouter router) {
      this.router = Objects.requireNonNull(router, "router");
      Map<String, Object> properties = new LinkedHashMap<>();
      properties.put(
          "pattern",
          ToolArguments.string("A glob relative to a root, like src/**/*.java. Never absolute."));
      this.schema =
          new ToolSchema(
              GLOB_NAME, GLOB_DESCRIPTION, ToolArguments.object(properties, List.of("pattern")));
    }

    @Override
    public ToolSchema schema() {
      return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      Objects.requireNonNull(home, "home");
      try {
        return answer(argumentsJson, home);
      } catch (BadArguments | WorkspaceRefusedException correctable) {
        return correctable.getMessage();
      }
    }

    /**
     * Every provider is searched, because a pattern names no filesystem.
     *
     * <p>{@code file_read} routes; this cannot, so it asks all of them — and <b>a provider that
     * refused is named beside the hits of one that answered</b>. Reporting only the hits would be a
     * partial search presented as a whole answer, which is the confident empty answer with extra
     * steps: two filesystems, one searched, and a listing that does not say so. Refusing the whole
     * call instead would make a job that has a working remote workspace and an empty local one
     * unable to search at all, which is worse for no gain — the refusal is already in the answer,
     * in words.
     *
     * <h2>An unavailable half is not treated like a refused half, and the asymmetry is the point
     * </h2>
     *
     * <p>A {@link WorkspaceRefusedException} from one provider is caught above and rendered; a
     * {@code WorkspaceUnavailableException} is not caught anywhere and ends the run. Two different
     * answers to what looks like one question, so it needs saying rather than noticing.
     *
     * <p><b>The difference is whether the provider knew its own answer.</b> A refusal is a complete
     * statement — "this half has no workspace, and here is which of the states {@code
     * LocalProvider} enumerates that is" — so the listing can carry it and still be true about
     * everything in it. An outage is the absence of a statement: the tree may hold a hundred
     * matches or none, and nothing that goes in the listing about it can be true. A listing that
     * named an unavailable half would be exactly the confident partial answer the paragraph above
     * refuses, only with the tool's own hand on it.
     *
     * <p>So {@code file_read} and {@code file_glob} really do differ, and consistently: a dead
     * provider is tolerable when the question is <em>one path</em> that plainly belongs to somebody
     * else — {@code ProviderRouter} argues that, and records what it costs — and is not tolerable
     * when the question is "what is out there", which is everything, and which {@code file_glob}
     * and {@code file_roots} both ask. A job with a healthy remote and a dead local can read a
     * remote file and cannot list its roots; that is not an oversight, it is the two questions
     * being different questions.
     */
    private String answer(String argumentsJson, Home home) {
      JsonNode args =
          ToolArguments.parse(argumentsJson, GLOB_NAME, "{\"pattern\": \"src/**/*.java\"}");
      String pattern =
          ToolArguments.requireText(
              args, "pattern", GLOB_NAME, "a glob relative to a root, like src/**/*.java");

      List<FileProvider> providers = router.providersFor(home);
      if (providers.isEmpty()) {
        return NO_FILESYSTEM;
      }
      List<Path> hits = new ArrayList<>();
      List<String> searched = new ArrayList<>();
      List<String> refused = new ArrayList<>();
      for (FileProvider provider : providers) {
        try {
          // roots() before glob(), so a provider whose workspace has
          // vanished says so before a tree is walked rather than
          // after.
          //
          // NO WORSE, rather than better, and the qualifier is the
          // honest one. Both orders end a run identically THROUGH
          // LocalProvider, because both of its calls route through
          // reachable(leash()); that is a property of that class and
          // not of FileProvider, and RemoteProvider is unwritten. The
          // trade is also two-sided: this saves a discarded walk on
          // the outage path and adds a whole roots() call on the
          // refusal path, which for a remote is an extra round trip
          // and is the commoner case.
          //
          // `searched` is only added to once the search has actually
          // succeeded, because it is a claim about what ran.
          List<Path> roots = provider.roots();
          hits.addAll(provider.glob(pattern));
          for (Path root : roots) {
            searched.add(root.toString());
          }
        } catch (WorkspaceRefusedException named) {
          refused.add(provider.name() + ": " + named.getMessage());
        }
      }

      StringBuilder out = new StringBuilder();
      if (searched.isEmpty()) {
        // Nothing ran at all, so there is no search to report the result
        // of. Saying "no matches" here is the sentence that cost one of
        // Excalibur's agents 15 of its 16 turns. The literal is appended
        // unconditionally: `refused` can be empty here too, through a
        // provider that answers a search it has no root for, and an
        // answer assembled only from `refused` would be blank.
        out.append("Nothing was searched.");
        if (!refused.isEmpty()) {
          out.append(' ').append(String.join(" ", refused));
        }
        return display(out.toString(), "this answer");
      }
      if (!refused.isEmpty()) {
        // BEFORE the hits, and the ordering is the whole of the fix.
        // `display` cuts the tail, so this note appended after three
        // thousand paths is the first thing deleted — measured at 3000
        // hits and one refusing provider: the note was gone, and what
        // survived was 1941 paths from one filesystem under a cut saying
        // "not the end of this listing", which reads as THERE ARE MORE
        // MATCHING FILES rather than A SECOND FILESYSTEM WAS NEVER
        // SEARCHED. That is verbatim the failure this method's javadoc
        // forbids, produced by this method. One provider leaves
        // `refused` always empty, which is the only reason it has never
        // bitten -- and production still has exactly one, because the
        // remote provider is a prepared seam that nothing constructs.
        // This comment predicted "task 7 makes two providers ordinary";
        // task 7 built the channel and task 10 wired the local provider
        // alone, so the prediction is recorded as unfulfilled rather
        // than left reading as a description of today.
        out.append("Not searched — ").append(String.join(" ", refused)).append("\n\n");
      }
      if (hits.isEmpty()) {
        out.append("No file matching '")
            .append(pattern)
            .append("' was found under ")
            .append(String.join(", ", searched))
            .append(". The search ran; this is what it found.");
      } else {
        out.append(hits.size())
            .append(hits.size() == 1 ? " file" : " files")
            .append(" matching '")
            .append(pattern)
            .append("', under ")
            .append(String.join(", ", searched))
            .append(":");
        for (Path hit : hits) {
          out.append('\n').append(hit);
        }
      }
      return display(out.toString(), "this listing");
    }
  }

  // --- file_grep ---------------------------------------------------------------

  /**
   * {@code file_grep} — which lines contain a piece of text, and the offset that reads each of
   * them.
   *
   * <h2>The offset is the answer</h2>
   *
   * <p>The measurement in the design spec is fourteen {@code file_read} calls to reach line 4133 of
   * a 4 795-line file, and sixteen turns spent without one. What replaces it is a search whose
   * every match already carries the number {@code file_read}'s {@code offset} takes — so {@link
   * #render} spells that number as {@code offset=}, the same way {@code file_read}'s own
   * continuation note spells the next one. <b>A match rendered as {@code path:4133:} would have
   * been the familiar shape and the wrong one</b>: that is a line number by every convention a
   * model has read, line numbers start at one, and {@link Found.Match#offset()} counts from zero.
   * The two spellings differ by exactly the off-by-one that would put every follow-up read one line
   * late while every match still looked right.
   *
   * <h2>Two shapes of call, and they route differently</h2>
   *
   * <p>A named {@code path} is {@code file_read}'s question — one file, on one filesystem — so it
   * routes. An absent one is {@code file_glob}'s: a needle names no filesystem, so every provider
   * is asked and one that refused is named beside the hits of one that answered. {@link
   * Glob#answer} owns that argument in full, including why an unavailable provider is not treated
   * like a refused one.
   */
  public static final class Grep implements AgentTool {

    /**
     * What marks a line that was cut, and what a reader is told it means.
     *
     * <p>Per match, because {@link Needle#MAX_LINE_CHARS} is per match — {@link Found}'s javadoc
     * says why the two caps are reported in different places — and explained once above the list
     * rather than fifty times inside it.
     */
    private static final String CUT = "[cut]";

    private final ProviderRouter router;
    private final ToolSchema schema;

    public Grep(ProviderRouter router) {
      this.router = Objects.requireNonNull(router, "router");
      Map<String, Object> properties = new LinkedHashMap<>();
      properties.put(
          "text",
          ToolArguments.string(
              "The text to find, matched literally and not as a regular expression."));
      properties.put(
          "path",
          ToolArguments.string(
              "One file or one directory to search, as file_roots spells it. Omit to"
                  + " search everything this job can reach."));
      properties.put(
          "ignore_case",
          ToolArguments.flag(
              "Whether a finds A. Omit for a search that matches the case you typed."));
      this.schema =
          new ToolSchema(
              GREP_NAME, GREP_DESCRIPTION, ToolArguments.object(properties, List.of("text")));
    }

    @Override
    public ToolSchema schema() {
      return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      Objects.requireNonNull(home, "home");
      try {
        return answer(argumentsJson, home);
      } catch (BadArguments | WorkspaceRefusedException correctable) {
        return correctable.getMessage();
      }
    }

    private String answer(String argumentsJson, Home home) {
      JsonNode args =
          ToolArguments.parse(
              argumentsJson, GREP_NAME, "{\"text\": \"class Main\", \"path\": \"/srv/repo/src\"}");
      Needle needle = needle(args);
      Path path = optionalPath(args, GREP_NAME);
      if (path != null) {
        FileProvider provider = router.providerFor(home, path);
        Found found = provider.grep(needle, path);
        return render(found.matches(), found.capped(), needle, path.toString(), List.of());
      }

      List<FileProvider> providers = router.providersFor(home);
      if (providers.isEmpty()) {
        return NO_FILESYSTEM;
      }
      List<Found.Match> matches = new ArrayList<>();
      List<String> searched = new ArrayList<>();
      List<String> refused = new ArrayList<>();
      boolean capped = false;
      for (FileProvider provider : providers) {
        try {
          // roots() before grep(), and `searched` filled only once the
          // search has actually run: file_glob's answer() carries both
          // arguments and neither is different here.
          List<Path> roots = provider.roots();
          Found found = provider.grep(needle, null);
          // The allowance is one provider's, so two of them could
          // return MAX_MATCHES each and this tool would hand back
          // twice what Needle sized for a turn. Filled to the cap and
          // then reported, rather than concatenated: a match dropped
          // here is a match found and not shown, which is the same
          // fact Needle.find returns true for and calls for the same
          // sentence. Nothing in production builds a second provider
          // today, which is exactly when this is cheap to get right.
          for (Found.Match match : found.matches()) {
            if (matches.size() >= Needle.MAX_MATCHES) {
              capped = true;
              break;
            }
            matches.add(match);
          }
          capped = capped || found.capped();
          for (Path root : roots) {
            searched.add(root.toString());
          }
        } catch (WorkspaceRefusedException named) {
          refused.add(provider.name() + ": " + named.getMessage());
        }
      }
      if (searched.isEmpty()) {
        // Nothing ran, so there is no search whose result could be
        // reported. "No line contains that" here is the sentence that
        // cost one of Excalibur's agents 15 of its 16 turns; file_glob's
        // answer() records it at length.
        StringBuilder out = new StringBuilder("Nothing was searched.");
        if (!refused.isEmpty()) {
          out.append(' ').append(String.join(" ", refused));
        }
        return display(out.toString(), "this answer");
      }
      return render(matches, capped, needle, String.join(", ", searched), refused);
    }

    /**
     * What to look for, or the refusal saying what to send instead.
     *
     * <p><b>{@code requireExactText} and not {@code requireText}, which is {@code file_edit}'s
     * choice arrived at from somewhere else.</b> That method strips, and a needle's own leading and
     * trailing spaces are part of what is being looked for: an agent searching for {@code "} else
     * {"} or for an indented line is asking about whitespace, and a tool that quietly searched for
     * something narrower would answer a question it was not asked, with matches in front of it to
     * make the answer look right.
     *
     * <p>The blank check is therefore this method's own rather than {@code requireExactText}'s, and
     * it has to exist here: {@link Needle}'s constructor refuses a blank with an {@link
     * IllegalArgumentException}, which shares no supertype with the two {@link #run} catches — so a
     * model that sent {@code ""} would end its own job instead of being told a sentence it could
     * act on. {@code FileTools.Read.window} guards {@link Window}'s constructor from the same
     * direction and for the same reason.
     */
    private static Needle needle(JsonNode args) {
      String text =
          ToolArguments.requireExactText(
              args, "text", GREP_NAME, "the text to find, matched literally");
      if (text.isBlank()) {
        throw new BadArguments(
            GREP_NAME
                + " was given a 'text' of '"
                + text
                + "'."
                + " Every line contains the empty string and almost every line of"
                + " source contains a space, so there is nothing that search could"
                + " tell you. Send the words you are looking for.");
      }
      boolean ignoreCase = ToolArguments.optionalFlag(args, "ignore_case", false, Grep::badFlag);
      return new Needle(text, ignoreCase);
    }

    private static BadArguments badFlag(JsonNode sent) {
      return new BadArguments(
          GREP_NAME
              + " could not read 'ignore_case': it must be"
              + " true or false, not "
              + sent
              + ". Leave it out for a search that"
              + " matches the case you typed.");
    }

    /**
     * The matches, and what to do about the two things that could be missing from them.
     *
     * <h2>The cap is reported and the remedy is not a next page</h2>
     *
     * <p><b>{@code file_read}'s continuation names a number to send back; this one deliberately
     * cannot.</b> {@link Found}'s javadoc owns the argument — a search stops the moment its
     * allowance is spent, precisely so a common needle does not walk and decode the rest of the
     * tree, so there is no total to report and an offer of "the next fifty" would be the same walk
     * again per fifty. The sentence therefore says what asking again would do, which is return
     * these same lines, and names the two moves that are actually available: a longer needle, or a
     * {@code path}.
     *
     * <p>It is worded to {@code file_read}'s reduced-limit note, which is the calibration: name
     * both the number and whose bound it is, and say what a repeat would get you. What it must not
     * borrow from that note is its shape, because that one ends in an offset.
     *
     * <h2>Order, for {@link #display}'s reason</h2>
     *
     * <p>Everything that is not a match goes in front of the matches, since {@link #display} cuts
     * the tail — {@code file_glob}'s "Not searched" note carries the worked example of what a note
     * appended after a listing costs. Fifty matches at {@link Needle#MAX_LINE_CHARS} do not reach
     * the display limit on their own; a provider on another machine that returns long paths, or a
     * refusal sentence it wrote itself, is what makes this reachable, and it is the same reason
     * {@code file_glob} and {@code file_roots} go through {@link #display} at all.
     */
    private static String render(
        List<Found.Match> matches,
        boolean capped,
        Needle needle,
        String where,
        List<String> refused) {

      StringBuilder out = new StringBuilder();
      if (!refused.isEmpty()) {
        out.append("Not searched — ").append(String.join(" ", refused)).append("\n\n");
      }
      if (matches.isEmpty()) {
        // Never a bare "no matches": where it looked is what tells a
        // model that the search ran at all, and file_glob's empty answer
        // is worded the same way for the same reason.
        out.append("No line containing '")
            .append(needle.text())
            .append("' was found in ")
            .append(where)
            .append(". The search ran; this is what it found.");
        return display(out.toString(), "this answer");
      }
      if (capped) {
        out.append("[Stopped at ")
            .append(Needle.MAX_MATCHES)
            .append(" matches, which is the most ")
            .append(GREP_NAME)
            .append(" returns in one answer. That is this tool's own bound and not")
            .append(" the number of lines that contain '")
            .append(needle.text())
            .append("' — the search stopped rather than reading the rest of the")
            .append(" tree, so there is no count of those and no next page to ask")
            .append(" for, and sending this again returns these same lines. To")
            .append(" reach what is not here, search for something longer, or give")
            .append(" a 'path' to search one file or one directory.]\n\n");
      }
      if (matches.stream().anyMatch(Found.Match::truncated)) {
        out.append("[A line marked ")
            .append(CUT)
            .append(" was longer than ")
            .append(Needle.MAX_LINE_CHARS)
            .append(" characters and is shown only")
            .append(" that far. Read it whole with ")
            .append(READ_NAME)
            .append(" at its own offset.]\n\n");
      }
      out.append(matches.size())
          .append(matches.size() == 1 ? " line contains '" : " lines contain '")
          .append(needle.text())
          .append("', in ")
          .append(where)
          .append(". Each match below gives the file, the offset to send to ")
          .append(READ_NAME)
          .append(", and the line:");
      for (Found.Match match : matches) {
        // "offset=" and not a colon, which is the shape a model has read
        // a million times and which means a line number counted from
        // one. This class's javadoc owns that argument. The spelling is
        // file_read's own continuation note's, so the number is typed
        // back into the argument it is named for.
        out.append('\n')
            .append(match.path())
            .append(" offset=")
            .append(match.offset())
            .append(": ")
            .append(match.line());
        if (match.truncated()) {
          out.append(' ').append(CUT);
        }
      }
      return display(out.toString(), "this answer");
    }
  }

  // --- file_edit ---------------------------------------------------------------

  /** {@code file_edit} — replace one file's contents, or one piece of text in it. */
  public static final class Edit implements AgentTool {

    private final ProviderRouter router;
    private final Reads reads;
    private final ToolSchema schema;

    public Edit(ProviderRouter router) {
      this(router, new Reads());
    }

    public Edit(ProviderRouter router, Reads reads) {
      this.router = Objects.requireNonNull(router, "router");
      this.reads = Objects.requireNonNull(reads, "reads");
      Map<String, Object> properties = new LinkedHashMap<>();
      properties.put(
          "path", ToolArguments.string("The absolute path of the file, as file_roots spells it."));
      properties.put(
          "content",
          ToolArguments.string(
              "The file's whole new contents, written exactly, including its last newline."
                  + " Send this or old and new, never both."));
      properties.put(
          "old",
          ToolArguments.string("The exact text to replace. It must occur in the file once."));
      properties.put("new", ToolArguments.string("The text to put in its place. May be empty."));
      this.schema =
          new ToolSchema(
              EDIT_NAME, EDIT_DESCRIPTION, ToolArguments.object(properties, List.of("path")));
    }

    @Override
    public ToolSchema schema() {
      return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      Objects.requireNonNull(home, "home");
      try {
        return answer(argumentsJson, home);
      } catch (BadArguments | WorkspaceRefusedException correctable) {
        return correctable.getMessage();
      }
    }

    private static final String SHAPES =
        " Send either {\"path\", \"content\"} to write the"
            + " whole file, or {\"path\", \"old\", \"new\"} to replace one piece of text.";

    private String answer(String argumentsJson, Home home) {
      JsonNode args =
          ToolArguments.parse(
              argumentsJson,
              EDIT_NAME,
              "{\"path\": \"/srv/repo/src/Main.java\", \"old\": \"int x = 1;\","
                  + " \"new\": \"int x = 2;\"}");
      Path path = path(args, EDIT_NAME);
      boolean whole = sent(args, "content");
      boolean replacing = sent(args, "old") || sent(args, "new");
      if (whole && replacing) {
        throw new BadArguments(
            EDIT_NAME
                + " was given content and old/new together, and"
                + " they are two different edits."
                + SHAPES);
      }
      if (!whole && !replacing) {
        throw new BadArguments(EDIT_NAME + " was given nothing to change." + SHAPES);
      }
      FileProvider provider = router.providerFor(home, path);
      if (whole) {
        // requireExactText and never requireText: that method strips and
        // refuses blank, which for a file's contents means dropping the
        // trailing newline off everything an agent writes and refusing to
        // truncate a file. Its javadoc owns the argument.
        String content =
            ToolArguments.requireExactText(
                args, "content", EDIT_NAME, "the file's whole new contents");
        // Reads owns why: a file this run never read may be created and
        // never replaced, and the owning machine enforces it.
        Changed written =
            reads.seen(path) ? provider.write(path, content) : provider.create(path, content);
        reads.saw(path);
        // The line count and nothing of the text: it is exactly what the
        // model sent, so showing it back would spend a turn's worth of
        // context on something already in front of it. The file side's
        // count, which is the one file_stat would give; counted here from
        // what was sent only for a client built before it reported one.
        return wrote(path, written.facts(), content);
      }
      String old =
          ToolArguments.requireExactText(
              args,
              "old",
              EDIT_NAME,
              "the exact text to replace, which must occur in the file once." + SHAPES);
      String replacement =
          ToolArguments.requireExactText(
              args, "new", EDIT_NAME, "the text to put in its place, which may be empty." + SHAPES);
      Changed edited = provider.edit(path, old, replacement);
      // The lines shown are not a read of the file, and only a read — or a
      // write of the whole of it — lets a whole-file write replace what is
      // there. Every line of the file, uncut, is the one exception, and the
      // facts say so; an old client's words are never read for it. A file
      // already read stays read: the edit changed text the run had seen
      // into text the run chose.
      if (edited.showedWholeFile()) {
        reads.saw(path);
      }
      String done = "Replaced the one occurrence of the text in " + path + ".";
      // Worded here from the facts, by the one renderer; or — from a client
      // built before facts — that client's own view, passed through.
      String view =
          edited.facts() != null ? FileWords.edited(edited.facts()) : edited.oldSentence();
      if (view == null || view.isEmpty()) {
        // A client built before an edit answered with its view.
        return done;
      }
      // Through display: an old client's view was written on another
      // machine, and this class trusts no length it does not control.
      return display(done + " " + view, "this answer");
    }
  }

  // --- what a change says it did ------------------------------------------------

  /*
   * The state a change left, from the file side's record of it (the facts it
   * reported — spec 2026-09-30 §4.3: a change tells the model what it did and
   * the state it left, from the harness's own records). A client built before
   * facts reported none; its answer says only what was asked, as it always did.
   */

  /** A whole-file write: how many lines and bytes the file holds now. */
  static String wrote(Path path, FileResult facts, String content) {
    if (facts == null || facts.lines() == null || facts.bytes() == null) {
      long lines = content.lines().count();
      return "Wrote " + lines + (lines == 1 ? " line" : " lines") + " to " + path + ".";
    }
    return "Wrote "
        + count(facts.lines(), "line")
        + " ("
        + count(facts.bytes(), "byte")
        + ") to "
        + path
        + ".";
  }

  /** A delete: what the file held, and that nothing is there now. */
  static String deleted(Path path, FileResult facts) {
    if (facts == null || facts.bytes() == null) {
      return "Deleted " + path + ".";
    }
    String held =
        facts.lines() == null
            ? count(facts.bytes(), "byte")
            : count(facts.lines(), "line") + " (" + count(facts.bytes(), "byte") + ")";
    return "Deleted " + path + ", which held " + held + "; nothing is there now.";
  }

  /** A move: how much moved, and that nothing is left where it was. */
  static String moved(Path from, Path to, FileResult facts) {
    if (facts == null || facts.bytes() == null) {
      return "Moved " + from + " to " + to + ".";
    }
    return "Moved "
        + from
        + " to "
        + to
        + " ("
        + count(facts.bytes(), "byte")
        + "); nothing is at "
        + from
        + " now.";
  }

  private static String count(long n, String unit) {
    return n + " " + unit + (n == 1 ? "" : "s");
  }

  // --- file_delete -------------------------------------------------------------

  /** {@code file_delete} — remove one file. */
  public static final class Delete implements AgentTool {

    private final ProviderRouter router;
    private final Reads reads;
    private final ToolSchema schema;

    public Delete(ProviderRouter router) {
      this(router, new Reads());
    }

    public Delete(ProviderRouter router, Reads reads) {
      this.router = Objects.requireNonNull(router, "router");
      this.reads = Objects.requireNonNull(reads, "reads");
      this.schema =
          new ToolSchema(
              DELETE_NAME,
              DELETE_DESCRIPTION,
              pathSchema("The absolute path of the file to delete, as file_roots spells it."));
    }

    @Override
    public ToolSchema schema() {
      return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      Objects.requireNonNull(home, "home");
      try {
        return answer(argumentsJson, home);
      } catch (BadArguments | WorkspaceRefusedException correctable) {
        return correctable.getMessage();
      }
    }

    private String answer(String argumentsJson, Home home) {
      JsonNode args =
          ToolArguments.parse(argumentsJson, DELETE_NAME, "{\"path\": \"/srv/repo/src/Old.java\"}");
      Path path = path(args, DELETE_NAME);
      Changed deleted = router.providerFor(home, path).delete(path);
      reads.forget(path);
      return FileTools.deleted(path, deleted.facts());
    }
  }

  // --- file_move ---------------------------------------------------------------

  /** {@code file_move} — rename one file, never over another. */
  public static final class Move implements AgentTool {

    private final ProviderRouter router;
    private final Reads reads;
    private final ToolSchema schema;

    public Move(ProviderRouter router) {
      this(router, new Reads());
    }

    public Move(ProviderRouter router, Reads reads) {
      this.router = Objects.requireNonNull(router, "router");
      this.reads = Objects.requireNonNull(reads, "reads");
      Map<String, Object> properties = new LinkedHashMap<>();
      properties.put(
          "path",
          ToolArguments.string("The absolute path of the file to move, as file_roots spells it."));
      properties.put(
          "to",
          ToolArguments.string(
              "The absolute path it should have afterwards. Nothing may be there already."));
      this.schema =
          new ToolSchema(
              MOVE_NAME, MOVE_DESCRIPTION, ToolArguments.object(properties, List.of("path", "to")));
    }

    @Override
    public ToolSchema schema() {
      return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      Objects.requireNonNull(home, "home");
      try {
        return answer(argumentsJson, home);
      } catch (BadArguments | WorkspaceRefusedException correctable) {
        return correctable.getMessage();
      }
    }

    private String answer(String argumentsJson, Home home) {
      JsonNode args =
          ToolArguments.parse(
              argumentsJson,
              MOVE_NAME,
              "{\"path\": \"/srv/repo/src/Old.java\", \"to\": \"/srv/repo/src/New.java\"}");
      Path from = path(args, MOVE_NAME);
      Path to = named(args, "to", MOVE_NAME);
      FileProvider source = router.providerFor(home, from);
      FileProvider destination = router.providerFor(home, to);
      // By name and not by identity: the router asks for its providers
      // afresh on every call, so one filesystem arrives as two instances.
      // A run holds at most one provider of each kind.
      if (!source.name().equals(destination.name())) {
        // No machine could carry this out: each provider is a different
        // filesystem, and a move between two is a copy and a delete that
        // would each have to succeed on its own.
        throw new WorkspaceRefusedException(
            from
                + " is on "
                + source.name()
                + " and "
                + to
                + " is on "
                + destination.name()
                + ", and a file can only be moved"
                + " within one filesystem; read it, write it at the new path, and delete"
                + " the old one");
      }
      Changed moved = source.move(from, to);
      if (reads.seen(from)) {
        reads.forget(from);
        reads.saw(to);
      }
      return FileTools.moved(from, to, moved.facts());
    }
  }

  // --- file_roots --------------------------------------------------------------

  /**
   * {@code file_roots} — what this job can see, and when it can see nothing, which of the several
   * reasons that is.
   *
   * <p>Free, and that is the point: it stops a model probing paths to discover its own leash, the
   * way 3a's scribe stopped guessing once the candidate tiers were labelled for it.
   *
   * <p><b>The empty answer is where the work is.</b> An empty root list has several distinct
   * causes, each a different person's fix, and {@link
   * io.aeyer.plowshare.server.files.LocalProvider} is where they are enumerated — with the
   * qualifier that one of them raises rather than answering empty and so never reaches this tool at
   * all. Rendering "you have no roots" for all of them is the collapse Excalibur split {@code
   * NO_ROOTS} and {@code NO_WORKSPACE} apart to stop. {@link ProviderRouter#absence} is how the
   * distinguishing sentence is reached and owns the argument for how.
   *
   * <p>The list is not restated here. An earlier version restated it as "five states" and
   * attributed it to {@link FileProvider#roots()}, which promises no such thing — while {@link
   * #ROOTS_DESCRIPTION} below, which a model actually reads, names four and is right.
   */
  public static final class Roots implements AgentTool {

    private final ProviderRouter router;
    private final ToolSchema schema;

    public Roots(ProviderRouter router) {
      this.router = Objects.requireNonNull(router, "router");
      this.schema =
          new ToolSchema(
              ROOTS_NAME,
              ROOTS_DESCRIPTION,
              ToolArguments.object(new LinkedHashMap<>(), List.of()));
    }

    @Override
    public ToolSchema schema() {
      return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      Objects.requireNonNull(home, "home");
      try {
        return answer(argumentsJson, home);
      } catch (BadArguments | WorkspaceRefusedException correctable) {
        return correctable.getMessage();
      }
    }

    private String answer(String argumentsJson, Home home) {
      // Parsed although nothing is read out of it. A model that sends an
      // array has misunderstood the schema, and saying so costs one turn
      // where ignoring it costs the turn plus whatever the model concludes
      // from an answer to a question it did not ask.
      ToolArguments.parse(argumentsJson, ROOTS_NAME, "{}");

      List<FileProvider> providers = router.providersFor(home);
      if (providers.isEmpty()) {
        return NO_FILESYSTEM;
      }
      StringBuilder out = new StringBuilder();
      for (FileProvider provider : providers) {
        if (out.length() > 0) {
          out.append('\n');
        }
        List<Path> roots = provider.roots();
        out.append(provider.name()).append(": ");
        if (roots.isEmpty()) {
          out.append(ProviderRouter.absence(provider));
        } else {
          out.append(String.join(", ", roots.stream().map(Path::toString).toList()));
        }
      }
      return display(out.toString(), "this list of roots");
    }
  }

  // --- the arguments -----------------------------------------------------------

  /**
   * One absolute path, or the refusal saying what to send instead.
   *
   * <p>Two refusals a model can act on, and each is one this seam is the only place that can make:
   *
   * <ul>
   *   <li><b>a string that is not a path at all.</b> Measured on JDK 21: {@code Path.of("a\0b")}
   *       raises {@code InvalidPathException}, which is unchecked. {@link FileProvider} is handed a
   *       {@code Path} and can never see this, which is why task 3 recorded it as the tools'
   *       problem;
   *   <li><b>a relative path.</b> Excalibur resolved one against the workspace; here there is no
   *       single workspace to resolve against — a job can hold two — and {@code toAbsolutePath}
   *       would resolve it against the server's own working directory, which is its checkout.
   *       Letting it through produces "outside every root", a true sentence about the wrong subject
   *       that sends the model looking for a file that was never the problem. Refused by name
   *       instead, pointing at {@code file_roots}.
   * </ul>
   *
   * <p>The string is read with {@code requireText}, so it is stripped. The cost is that a file
   * whose last component ends in a space cannot be named, and that is the better trade: a stray
   * space in a model's JSON is ordinary and would otherwise become "there is no file at …", while a
   * filename with a trailing space is not.
   */
  /**
   * The same path, for the one tool that does not need one.
   *
   * <p>{@code null} means the caller sent nothing, which for {@code file_grep} is the ordinary call
   * — <em>where is X</em> is the question that has no path in it, and {@link FileProvider#grep}
   * takes a null for exactly this.
   *
   * <p><b>A path that was sent is checked by {@link #path} and not by a second copy of it.</b> The
   * two refusals that method makes — a string the platform cannot parse, and a relative path with
   * nothing here to resolve it against — are no less true of a search than of a read, and an
   * optional argument validated more loosely than a required one is a way to reach the seam with
   * something the seam was promised it would never see.
   */
  private static Path optionalPath(JsonNode args, String tool) {
    JsonNode sent = args.path("path");
    if (sent.isMissingNode() || sent.isNull()) {
      return null;
    }
    return path(args, tool);
  }

  private static Path path(JsonNode args, String tool) {
    return named(args, "path", tool);
  }

  /** Whether the caller sent a field at all; a JSON null is not sending one. */
  private static boolean sent(JsonNode args, String name) {
    JsonNode value = args.path(name);
    return !value.isMissingNode() && !value.isNull();
  }

  /** {@link #path}, for a field with another name, which {@code file_move}'s {@code to} is. */
  private static Path named(JsonNode args, String field, String tool) {
    String raw =
        ToolArguments.requireText(
            args, field, tool, "the absolute path of the file, as file_roots spells it");
    Path path;
    try {
      path = Path.of(raw);
    } catch (InvalidPathException unusable) {
      throw new BadArguments(
          tool
              + " could not read '"
              + raw
              + "' as a path: "
              + ToolArguments.firstLine(unusable)
              + ". Send a plain absolute path.");
    }
    if (!path.isAbsolute()) {
      throw new BadArguments(
          tool
              + " needs an absolute path, and '"
              + raw
              + "' is"
              + " relative. There is nothing here for it to be relative to — call"
              + " file_roots and write the whole path out.");
    }
    return path;
  }

  // --- rendering ---------------------------------------------------------------

  /**
   * The one sentence for a job wired with no provider at all. Not a projects row's doing and not
   * the model's: a job reaches files through the tier it runs in, and this one was given nothing to
   * reach them through.
   */
  private static final String NO_FILESYSTEM =
      "This job has no filesystem at all — no provider is wired to it, so there is"
          + " nothing to read, search or write anywhere.";

  /**
   * As much of an answer as one turn is given, and a note when that is not all of it.
   *
   * <p>{@link #MAX_DISPLAY_CHARS} owns the argument for the number and for why this is a display
   * limit rather than a refusal. What belongs here is that <b>the note is not optional</b>: a cut
   * that said nothing would be indistinguishable from a file that ends there, and a model acting on
   * the second reading edits from a copy it believes is complete.
   *
   * <p><b>Every answer assembled from something another machine wrote goes through here</b> — both
   * of {@code file_glob}'s and {@code file_roots}' as well as {@code file_read}'s windows. Two of
   * those were left uncut on the reasoning that a root list and a refusal are short, which is a
   * fact about {@link LocalProvider} and not about the interface: {@link FileProvider#roots()}
   * bounds nothing, and a remote provider assembles both its root list and its refusal sentence on
   * another machine. A tool that trusted a length it does not control would be relying on the far
   * side's good manners.
   *
   * <p>{@code file_edit}'s answer after a replacement is one of these: it carries the view of the
   * changed lines, which a client words on its own machine. The view is bounded where it is made,
   * and cut here as well.
   *
   * <p>The exceptions are the answers built entirely from a path and numbers this class already
   * holds — {@code file_edit}'s answer to a whole-file write, {@code file_delete}'s and {@code
   * file_move}'s confirmations, {@code file_stat}'s count, and {@code file_read}'s two sentences
   * for an empty file and an offset past the end. There is no far side in any of them to distrust.
   *
   * <p><b>Anything that must survive the cut goes before the bulk</b>, because this cuts the tail.
   * {@code file_glob} emits its "Not searched" note ahead of the hits for exactly that reason; the
   * comment there records what happened when it did not.
   */
  private static String display(String text, String what) {
    if (text.length() <= MAX_DISPLAY_CHARS) {
      return text;
    }
    return text.substring(0, MAX_DISPLAY_CHARS)
        + "\n\n[Cut off here: "
        + MAX_DISPLAY_CHARS
        + " of "
        + text.length()
        + " characters are shown. This is a limit on how much one answer can carry,"
        + " not the end of "
        + what
        + ".]";
  }

  private static Map<String, Object> pathSchema(String description) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("path", ToolArguments.string(description));
    return ToolArguments.object(properties, List.of("path"));
  }

  // --- what the model is told these tools do -----------------------------------

  /*
   * Written to MemoryTools' two rules, which are the MCP surface's: say what
   * the tool costs, and never describe a capability this surface does not
   * have. None of them mentions a project, because none of them takes one —
   * an agent runs against the tier its job was started for.
   */

  static final String READ_DESCRIPTION =
      """
            Read one file by its absolute path, a window of lines at a time. \
            Fast and free — no model runs. Omit offset and limit to read from \
            the beginning.

            One answer carries at most %d lines. A larger limit is brought \
            down to that rather than refused, and the answer says when it was, \
            so a long file is read by paging through it and never by asking for \
            more of it at once.

            Lines are numbered from 0, and offset is a line number. If the file \
            continues past the window you asked for, the answer opens with a \
            line saying which lines you were given, how many the file has, and \
            the offset to send to get the next window — send that number and \
            you carry on where you stopped. That line is written by this tool \
            and is not in the file. A window that reaches the end of the file \
            has no such line, so its absence means you have seen the rest.

            Sometimes the window fills up on size before it runs out of lines, \
            and the answer says so when it does. Asking again with a larger \
            limit returns the same text; ask for the next offset instead.

            file_stat gives you the line count without the lines, so you can \
            plan the reads rather than discover the length one window at a time.

            You can only read inside the roots this job was granted. Call \
            file_roots to see what those are rather than guessing at paths: a \
            path outside them is refused, and the refusal costs you a turn.\
            """
          .formatted(MAX_READ_LINES);

  static final String STAT_DESCRIPTION =
      """
            Say how many lines one file has, without returning any of them. \
            Fast and free — no model runs. Call it before reading anything you \
            expect to be long: file_read carries a window at a time, and \
            knowing the length up front turns the reads into a plan instead of \
            a guess.""";

  static final String GLOB_DESCRIPTION =
      """
            List the files matching a glob pattern, without opening any of \
            them. Fast and free — no model runs. The pattern is matched against \
            each file's path relative to a root, so it is never absolute: write \
            src/**/*.java, not /srv/repo/src/**/*.java.

            ** matches any number of directories including none, so **/*.java \
            finds a file at the top of a root as well as one buried in it.

            An empty answer means the search ran and matched nothing, and it \
            names the trees it ran over so you can tell that apart from having \
            nowhere to look. If part of what this job can reach could not be \
            searched, the answer says which part and why.""";

  static final String GREP_DESCRIPTION =
      """
            Find the lines that contain a piece of text. Fast and free — no \
            model runs. Searching before you read is the cheaper order: it \
            turns "where is X" into one call instead of paging through a file \
            until you reach it.

            The text is matched literally, exactly as you send it. It is not a \
            regular expression: there are no wildcards and no anchors, and .* \
            finds only a line with a dot and an asterisk in it.

            Each match names the file, an offset and the line. That offset is \
            file_read's offset for that file, so a search and one read reach \
            any line. Omit path to search everything this job can reach, or \
            give one file or one directory to search only that. At most %d \
            matches come back, and the answer says when it stopped there.\
            """
          .formatted(Needle.MAX_MATCHES);

  static final String EDIT_DESCRIPTION =
      """
            Change one file. Fast and free — no model runs. Two ways, and you \
            send one of them:

            old and new replace one piece of text. old must match the file \
            exactly — spaces, indentation and line breaks included — and must \
            occur in it once; if it is not there, or is there more than once, \
            nothing is changed and the answer says which, so include more of \
            the surrounding lines to make it unique. new may be empty, which \
            deletes the text. Everything else in the file, line endings \
            included, is left exactly as it was. Prefer this for any change to \
            an existing file: you send only what changes.

            The answer shows the lines the new text now occupies, with %d \
            lines either side, under a line numbering them from 0 as \
            file_read's offset does. Build your next edit of that file from \
            those lines, not from what you read before this one. If old is not \
            in the file, the answer shows the closest lines it does have, and \
            says so when the only difference is whitespace or a look-alike \
            character such as a typographic dash or quote — copy old from \
            them. Nothing is ever changed on a near match.

            content replaces the whole file, or creates it and the directories \
            above it. It is written exactly as you send it, including its last \
            newline — or its absence. A file that already exists is only \
            replaced if you have read it with file_read in this job — the \
            lines an edit shows are not that read; otherwise the answer \
            refuses, so you cannot overwrite what you have not seen.

            Changing files needs a write grant. If this agent's definition \
            declares only read, the refusal says so, and no turn of yours can \
            change it."""
          .formatted(Replacement.CONTEXT_LINES);

  static final String DELETE_DESCRIPTION =
      """
            Delete one file. Fast and free — no model runs. A directory, a \
            link, and a path with nothing at it are refused; there is no way \
            to delete a whole directory at once. It needs a write grant.""";

  static final String MOVE_DESCRIPTION =
      """
            Move or rename one file. Fast and free — no model runs. Nothing \
            may be at the destination already: a move never replaces a file. \
            The directories above the destination are created. Both paths must \
            be inside this job's roots and on the same filesystem, and a \
            directory or a link cannot be moved. It needs a write grant.""";

  static final String ROOTS_DESCRIPTION =
      """
            Say which directories this job can reach, and on which filesystem. \
            Fast and free — no model runs. Call it before your first file_read \
            or file_glob: every path you send has to be inside one of these, \
            and probing for them costs a turn each.

            A project may name more than one — its workspace, plus any further \
            directories lent to it — so read the whole answer rather than the \
            first line of it.

            If a filesystem has nothing to offer, the answer says which reason \
            it is — the tier this job runs in, a project with no workspace set, \
            an agent whose definition asked for no file access, or a project \
            every one of whose directories is inside somewhere no project may \
            reach. They are four \
            different fixes and none of them is a path you can retype.""";
}
