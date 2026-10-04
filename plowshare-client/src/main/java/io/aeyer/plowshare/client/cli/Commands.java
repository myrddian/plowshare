package io.aeyer.plowshare.client.cli;

import io.aeyer.plowshare.client.Capabilities;
import io.aeyer.plowshare.client.PlowshareClient;
import io.aeyer.plowshare.client.ServerClient;
// The one place on this side of the wire that turns a structural unit's null
// title into words. Reached across packages rather than copied, which is the
// whole of the discipline: a second implementation of the rule is a second
// renderer that can get it wrong. See Structural.
import io.aeyer.plowshare.client.WsServerClient;
import io.aeyer.plowshare.client.tools.FetchTools;
import io.aeyer.plowshare.client.tools.SearchTools;
import io.aeyer.plowshare.client.tools.Structural;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.WriteResult;
import io.aeyer.plowshare.protocol.fetch.FetchWindow;
import io.aeyer.plowshare.protocol.search.Hit;
import io.aeyer.plowshare.protocol.search.SearchPage;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Everything this terminal can do that is not starting a run or holding a conversation.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The parity design's rule reads both ways, and until now the <b>terminal</b> was the deficient
 * side: twenty MCP tools against two CLI verbs. A person at a terminal could run an agent once or
 * talk to one, and could not recall a memory, define a project or read a trajectory — through the
 * front end that is supposed to be the reference implementation of a Plowshare harness.
 *
 * <p>Nothing under here is new capability. {@link ServerClient} already carried every one of these
 * calls, because the MCP surface is built on the same interface; what was missing was a way for a
 * person to reach them.
 *
 * <h2>Shaped for a terminal, and deliberately not a mirror of the tool surface</h2>
 *
 * <p>{@link Capabilities} holds the two spellings side by side, which makes what they share visible
 * and what they do not obvious. Three places where these commands are not the tools rearranged:
 *
 * <ul>
 *   <li><b>Nothing here reuses a tool's handler, and the reason is what the output says.</b> {@code
 *       agent_run}'s answer ends "Ask agent_poll whether it has finished, then agent_result";
 *       {@code conversation_list}'s ends "Read one with conversation_chat". Those sentences are
 *       correct and are written for a model holding that menu. Printed at a terminal they name
 *       tools a person does not have, in place of the commands they do. The renderings below are
 *       therefore this class's own, and a tool description is never printed to anybody:
 *       descriptions are model-visible text, and editing one to read better in a shell would change
 *       what every agent reading that menu is told.
 *   <li><b>{@code agent_poll} and {@code agent_result} are one command.</b> A model asking "is it
 *       done" wants an answer with no result to read out of; a terminal prints what it has the
 *       moment it is asked. See the note on that capability.
 *   <li><b>{@code project_move} is spelled {@code rename}.</b> It changes a project's name and
 *       never its directory. The tool keeps {@code move} because {@code ProjectTools} has a naming
 *       hazard to manage between four descriptions a model reads one at a time; a person reads all
 *       four in a usage block at once, and for them "move" is the word for the files.
 * </ul>
 *
 * <h2>The first word, and the agent whose name collides with it</h2>
 *
 * <p>{@code plowshare <agent> <task>} takes the first word as an agent, so five words are now
 * reserved in front of it: {@code memory}, {@code project}, {@code conversation}, {@code document}
 * and {@code job}. A deployment may have an agent called any of them. The rule is that <b>a
 * reserved word is always a command</b> — never a guess from the shape of the rest of the line,
 * because a guess is silent when it is wrong — and {@code plowshare run <agent> <task>} takes any
 * name at all, including these five and including {@code run}. Every refusal from a group word says
 * so.
 *
 * <h2>Column zero</h2>
 *
 * <p>{@code MemoryTools}' rule, kept for a different reader. What a memory or an outcome carries
 * was written by an agent, and a body that renders indistinguishably from this class's own headings
 * is a listing a person can be misled by: {@link #oneLine} flattens every single-line slot and
 * {@link #indent} pushes multi-line content off column zero. The tool surface argues this as a
 * channel between agents; here it is simply that the terminal's own voice has to be tellable from
 * what it is quoting.
 */
public final class Commands {

  /**
   * What every command may be told, on top of its own flags: where the server is. Resolved exactly
   * as the run and conversation forms resolve it.
   */
  private static final String SERVER = "--server";

  /** Two spaces, for content this class is quoting rather than saying. */
  private static final String QUOTE = "  ";

  /**
   * Every line terminator Java recognises, not the three {@code String.lines()} splits on — {@code
   * MemoryTools.oneLine} measured that difference and it is the same one here.
   */
  private static final Pattern LINE_BREAK = Pattern.compile("\\R");

  /**
   * What the index and a recall call a memory no question can reach. One spelling for both, so a
   * person is not given two facts to reconcile.
   */
  private static final String UNSEARCHABLE = "[not searchable]";

  private static final Map<String, Command> TABLE = table();

  /**
   * The two commands the terminal answers for itself.
   *
   * <p>Both need a session — the file channel this machine lends, and the two clocks that render a
   * run while it goes — so they live in {@link Plowshare} where that machinery is. They are
   * declared capabilities like any other and are listed here so the parity check sees the whole
   * surface rather than the part that happens to be a table.
   */
  private static final Set<String> OWN = Set.of("run", "talk");

  static {
    // Where parity stops being something to remember. A verb added to the
    // table above without a line in Capabilities fails here — at class
    // initialisation, so on the first thing that touches this class, which
    // is every test in the module and the first command anybody types.
    Capabilities.commandsAreDeclared(offered());
  }

  private Commands() {}

  /** Every command this terminal offers, as the words a person types after {@code plowshare}. */
  public static Set<String> offered() {
    Set<String> all = new LinkedHashSet<>(OWN);
    all.addAll(TABLE.keySet());
    return all;
  }

  /**
   * Whether a word at the front of a command line is a group of commands rather than an agent's
   * name.
   */
  static boolean group(String word) {
    return groups().contains(word);
  }

  /**
   * Run one command and answer with the terminal's status.
   *
   * <p>{@code 0} for a command that did what it was asked. {@code 1} is used by exactly one
   * command, {@code job status}, and means what it means everywhere else in this terminal: the run
   * did not answer. {@code 2} for everything that stopped this doing the thing at all — a usage
   * error, a server that refused, a server that was not there.
   *
   * <h2>A solo command is dispatched off the group word alone</h2>
   *
   * <p>Every command here used to be {@code group verb} — {@code memory index}, {@code job status}
   * — and {@code args[1]} was always the second word of a name to look up. {@code search} broke
   * that: its whole point is that everything after the group word is the query and its options, not
   * a second word choosing among several actions the way {@code index} chooses among memory's four.
   * Rather than force a query through a fake verb slot — {@code plowshare search query "..."},
   * where {@code query} names nothing a person would otherwise type — a {@link Command} whose
   * {@link Command#verb} is empty is looked up by its group alone, before the two-word path below
   * ever runs, and everything after the group word is that command's own arguments.
   */
  static int run(String[] args, PrintStream out, PrintStream problems) {
    String group = args[0];
    Command solo = TABLE.get(group);
    if (solo != null && solo.verb().isEmpty()) {
      return dispatch(solo, Arrays.copyOfRange(args, 1, args.length), out, problems);
    }

    if (args.length < 2 || args[1].startsWith("--")) {
      problems.println(
          "'"
              + group
              + "' is a group of commands and needs one of them"
              + " after it. "
              + escape(group));
      problems.print(usageFor(group));
      return 2;
    }
    String name = group + " " + args[1];
    Command command = TABLE.get(name);
    if (command == null) {
      problems.println("there is no '" + name + "'. " + escape(group));
      problems.print(usageFor(group));
      return 2;
    }
    return dispatch(command, Arrays.copyOfRange(args, 2, args.length), out, problems);
  }

  /**
   * What every command shares once its name has been resolved: parse the rest of the line, open a
   * connection, run the handler, and turn whatever went wrong into the one sentence a person at
   * this terminal reads.
   *
   * <p>Split out of {@link #run} when {@code search} needed a second way to arrive at a {@link
   * Command} — off the group word alone rather than off {@code group verb} — and this half of the
   * old method did not change: a command is a command once its name is resolved, whichever path
   * found it.
   */
  private static int dispatch(
      Command command, String[] rest, PrintStream out, PrintStream problems) {
    Line line;
    try {
      line = Line.of(rest, command);
    } catch (IllegalArgumentException notUsable) {
      problems.println(notUsable.getMessage());
      problems.print(oneUsage(command));
      return 2;
    }

    WsServerClient server;
    String where = line.flag(SERVER) == null ? PlowshareClient.serverUrl() : line.flag(SERVER);
    try {
      // The credential comes from the environment and never from a flag,
      // for the reason cli.Plowshare states: a --token would put it on a
      // command line, which every process on the box can read through ps.
      server = new WsServerClient(where, PlowshareClient.accessToken());
    } catch (IllegalArgumentException notAUrl) {
      problems.println(notAUrl.getMessage());
      return 2;
    }

    try (server) {
      return command.handler().handle(server, line, out, problems);
    } catch (IllegalArgumentException notUsable) {
      problems.println(notUsable.getMessage());
      problems.print(oneUsage(command));
      return 2;
    } catch (ServerClient.ServerError said) {
      // The server's own sentence and nothing added to it. It answered,
      // and what it said about the request is more use than anything this
      // side could say about it.
      problems.println(said.getMessage());
      return 2;
    } catch (IOException unreachable) {
      if (unreachable instanceof io.aeyer.plowshare.sdk.Plowshare.TransportException transport
          && transport.delivery() != io.aeyer.plowshare.sdk.Plowshare.Delivery.NOT_SUBMITTED) {
        problems.println(
            "The Plowshare request has an unknown outcome and may have been applied. No request was replayed. Read durable status or recover its receipt before submitting work again.");
        return 2;
      }
      // Never rendered as an empty answer. "Nothing is remembered" and "I
      // could not ask" are indistinguishable once they print the same way,
      // and the first is a conclusion somebody acts on.
      problems.println(
          "could not reach the Plowshare server at "
              + where
              + ": "
              + describe(unreachable)
              + ". This says nothing about what is there — it was"
              + " never asked. Check the server is running, then try again.");
      return 2;
    }
  }

  // --- the table -------------------------------------------------------------------

  /**
   * Every command, in the order the usage block reads them: the archive, then the queue it fills,
   * then a conversation, then the corpus, then a project, then a run that is already going.
   */
  private static Map<String, Command> table() {
    Map<String, Command> all = new LinkedHashMap<>();
    add(
        all,
        new Command(
            "information",
            "",
            "<operation> '<JSON payload>'",
            "read and manage retained information over WebSocket; keep mutation requestId across retries",
            2,
            2,
            Set.of(),
            (server, line, out, problems) -> {
              java.util.Map<String, Object> payload;
              try {
                payload =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(
                            line.word(1),
                            new com.fasterxml.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {});
              } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw new IllegalArgumentException(
                    "information payload must be a JSON object", invalid);
              }
              if (payload == null)
                throw new IllegalArgumentException("information payload must be a JSON object");
              out.println(
                  new com.fasterxml.jackson.databind.ObjectMapper()
                      .writeValueAsString(server.information(line.word(0), payload)));
              return 0;
            }));

    add(
        all,
        new Command(
            "memory",
            "navigate",
            "<question> [--project NAME]",
            "navigate the memory tree with a separate system allowance",
            1,
            1,
            Set.of("--project"),
            (server, line, out, problems) -> {
              var result = server.navigateMemory(line.project(), line.word(0));
              out.println(
                  "Reached "
                      + result.level()
                      + "; complete="
                      + result.complete()
                      + "; ids="
                      + result.ids());
              out.println(result.text());
              return result.complete() ? 0 : 1;
            }));
    add(
        all,
        new Command(
            "memory",
            "digest",
            "[--project NAME]",
            "build or refresh digests; returns a job id",
            0,
            0,
            Set.of("--project"),
            (server, line, out, problems) -> {
              out.println("Started digest job " + server.digestMemory(line.project()).id());
              return 0;
            }));
    add(
        all,
        new Command(
            "memory",
            "index",
            "[--project NAME]",
            "every memory a tier holds, one line each. Fast: nothing runs.",
            0,
            0,
            Set.of("--project"),
            Commands::memoryIndex));
    add(
        all,
        new Command(
            "memory",
            "read",
            "<id> [<id>...]",
            "those memories in full, with where each came from. A retired one is"
                + " readable too, and the reason on it is the point.",
            1,
            Integer.MAX_VALUE,
            Set.of(),
            Commands::memoryRead));
    add(
        all,
        new Command(
            "memory",
            "recall",
            "<question> [--project NAME] [--limit N]",
            "the memories nearest a question, matched by meaning rather than by"
                + " words. Takes a moment: the question is embedded.",
            1,
            1,
            Set.of("--project", "--limit"),
            Commands::memoryRecall));
    add(
        all,
        new Command(
            "memory",
            "write",
            "--summary S --scope W --body B [--project NAME] [--by WHO] [--where W]",
            "record something worth remembering. A scribe on the server decides"
                + " whether it is new, more detail on one already held, or a"
                + " replacement, and says which.",
            0,
            0,
            Set.of("--summary", "--scope", "--body", "--project", "--by", "--where"),
            Commands::memoryWrite));
    add(
        all,
        new Command(
            "memory",
            "curate",
            "<project> [--max-model-calls N]",
            "start a pass that decides which of a project's memories hold"
                + " everywhere. Costs model calls, roughly two per memory. It runs"
                + " on the server and this does not wait for it.",
            1,
            1,
            Set.of("--max-model-calls"),
            Commands::memoryCurate));
    add(
        all,
        new Command(
            "memory",
            "proposals",
            "[--project NAME]",
            "the promotions waiting on a person. Fast: nothing runs.",
            0,
            0,
            Set.of("--project"),
            Commands::memoryProposals));
    add(
        all,
        new Command(
            "memory",
            "resolve",
            "<proposal> accept|reject [--reason WHY]" + " [--by WHO]",
            "settle one of them, once. Neither decision is reversible and a"
                + " rejection is remembered, which is what stops the same memory"
                + " being proposed again next week.",
            2,
            2,
            Set.of("--reason", "--by"),
            Commands::memoryResolve));

    add(
        all,
        new Command(
            "conversation",
            "list",
            "[--project NAME]",
            "what is open in a tier, oldest first.",
            0,
            0,
            Set.of("--project"),
            Commands::conversationList));
    add(
        all,
        new Command(
            "conversation",
            "search",
            "<question> [--project NAME] [--offset N] [--limit N]",
            "where in a tier's conversations something was said, closest first."
                + " This is the one that starts from words rather than from an"
                + " id. Matched on the words themselves and not on meaning: an"
                + " entry has to hold every content word of the question,"
                + " stemmed, and common words are ignored — so a shorter question"
                + " finds more.",
            1,
            1,
            Set.of("--project", "--offset", "--limit"),
            Commands::conversationSearch));
    add(
        all,
        new Command(
            "conversation",
            "chat",
            "<id> [--offset N] [--limit N]",
            "what the model is shown in it: what a fold covered is gone, and so is"
                + " everything whose kind never reaches a model.",
            1,
            1,
            Set.of("--offset", "--limit"),
            Commands::conversationChat));
    add(
        all,
        new Command(
            "conversation",
            "trajectory",
            "<id> [--offset N] [--limit N]",
            "everything it recorded, in the order it happened — what a fold"
                + " covered, the harness's own diagnostics, the model calls that"
                + " failed, and the timings.",
            1,
            1,
            Set.of("--offset", "--limit"),
            Commands::conversationTrajectory));
    add(
        all,
        new Command(
            "conversation",
            "context",
            "<id> [--agent NAME]",
            "what its prompt costs, and every number the server declines to"
                + " estimate with the reason it gave. With --agent, what that"
                + " agent's fixed block costs in characters.",
            1,
            1,
            Set.of("--agent"),
            Commands::conversationContext));

    add(
        all,
        new Command(
            "document",
            "retrieve",
            "<question> [--document ID] [--limit N]",
            "passages of the corpus WITH the structure around them — each one under"
                + " what its document argues, what its chapter and section cover, and"
                + " what the paragraph itself claims. Not a better 'search': it is"
                + " what tells you whether a passage says what it looks like it says."
                + " With --document, one paper; without, everything.",
            1,
            1,
            Set.of("--document", "--limit"),
            Commands::documentRetrieve));
    add(
        all,
        new Command(
            "document",
            "rank",
            "<question> [--limit N]",
            "which PAPERS are about something, by comparing each document's own summary"
                + " against the question. One lookup per document and it does not read"
                + " inside them. Ranks by SUBJECT and not by agreement: a paper that"
                + " spends forty pages demolishing a claim ranks high for that claim.",
            1,
            1,
            Set.of("--limit"),
            Commands::documentRank));
    add(
        all,
        new Command(
            "document",
            "stance",
            "<document-id> <claim>",
            "a CHEAP GUESS at what one paper says about a claim, with no model call at"
                + " all: the claim and its negation are compared against the document's"
                + " summary. A heuristic and not a reading — nothing here has looked at"
                + " the paper. 'document ask' is what answers this properly.",
            2,
            2,
            Set.of(),
            Commands::documentStance));
    add(
        all,
        new Command(
            "document",
            "list",
            "[--naming TEXT] [--limit N] [--offset N]",
            "what the corpus holds, newest first, with what each document argues and"
                + " how much of it there is. This is how you find a document's id."
                + " --naming matches part of the filename it was filed under or part"
                + " of its title, in any case.",
            0,
            0,
            Set.of("--naming", "--limit", "--offset"),
            Commands::documentList));
    add(
        all,
        new Command(
            "document",
            "show",
            "<document-id>",
            "one document's structure — its chapters, the sections under them and what"
                + " each covers, in the document's own word for its parts. No passage"
                + " text. Read it before 'document ask', which costs minutes.",
            1,
            1,
            Set.of(),
            Commands::documentShow));
    add(
        all,
        new Command(
            "document",
            "search",
            "<question> [--limit N]",
            "the passages of the corpus nearest a question, by meaning and by the"
                + " words themselves. Each carries the paragraph to cite, which"
                + " outlives the passage that matched.",
            1,
            1,
            Set.of("--limit"),
            Commands::documentSearch));
    add(
        all,
        new Command(
            "document",
            "ask",
            "<document-id> <question>" + " [--max-model-calls N]",
            "ask ONE document a question, and start the three-agent deliberation that"
                + " answers it. Not a narrower search: a proposer drafts from the"
                + " document's whole structure and its passages, a critic challenges"
                + " that draft from what the document argues as a whole and is shown"
                + " none of the passages, and a synthesiser writes the answer from"
                + " both. Runs on the server and takes minutes; read it with 'job"
                + " status'.",
            2,
            2,
            Set.of("--max-model-calls"),
            Commands::documentAsk));
    add(
        all,
        new Command(
            "document",
            "citations",
            "[--conversation ID] [--document ID] [--limit N]",
            "what answers have said they took from the corpus. A record of past"
                + " answers and not the corpus itself, so nothing here is a claim"
                + " about what the documents say. A citation whose paragraph a"
                + " later ingest edited says so instead of quietly showing"
                + " different words.",
            0,
            0,
            Set.of("--conversation", "--document", "--limit"),
            Commands::documentCitations));

    // A solo command: its verb is "", so it is typed as 'plowshare search
    // <query>' rather than 'plowshare search <verb> <query>'. See
    // Commands#run for why 'search <verb>' would be the wrong shape here
    // — the whole point is that everything after the group word is the
    // query and its options, not a second word choosing among actions.
    add(
        all,
        new Command(
            "search",
            "",
            "<query> [--page-size N] [--max N] [--page N]",
            "search for information from outside this project. --page-size is how"
                + " many results to print; --max is the most worth looking for, a"
                + " ceiling rather than a target; --page reads a later page of the"
                + " SAME search and only works with the exact --max the first page"
                + " used. A search can come back empty, or refuse outright with a"
                + " sentence saying what to try instead.",
            1,
            1,
            Set.of("--page-size", "--max", "--page"),
            Commands::search));

    // A second solo command, right beside search for the same reason
    // fetch is registered right beside search in PlowshareClient.tools:
    // this is the other door onto the open web.
    add(
        all,
        new Command(
            "fetch",
            "",
            "<url> [--offset N]",
            "read a web page by URL and print its title and readable text, a window"
                + " at a time. --offset continues a page already read, at the number"
                + " the previous window reported; omit it to start from the"
                + " beginning. A page that could not be read comes back as a"
                + " sentence explaining why, not as an error.",
            1,
            1,
            Set.of("--offset"),
            Commands::fetch));

    add(
        all,
        new Command(
            "project",
            "define",
            "<name> <directory> [--exclude A,B]",
            "name a project's workspace ON THE SERVER, replacing the whole definition —"
                + " including anything it was being lent."
                + " For files on THIS machine, pass --workspace to a run instead.",
            2,
            2,
            Set.of("--exclude"),
            Commands::projectDefine));
    add(
        all,
        new Command(
            "project",
            "workspace",
            "<name> <directory>",
            "point it at a different directory on the server, keeping the"
                + " exclusions it already has and everything it is lent.",
            2,
            2,
            Set.of(),
            Commands::projectWorkspace));
    add(
        all,
        new Command(
            "project",
            "lend",
            "<name> <directory>...",
            "let it read further directories on the server as well as its workspace."
                + " This is what makes a dot-directory like .github reachable, since"
                + " a hidden path needs a root that names it. It does not move the"
                + " project: the workspace is part of its full name.",
            2,
            Integer.MAX_VALUE,
            Set.of(),
            Commands::projectLend));
    add(
        all,
        new Command(
            "project",
            "unlend",
            "<name> <directory>...",
            "stop lending it those directories. The workspace and the exclusions are"
                + " untouched, and a directory it was not lending is left out of the"
                + " answer rather than being an error.",
            2,
            Integer.MAX_VALUE,
            Set.of(),
            Commands::projectUnlend));
    add(
        all,
        new Command(
            "project",
            "rename",
            "<name> <new name>",
            "give it a different name, and its whole archive with it. Nothing"
                + " moves on any disk; this is the one project verb that works on"
                + " a project with no workspace at all.",
            2,
            2,
            Set.of(),
            Commands::projectRename));
    add(
        all,
        new Command(
            "project",
            "forget",
            "<name>",
            "drop its workspace and everything lent with it, leaving its memories"
                + " alone. Its runs go back to"
                + " reaching only what the server itself can see.",
            1,
            1,
            Set.of(),
            Commands::projectForget));

    add(
        all,
        new Command(
            "job",
            "status",
            "<id>",
            "how a run is going, and how it ended once it has. Exits 0 only if it"
                + " answered — the same reading a watched run gets.",
            1,
            1,
            Set.of(),
            Commands::jobStatus));
    add(
        all,
        new Command(
            "job",
            "cancel",
            "<id>",
            "ask it to stop. It stops at its next turn boundary rather than"
                + " immediately, and whatever it already wrote down stands.",
            1,
            1,
            Set.of(),
            Commands::jobCancel));

    // An unmodifiable *copy* of a LinkedHashMap, and never Map.copyOf.
    // Map.copyOf gives back an unordered map — the trap ToolRegistry's
    // javadoc records for tool schemas, and it bites here for the same
    // reason: this table's order is the order the usage block reads, and a
    // Map.copyOf of it printed the groups shuffled, differently on every
    // launch, because iteration order there depends on a per-run hash seed.
    return Collections.unmodifiableMap(new LinkedHashMap<>(all));
  }

  private static void add(Map<String, Command> all, Command command) {
    all.put(command.name(), command);
  }

  /**
   * One command: how it is typed, what it does, and what it accepts.
   *
   * @param usage the arguments after the two words, as a person types them
   * @param help one paragraph, in this terminal's voice. <b>Never a tool description.</b> Those are
   *     read by a model and are edited for a model; borrowing one here would make a change written
   *     for a shell change what an agent is told
   * @param leastWords how many positional arguments are required
   * @param mostWords how many are allowed
   * @param flags what may be given with {@code --}. Anything else is refused rather than ignored: a
   *     mistyped flag that is silently dropped is a command that did something other than what was
   *     asked, quietly
   */
  private record Command(
      String group,
      String verb,
      String usage,
      String help,
      int leastWords,
      int mostWords,
      Set<String> flags,
      Handler handler) {

    /**
     * How this command is typed after {@code plowshare}: {@code group verb} ordinarily, or the
     * group word alone for a <b>solo</b> command — one with no second word naming a verb, because
     * everything after the group word is that command's own arguments. {@code search} is the first
     * of these; see {@link Commands#run}.
     */
    String name() {
      return verb.isEmpty() ? group : group + " " + verb;
    }
  }

  @FunctionalInterface
  private interface Handler {
    int handle(ServerClient server, Line line, PrintStream out, PrintStream problems)
        throws IOException;
  }

  // --- the archive -------------------------------------------------------------------

  private static int memoryIndex(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    String project = line.project();
    List<ServerClient.IndexEntry> entries = server.index(project);
    if (entries.isEmpty()) {
      out.println("the " + tier(project) + " archive remembers nothing yet.");
      return 0;
    }
    out.println(
        count(entries.size(), "memory", "memories") + " in the " + tier(project) + " archive");
    int unsearchable = 0;
    for (ServerClient.IndexEntry entry : entries) {
      out.println();
      out.println(
          oneLine(entry.id())
              + "  "
              + oneLine(entry.summary())
              + (entry.unsearchable() ? "  " + UNSEARCHABLE : ""));
      out.println("    when: " + oneLine(entry.scope()));
      unsearchable += entry.unsearchable() ? 1 : 0;
    }
    if (unsearchable > 0) {
      out.println();
      out.println(
          unsearchable
              + " of these "
              + (unsearchable == 1 ? "is " : "are ")
              + UNSEARCHABLE
              + ": written while the embedding endpoint was down, so"
              + " 'memory recall' cannot find "
              + (unsearchable == 1 ? "it" : "them")
              + " whatever the question. Nothing is lost — read "
              + (unsearchable == 1 ? "it" : "them")
              + " by id from this list.");
    }
    return 0;
  }

  private static int memoryRead(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    boolean first = true;
    for (String id : line.words()) {
      if (!first) {
        // Long enough that a body containing blank lines cannot be
        // mistaken for the start of the next memory.
        out.println();
        out.println("----------------------------------------");
        out.println();
      }
      first = false;
      render(server.read(id), out);
    }
    return 0;
  }

  private static int memoryRecall(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    String project = line.project();
    String question = line.word(0);
    ServerClient.Recall found = server.recall(project, question, line.number("--limit"));

    if (found.memories().isEmpty()) {
      // Only reached because the server answered with an empty list: one
      // that could not be reached at all threw an IOException, which the
      // dispatcher above reports as never having asked.
      out.println("nothing in the " + tier(project) + " archive is close to: " + oneLine(question));
      out.println(
          "recall matches on meaning, so a differently framed question can still"
              + " find something.");
    } else {
      out.println(
          count(found.memories().size(), "memory", "memories") + " for: " + oneLine(question));
      for (Memory memory : found.memories()) {
        out.println();
        render(memory, out);
      }
    }
    if (found.unsearchable() > 0) {
      // Said for a short answer as loudly as for an empty one, and the
      // short one is the more dangerous: somebody who got three memories
      // has no reason to suspect a fourth was unreachable.
      out.println();
      out.println(
          "incomplete answer: "
              + count(found.unsearchable(), "memory", "memories")
              + " in the "
              + tier(project)
              + " archive "
              + (found.unsearchable() == 1 ? "has" : "have")
              + " no embedding — written while the embedding endpoint was down — so recall"
              + " could not search "
              + (found.unsearchable() == 1 ? "it" : "them")
              + " at all, whatever the question. 'memory index' lists "
              + (found.unsearchable() == 1 ? "it" : "them")
              + " marked "
              + UNSEARCHABLE
              + ". Look there before concluding the archive does not hold the answer.");
    }
    return 0;
  }

  private static int memoryWrite(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    String project = line.project();
    MemoryProposal proposal =
        new MemoryProposal(
            line.required("--summary"),
            line.required("--scope"),
            line.required("--body"),
            // The account at this terminal, and not the tool surface's
            // "unknown". That word is what a memory's provenance says when
            // nobody recorded who wrote it; there is somebody here and the
            // machine already knows their name. --by is for writing on
            // behalf of somebody else.
            line.flag("--by") == null ? whoIsHere() : line.flag("--by"),
            line.flag("--where") == null ? "" : line.flag("--where"));

    WriteResult result = server.write(project, proposal);
    out.println(became(result, project));
    out.println();
    // Always, and on its own line. It is the only place a person learns
    // whether a scribe judged the write at all: every server-side fallback
    // opens "filed flat: " and says which one it was.
    out.println(oneLine(result.reason()));
    if (!result.demoted().isEmpty()) {
      out.println();
      out.println(
          "the "
              + tier(project)
              + " index was full, so these fell out of it: "
              + String.join(", ", result.demoted().stream().map(Commands::oneLine).toList())
              + ". They are still readable by id and still found by recall — falling out"
              + " of the index is not deletion.");
    }
    return 0;
  }

  private static String became(WriteResult result, String project) {
    String where = " in the " + tier(project) + " archive";
    String id = oneLine(result.memoryId());
    String target = oneLine(result.targetId());
    return switch (result.kind()) {
      case NEW -> "wrote " + id + where + ".";
      case MERGED_INTO ->
          "added this to "
              + id
              + where
              + ", which it refines. That memory"
              + " is what recall returns; no new memory was made.";
      case SUPERSEDES ->
          "wrote "
              + id
              + where
              + ", replacing "
              + target
              + ". "
              + target
              + " is retired: still readable by id, and recall will not return it again.";
    };
  }

  private static int memoryCurate(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    String project = line.word(0);
    ServerClient.StartedJob started = server.curate(project, line.number("--max-model-calls"));
    out.println(
        "started "
            + oneLine(started.id())
            + " — a curator pass over project '"
            + oneLine(project)
            + "'.");
    out.println();
    out.println(
        "it runs on the server and this did not wait for it. Confident rulings are"
            + " applied; the rest are filed for a person. Read it with 'plowshare job status "
            + oneLine(started.id())
            + "', and the queue with 'plowshare memory proposals"
            + " --project "
            + oneLine(project)
            + "'.");
    return 0;
  }

  private static int memoryProposals(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    String project = line.project();
    List<ServerClient.ProposalRow> waiting = server.proposals(project);
    if (waiting.isEmpty()) {
      out.println("nothing in the " + tier(project) + " archive is waiting on a decision.");
      return 0;
    }
    out.println(
        count(waiting.size(), "proposal", "proposals")
            + " waiting on the "
            + tier(project)
            + " archive. Each asks whether a memory should be copied into"
            + " the global archive, which every project reads.");
    for (ServerClient.ProposalRow row : waiting) {
      out.println();
      out.println(
          oneLine(row.id()) + "  " + oneLine(row.action()) + "  " + oneLine(row.memoryId()));
      out.println("    why: " + oneLine(row.reason()));
      // Null is rendered rather than skipped: a row filed before the
      // server had a column for it has no name to give, and a line that
      // stopped after the instant would read as one nobody asked for.
      out.println(
          "    asked: "
              + row.createdAt()
              + " by "
              + (row.proposedBy() == null
                  ? "somebody this queue did not record"
                  : oneLine(row.proposedBy())));
    }
    out.println();
    out.println(
        "read the memory itself with 'plowshare memory read <id>' before deciding —"
            + " the reason above is one sentence, written by whoever the line names.");
    return 0;
  }

  private static int memoryResolve(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    String id = line.word(0);
    String decision = line.word(1).trim().toLowerCase(Locale.ROOT);
    boolean accept =
        switch (decision) {
          case "accept" -> true;
          case "reject" -> false;
          default ->
              throw new IllegalArgumentException(
                  "the decision is 'accept' or 'reject', and this line says '"
                      + oneLine(decision)
                      + "'. Accepting promotes the memory into the"
                      + " global archive; rejecting records that it belongs where it is,"
                      + " which is what stops it being proposed again.");
        };
    ServerClient.Resolution settled =
        server.resolve(
            id,
            accept,
            line.flag("--reason"),
            line.flag("--by") == null ? whoIsHere() : line.flag("--by"));

    if (!accept) {
      out.println(
          oneLine(settled.proposal().id())
              + " is rejected. "
              + oneLine(settled.proposal().memoryId())
              + " stays where it is, and will not"
              + " be proposed again: a rejection is remembered, which is what stops a later"
              + " pass asking the same question.");
      return 0;
    }
    out.println(
        oneLine(settled.proposal().id())
            + " is accepted. "
            + oneLine(settled.proposal().memoryId())
            + " was promoted as "
            + oneLine(settled.promotedId())
            + ", which every project now reads; the project's"
            + " own record is retired and points at the new one.");
    if (!settled.demoted().isEmpty()) {
      out.println();
      out.println(
          "the global index was full, so these fell out of it: "
              + String.join(", ", settled.demoted().stream().map(Commands::oneLine).toList())
              + ". They are cold, not deleted.");
    }
    return 0;
  }

  // --- conversations -----------------------------------------------------------------

  private static int conversationList(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    String project = line.project();
    List<ServerClient.Conversation> open = server.conversations(project);
    if (open.isEmpty()) {
      out.println("nothing is open in the " + tier(project) + " tier.");
      return 0;
    }
    out.println(
        count(open.size(), "conversation", "conversations")
            + " open in the "
            + tier(project)
            + " tier, oldest first");
    for (ServerClient.Conversation conversation : open) {
      out.println();
      out.println(
          oneLine(conversation.id())
              + "  allowance "
              + (conversation.maxModelCalls() == null
                  ? "unlimited"
                  : count(conversation.maxModelCalls(), "model call", "model calls")));
    }
    return 0;
  }

  /**
   * Where, in one tier's conversations, something was said.
   *
   * <p><b>The one conversation verb that starts from words rather than from an id</b>, and the way
   * into the other four for somebody who has a phrase and no conversation. The other three all
   * begin by naming one.
   *
   * <p>Not {@link #page}, and not because a renderer was missing. That one takes {@link
   * ServerClient.Entries} — whole entries, in conversation order, from one conversation. A search
   * answers hits from anywhere in a tier, each carrying the words <em>around</em> a match rather
   * than the opening of an entry, and a {@link ServerClient.Reach} that says what could not be
   * looked at. Three of those four differences change what a line has to say.
   */
  private static int conversationSearch(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    String project = line.project();
    String question = line.word(0);
    ServerClient.LogHits found =
        server.searchEntries(project, question, line.number("--offset"), line.number("--limit"));

    if (found.total() == 0) {
      out.println("nothing in the " + tier(project) + " tier was said with: " + oneLine(question));
      out.println(
          "this matches on the words themselves, so an entry has to hold every"
              + " content word of the question — a shorter question finds more.");
      reach(found.reach(), true, out);
      return 0;
    }
    if (found.hits().isEmpty()) {
      // The distinction the whole terminal keeps: an offset past the end is
      // not an empty result. "Nothing here" for an offset of 500 into four
      // hits is a conclusion somebody acts on.
      out.println(
          "nothing at offset "
              + found.offset()
              + ": this question matches "
              + count(found.total(), "entry", "entries")
              + " in the "
              + tier(project)
              + " tier, counting from 0, so the last of them is at offset "
              + (found.total() - 1)
              + ".");
      return 0;
    }
    // The range and the total before the rows, for page()'s reason: a short
    // page is either the end of the hits or a reader who paged past them,
    // and only the total tells those apart.
    int last = found.offset() + found.hits().size() - 1;
    out.println(
        "hits "
            + found.offset()
            + " to "
            + last
            + " of "
            + found.total()
            + " in the "
            + tier(project)
            + " tier for: "
            + oneLine(question));
    out.println(
        "closest first. Read the conversation a hit is in with 'plowshare"
            + " conversation trajectory <id>'.");
    for (ServerClient.LogHit hit : found.hits()) {
      out.println();
      hitRow(hit, out);
    }
    // After the hits and before the continuation, deliberately. Whoever found
    // what they wanted has stopped reading by here; whoever found too little
    // is still going, and they are the reader for whom "four of them could
    // not be searched" changes the conclusion.
    reach(found.reach(), false, out);
    if (last + 1 < found.total()) {
      out.println();
      // The tier travels with the offset. A continuation that dropped
      // --project would page into the global tier under the same question
      // and look like more of the same answer.
      out.println(
          "more: plowshare conversation search "
              + shellQuoted(question)
              + (project == null ? "" : " --project " + oneLine(project))
              + " --offset "
              + (last + 1));
    }
    return 0;
  }

  /**
   * One hit: which conversation said it and where in it, then the words around the match.
   *
   * <p>The address goes first because it is what makes a hit actionable — a snippet with nothing
   * naming where it came from is a paragraph from nowhere.
   */
  private static void hitRow(ServerClient.LogHit hit, PrintStream out) {
    StringBuilder heading =
        new StringBuilder(oneLine(hit.conversationId()))
            .append("  [")
            .append(hit.ordinal())
            .append("] ")
            .append(oneLine(hit.kind()))
            .append(" — turn ")
            .append(hit.turnOrdinal());
    if (hit.recordedAt() != null) {
      heading.append(", ").append(hit.recordedAt());
    }
    if (hit.supersededBy() != null) {
      // Said on a hit for the reason it is said on a page, and one more:
      // this is a row the model of that conversation can no longer see, so
      // somebody who goes looking for it in 'conversation chat' will not
      // find it and would otherwise conclude the search was wrong.
      heading.append(", folded away by the summary at [").append(hit.supersededBy()).append(']');
    }
    if (hit.handle() != null) {
      heading.append(", handle ").append(oneLine(hit.handle()));
    }
    out.println(heading);
    out.println(indent(hit.snippet()));
    // The entry's own length, and never how much of it is "left". A snippet
    // is the middle of an entry rather than its opening, so there is no
    // remainder after it to offer — the rest of those characters are on both
    // sides of these words.
    out.println(
        QUOTE
            + "(the words around the match, which is not the start of the entry;"
            + " the whole of it is "
            + hit.length()
            + " characters)");
  }

  /**
   * What the search looked at, and what it could not, as prose.
   *
   * <p><b>Never three bare numbers.</b> A search has nowhere to put a row it cannot match on, so
   * the only honest place for one is a sentence saying why it is not in the list — and the reader
   * that sentence is for is the one with a short answer or none, about to conclude the thing was
   * never said. It is {@code memory recall}'s "incomplete answer" said of a different absence.
   */
  private static void reach(ServerClient.Reach reach, boolean nothingMatched, PrintStream out) {
    out.println();
    out.println(
        "searched "
            + count(reach.searched(), "entry", "entries")
            + (nothingMatched ? "." : " to find these."));
    if (reach.searched() == 0 && reach.ejected() == 0 && reach.recordedOnly() == 0) {
      out.println(
          "nothing has been said in this tier at all, so this is not a question"
              + " that found nothing — there was nothing to ask it of.");
      return;
    }
    if (reach.ejected() > 0) {
      boolean one = reach.ejected() == 1;
      out.println(
          "incomplete answer: "
              + count(reach.ejected(), "tool result", "tool results")
              + " had "
              + (one ? "its payload" : "their payloads")
              + " ejected by a retention sweep,"
              + " so "
              + (one ? "its" : "their")
              + " words are gone and the question could"
              + " not be asked of "
              + (one ? "it" : "them")
              + " at all, whatever it was. "
              + (one ? "The entry is" : "The entries are")
              + " still in the trajectory,"
              + " marked as ejected.");
    }
    if (reach.recordedOnly() > 0) {
      boolean one = reach.recordedOnly() == 1;
      out.println(
          count(reach.recordedOnly(), "more entry", "more entries")
              + " "
              + (one ? "is" : "are")
              + " of a kind no model is ever shown — a diagnostic, a"
              + " failed attempt, a runtime note or a plan — and this searches what was"
              + " said. Read "
              + (one ? "it" : "them")
              + " with 'plowshare conversation"
              + " trajectory <id>'.");
    }
  }

  private static int conversationChat(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    return page(
        server.chat(line.word(0), line.number("--offset"), line.number("--limit")),
        line.word(0),
        "what the model is shown: what a fold covered is gone, and so is everything"
            + " whose kind never reaches a model",
        "chat",
        out);
  }

  private static int conversationTrajectory(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    return page(
        server.trajectory(line.word(0), line.number("--offset"), line.number("--limit")),
        line.word(0),
        "everything this conversation recorded, in the order it happened — including"
            + " what a fold covered, the harness's own diagnostics, and the model"
            + " calls that failed",
        "trajectory",
        out);
  }

  /**
   * One page of entries, and the exact next command when there is more.
   *
   * <p>The range and the total go first, because they change how the rows under them are read: a
   * short page is either the end of the list or a reader who paged past it, and only the total
   * tells those apart.
   */
  private static int page(
      ServerClient.Entries entries,
      String conversation,
      String what,
      String verb,
      PrintStream out) {

    if (entries.total() == 0) {
      out.println(
          "conversation "
              + oneLine(conversation)
              + " holds nothing on this"
              + " reading: "
              + what
              + ".");
      return 0;
    }
    if (entries.entries().isEmpty()) {
      out.println(
          "nothing at offset "
              + entries.offset()
              + ": this reading of "
              + oneLine(conversation)
              + " holds "
              + entries.total()
              + " entries, counting"
              + " from 0.");
      return 0;
    }
    int last = entries.offset() + entries.entries().size() - 1;
    out.println(
        "entries "
            + entries.offset()
            + " to "
            + last
            + " of "
            + entries.total()
            + " in "
            + oneLine(conversation)
            + ", counting from 0. This reading is "
            + what
            + ".");
    for (ServerClient.Entry entry : entries.entries()) {
      out.println();
      row(entry, out);
    }
    if (last + 1 < entries.total()) {
      out.println();
      out.println(
          "more: plowshare conversation "
              + verb
              + " "
              + oneLine(conversation)
              + " --offset "
              + (last + 1));
    }
    return 0;
  }

  /** One entry: what it is, when, how long it took, and what it said. */
  private static void row(ServerClient.Entry entry, PrintStream out) {
    StringBuilder heading =
        new StringBuilder("[")
            .append(entry.ordinal())
            .append("] ")
            .append(oneLine(entry.kind()))
            .append(" — turn ")
            .append(entry.turnOrdinal());
    if (entry.recordedAt() != null) {
      heading.append(", ").append(entry.recordedAt());
    }
    if (entry.tookMillis() != null) {
      heading.append(", took ").append(entry.tookMillis()).append("ms");
    }
    if (entry.supersededBy() != null) {
      heading.append(", folded away by the summary at [").append(entry.supersededBy()).append(']');
    }
    out.println(heading);
    if (entry.excerpt() == null) {
      // Not empty text. A tool result whose payload a retention sweep has
      // taken really happened and really returned something, and printing
      // nothing would say the tool does not work.
      out.println(
          QUOTE
              + "(this payload was ejected at "
              + entry.ejectedAt()
              + "; it was "
              + entry.length()
              + " characters)");
    } else {
      out.println(indent(entry.excerpt()));
      if (entry.cut()) {
        out.println(QUOTE + "… cut here; the whole of it is " + entry.length() + " characters");
      }
    }
    for (ServerClient.Asked asked : entry.toolCalls()) {
      out.println(
          QUOTE
              + "asked "
              + oneLine(asked.name())
              + ": "
              + oneLine(asked.arguments())
              + (asked.cut() ? " …" : ""));
    }
  }

  private static int conversationContext(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    String conversation = line.word(0);
    String agent = line.flag("--agent");
    ServerClient.Context context = server.context(conversation, agent);

    out.println(
        oneLine(conversation)
            + ": "
            + count(context.turns(), "turn", "turns")
            + ", "
            + context.turnsMeasured()
            + " of them measured.");
    out.println();
    if (context.sent() == null) {
      out.println(
          "no turn of this conversation has reached a model call, so nothing has"
              + " been measured. That is not a cost of zero.");
    } else {
      out.println(
          "the whole prompt of turn "
              + context.sentAtTurn()
              + " cost "
              + context.sent()
              + " tokens, counted by the model's own tokenizer. That is"
              + " the entire request — the system prompt, the tool schemas and everything"
              + " said — and it is the one token figure that exists.");
    }
    out.println();
    out.println("what cannot be given, and why:");
    for (ServerClient.Unavailable why : context.unavailable()) {
      out.println();
      out.println(oneLine(why.component()) + ":");
      out.println(indent(why.reason()));
    }
    if (context.prefix() == null) {
      out.println();
      out.println(
          "no agent was named, so the fixed block is not priced. A conversation"
              + " does not record which agent answered a turn — the agent is chosen per"
              + " turn — so pass --agent to see what one agent's system prompt and tool"
              + " schemas cost, in characters.");
      return 0;
    }
    ServerClient.Prefix prefix = context.prefix();
    out.println();
    out.println(
        "the fixed block of '"
            + oneLine(prefix.agent())
            + "' on model '"
            + oneLine(prefix.model())
            + "', in characters of the JSON actually sent —"
            + " characters and not tokens, and they must not be scaled into tokens:");
    out.println();
    out.println("system prompt — " + prefix.systemPromptCharacters() + " characters");
    out.println(
        "tool schemas — "
            + prefix.toolCharacters()
            + " characters across "
            + prefix.tools().size()
            + " tools");
    for (ServerClient.ToolCost tool : prefix.tools()) {
      out.println("  " + oneLine(tool.name()) + " — " + tool.characters() + " characters");
    }
    return 0;
  }

  // --- the corpus --------------------------------------------------------------------

  /**
   * Ask one document a question.
   *
   * <p><b>The document id is a positional argument and not a flag</b>, unlike {@code --document} on
   * {@code document citations} one verb over. There it narrows a listing and leaving it out is a
   * legal, meaningful request; here it <em>is</em> the thing being asked, and an ask with no
   * document is not a broader ask — it is a corpus search, which already has a verb.
   */
  private static int documentAsk(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    String document = line.word(0);
    String question = line.word(1);
    ServerClient.StartedJob started =
        server.askDocument(document, question, line.number("--max-model-calls"));

    out.println(
        "started "
            + oneLine(started.id())
            + " — a deliberation over document "
            + oneLine(document)
            + ".");
    out.println();
    out.println(
        "three agents run in series on the server and this did not wait for them:"
            + " one drafts an answer from the document's whole structure and its most"
            + " relevant passages, one challenges that draft from what the document argues"
            + " as a whole, and one writes the final answer. Read it with 'plowshare job"
            + " status "
            + oneLine(started.id())
            + "'.");
    out.println();
    out.println(
        "the answer names the paragraph and quotes the words behind each claim, and"
            + " every quotation is checked against the paragraph it names — one that is not"
            + " there is reported as a failed attribution rather than dropped.");
    return 0;
  }

  /**
   * Which papers are about something.
   *
   * <h2>How much of the corpus was ranked is printed on every answer</h2>
   *
   * <p>Not only on an empty one. A top result out of two rankable documents in a corpus of forty is
   * a different fact from a top result out of forty, and a person reading a list without that line
   * has no way to tell which they are looking at. The count is not rare: a document is summarised
   * before anything embeds the summary, so a corpus ingested minutes ago and never restarted is
   * entirely unrankable while every word of it is searchable.
   */
  private static int documentRank(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    String question = line.word(0);
    ServerClient.Ranking found = server.rankDocuments(question, line.number("--limit"));

    if (found.documents().isEmpty()) {
      out.println("no document in the corpus is about: " + oneLine(question));
    } else {
      out.println(
          count(found.documents().size(), "document", "documents") + " for: " + oneLine(question));
      for (ServerClient.RankedDocument row : found.documents()) {
        out.println();
        out.println(
            String.format(Locale.ROOT, "%.2f", row.score())
                + "  "
                + oneLine(row.sourceName())
                + " — "
                + oneLine(row.title()));
        out.println("    id: " + row.documentId());
        if (row.summary() != null) {
          out.println("    " + oneLine(row.summary()));
        }
      }
    }
    out.println();
    out.println(
        found.rankable()
            + " documents in the corpus could be ranked"
            + (found.unranked() > 0
                ? ", and "
                    + found.unranked()
                    + " have a summary nothing has embedded"
                    + " yet, so they were not compared whatever the question was."
                : "."));
    // Said once, at the bottom, where a person has already read the list:
    // the number ranks by subject and a reader who takes it for agreement
    // will read a refutation as a confirmation.
    out.println(
        "this ranks by what a paper is ABOUT. A paper that argues against something"
            + " is about it.");
    return 0;
  }

  /**
   * A cheap guess at what one paper says about a claim.
   *
   * <h2>The caveat is printed with the numbers and not under them</h2>
   *
   * <p>The output is two floats, and two floats look like a measurement. What this actually is: the
   * claim and the string {@code "not " + claim} are embedded and both are compared against a
   * sentence a model wrote about a paper nothing here has read. Anchor's own type says twice that
   * it is not a substitute for reading the paper, and its shell prints the word {@code vector_only}
   * at the bottom under the label "Mode" — which is true and is not the same as saying it.
   *
   * <h2>Topical before stance, in that order, because the order is the reading</h2>
   *
   * <p>A stance near zero means "argues both ways" when the topical number is high and "is not
   * about this at all" when it is low. Those are opposite conclusions from the same number, and a
   * reader shown the difference first has already drawn one of them.
   */
  private static int documentStance(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    ServerClient.Stance read = server.documentStance(line.word(0), line.word(1));

    out.println("claim: " + oneLine(read.claim()));
    out.println();
    out.println(
        String.format(Locale.ROOT, "about it   %+.3f", read.topical())
            + "   how far this paper is ABOUT the claim at all");
    out.println(
        String.format(Locale.ROOT, "leans      %+.3f", read.stance())
            + "   positive toward the claim, negative against it");
    out.println();
    out.println(
        "read the first number first: a lean near zero means the paper argues both"
            + " ways when it is about the claim, and means nothing at all when it is not.");
    out.println();
    out.println(
        "this is "
            + oneLine(read.basis())
            + " — the claim and the words \"not\" plus"
            + " the claim were compared against a one-sentence summary a model wrote about"
            + " this paper. NOTHING HAS READ THE PAPER. 'plowshare document ask "
            + oneLine(read.documentId().toString())
            + " <question>' is what does.");
    return 0;
  }

  /**
   * A passage and the paper around it.
   *
   * <h2>The stack prints from the document down, and the text last</h2>
   *
   * <p>Not a layout preference. A chunk read before the argument it sits inside is the failure the
   * whole hierarchy was built to fix — a passage can be a position the paper goes on to refute —
   * and a renderer that put the words first would reproduce that failure in the one verb that
   * exists to prevent it.
   *
   * <h2>{@code --document} is a flag where {@code document ask} takes a positional</h2>
   *
   * <p>The distinction that verb's own javadoc draws, on the other side of it: an ask with no
   * document is not a smaller ask, so the id is what is being asked and is positional. A retrieve
   * with no document is a complete and ordinary request — the whole corpus — so the id narrows
   * something that already works, which is what a flag is.
   */
  private static int documentRetrieve(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    String question = line.word(0);
    ServerClient.Retrieved found =
        server.retrieve(question, line.flag("--document"), line.number("--limit"));

    String scope =
        found.document() == null
            ? "the corpus"
            : "document " + oneLine(found.document().toString());
    if (found.hits().isEmpty()) {
      out.println("nothing in " + scope + " is close to: " + oneLine(question));
      return 0;
    }
    out.println(
        count(found.hits().size(), "passage", "passages")
            + " from "
            + scope
            + " for: "
            + oneLine(question));
    for (ServerClient.RetrievedHit hit : found.hits()) {
      ServerClient.ChunkDetail chunk = hit.chunk();
      out.println();
      out.println(
          oneLine(chunk.sourceName())
              + " — "
              + oneLine(chunk.title())
              + ", paragraph "
              + chunk.paragraphOrdinal()
              + " — "
              + String.format(Locale.ROOT, "%.2f", hit.score()));
      // The paragraph and not the chunk, for `documentSearch`'s reason:
      // a chunk is an artefact of the chunker and moves whenever the chunk
      // rule does.
      out.println("    cite: " + chunk.paragraphId());
      if (chunk.documentSummary() != null) {
        out.println("    document: " + oneLine(chunk.documentSummary()));
      }
      if (chunk.chapter() != null) {
        out.println("    " + Structural.name(chunk.chapter()) + summarised(chunk.chapter()));
      }
      // A null section and a null chapter are one state, and Structural
      // says it in one phrase -- so the line above is skipped and this one
      // carries it, rather than both reporting the same absence twice in
      // different words.
      out.println("    " + Structural.name(chunk.section()) + summarised(chunk.section()));
      if (chunk.paragraphSummary() != null) {
        out.println("    paragraph: " + oneLine(chunk.paragraphSummary()));
      }
      out.println(indent(chunk.text()));
    }
    return 0;
  }

  /**
   * What a unit covers, after its name, or nothing at all. Two call sites in one method and a
   * helper anyway, because the null it guards is the one a reader of that method most easily loses
   * track of.
   */
  private static String summarised(ServerClient.Unit unit) {
    return unit == null || unit.summary() == null ? "" : " — " + oneLine(unit.summary());
  }

  /**
   * What the corpus holds.
   *
   * <h2>The verb the register was missing</h2>
   *
   * <p>Every other document verb here takes an id or answers with passages, and nothing said where
   * an id comes from. {@code document ask} needs one; {@code document citations} answers with the
   * ids of documents that have been cited, which is a smaller set than the ones that exist. A
   * person who had uploaded a paper and forgotten its name had no way back to it from a terminal.
   *
   * <h2>The empty answer names which question was asked</h2>
   *
   * <p>{@code documentCitations}' rule one verb over: an empty corpus and a naming that matched
   * nothing are two different facts that print as the same blank, and a person reading "no
   * documents" with no scope beside it would take the first reading whichever was true.
   */
  private static int documentList(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    ServerClient.DocumentPage page =
        server.listDocuments(
            line.flag("--naming"), line.number("--limit"), line.number("--offset"));

    if (page.documents().isEmpty()) {
      out.println(
          page.naming() == null
              ? "the corpus holds no documents."
              : "no document is named anything like: " + oneLine(page.naming()));
      return 0;
    }
    out.println(
        page.documents().size()
            + " of "
            + count(page.total(), "document", "documents")
            + (page.naming() == null ? "" : " named like " + oneLine(page.naming()))
            + ".");
    for (ServerClient.DocumentRow row : page.documents()) {
      out.println();
      out.println(oneLine(row.sourceName()) + " — " + oneLine(row.title()));
      out.println("    id: " + row.documentId());
      out.println(
          "    "
              + row.paragraphs()
              + " paragraphs, "
              + row.sections()
              + " sections, "
              + row.chapters()
              + " chapters, "
              + row.chunks()
              + " passages");
      out.println(
          row.summary() == null
              ? "    nothing has summarised it yet."
              : "    " + oneLine(row.summary()));
    }
    return 0;
  }

  /**
   * One document's structure.
   *
   * <h2>Rendered in the document's own word for its parts</h2>
   *
   * <p>V26's {@code top_level_label}, and this is a second reader for it beside the ask's prompts.
   * A paper that calls its top-level parts sections throughout should not be shown a heading that
   * says "chapters", because the person reading the outline is going to go looking for that word in
   * the paper.
   *
   * <h2>A unit with no name says so in words</h2>
   *
   * <p>{@code Structural.name}, and Anchor's shell is the reason it exists: given the same JSON it
   * interpolates a null title straight into a heading and prints a column of the word "null". A
   * document that declared no headings has a synthetic chapter over a synthetic section, which is
   * the state most of this corpus is in, so that is the common case rather than the odd one.
   */
  private static int documentShow(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    ServerClient.DocumentOutline outline = server.describeDocument(line.word(0));

    out.println(oneLine(outline.sourceName()) + " — " + oneLine(outline.title()));
    out.println("    id: " + outline.documentId());
    out.println(
        outline.summary() == null
            ? "    nothing has summarised it yet."
            : "    " + oneLine(outline.summary()));
    String parts =
        outline.vocabulary() == null
            ? "chapters"
            : outline.vocabulary().toLowerCase(Locale.ROOT) + "s";
    out.println();
    out.println(
        count(outline.chapters().size(), parts.substring(0, parts.length() - 1), parts) + ":");
    for (ServerClient.ChapterOutline chapter : outline.chapters()) {
      out.println();
      out.println(Structural.name(chapter.title(), chapter.synthetic()));
      if (chapter.summary() != null) {
        out.println("    " + oneLine(chapter.summary()));
      }
      for (ServerClient.Unit section : chapter.sections()) {
        out.println("  - " + Structural.name(section));
        if (section.summary() != null) {
          out.println("        " + oneLine(section.summary()));
        }
      }
    }
    return 0;
  }

  private static int documentSearch(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    String question = line.word(0);
    ServerClient.DocumentSearch found = server.searchDocuments(question, line.number("--limit"));

    if (found.hits().isEmpty()) {
      out.println("nothing in the corpus is close to: " + oneLine(question));
    } else {
      out.println(count(found.hits().size(), "passage", "passages") + " for: " + oneLine(question));
      for (ServerClient.DocumentHit hit : found.hits()) {
        out.println();
        out.println(
            oneLine(hit.sourceName())
                + " — "
                + oneLine(hit.title())
                + ", paragraph "
                + hit.paragraphOrdinal());
        // The paragraph and not the chunk. A chunk is an artefact of the
        // chunker and moves whenever the chunk rule does; the paragraph
        // id is the thing that survives a re-ingest and is worth writing
        // down.
        out.println("    cite: " + hit.paragraphId());
        // AND THE DOCUMENT, WHICH IS THE ONE THING HERE THE NEXT VERB
        // NEEDS. `document ask` takes a document id as its first
        // positional argument and nothing else on this surface prints
        // one: the field has been on DocumentSearch since the endpoint
        // shipped and this renderer dropped it, so the two verbs sat one
        // line apart in the usage block with no route from the first to
        // the second. Anchor's shell prints the id on every search line
        // for exactly this reason -- its `use <uuid-or-title>` is what a
        // person types next.
        //
        // Labelled `ask:` rather than `document:` because the label is
        // the instruction. A bare id under a hit is a value whose use a
        // person has to infer; this one names the verb it goes into.
        out.println("    ask:  " + hit.documentId());
        out.println(indent(hit.paragraphText()));
      }
    }
    out.println();
    out.println(
        found.searchable()
            + " passages in the corpus can be reached by a question"
            + (found.unsearchable() > 0
                ? ", and "
                    + found.unsearchable()
                    + " hold their text and no vector, so"
                    + " they were skipped whatever the question was."
                : "."));
    return 0;
  }

  /**
   * What the corpus has been used for.
   *
   * <h2>The empty answer names which question was asked</h2>
   *
   * <p>Three scopes produce one empty list and it means something different under each — nothing
   * has been cited, this conversation cited nothing, this paper has never been cited. A person
   * reading "no citations" with no scope beside it would take the first reading whichever was true.
   *
   * <h2>A stale citation shows no passage, which is the point of it</h2>
   *
   * <p>V18: a dangling citation is "a staleness signal and never a silent repoint at different
   * text". So there are two of them and they say different things — the paragraph was edited away,
   * or the document is gone — and neither prints words, because there are none to print.
   *
   * <h2>The passage is indented like every other quotation here</h2>
   *
   * <p>{@code documentSearch} one method up, for its reason: the words belong to whoever wrote the
   * document, and a paper whose own prose is shaped like this listing's headings must not be able
   * to forge one.
   */
  private static int documentCitations(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    String conversation = line.flag("--conversation");
    String document = line.flag("--document");
    if (conversation != null && document != null) {
      problems.println(
          "--conversation and --document are two different questions: what"
              + " one conversation cited, and what has cited one document. Pass one of"
              + " them, or neither for the most recent citations in the corpus.");
      return 2;
    }
    ServerClient.Citations found = server.citations(conversation, document, line.number("--limit"));

    if (found.citations().isEmpty()) {
      out.println(nothingCited(found.scope()));
      return 0;
    }
    out.println(
        count(found.citations().size(), "citation", "citations")
            + ", "
            + ("conversation".equals(found.scope())
                ? "in the order they were made"
                : "most recent first"));
    for (ServerClient.Citation cited : found.citations()) {
      out.println();
      out.println(
          oneLine(cited.sourceName())
              + (cited.title() == null ? "" : " — " + oneLine(cited.title()))
              + ", paragraph "
              + cited.paragraphOrdinal());
      out.println(
          "    cited by "
              + oneLine(cited.agent())
              + " on "
              + cited.citedAt()
              + (cited.conversationId() == null
                  ? ", in no conversation"
                  : " in " + oneLine(cited.conversationId()) + ", turn " + cited.turnOrdinal()));
      if ("resolves".equals(cited.standing())) {
        out.println("    cite: " + cited.paragraphId());
        out.println(indent(cited.paragraphText()));
      } else if ("paragraph_gone".equals(cited.standing())) {
        out.println(
            "    stale: a later ingest edited or removed that paragraph."
                + " The document is still in the corpus.");
      } else {
        out.println("    stale: that document is no longer in the corpus.");
      }
    }
    return 0;
  }

  /** Which nothing this is, spelled per scope. */
  private static String nothingCited(String scope) {
    if ("conversation".equals(scope)) {
      return "that conversation has cited nothing";
    }
    if ("document".equals(scope)) {
      return "nothing has cited that document";
    }
    return "nothing in the corpus has been cited yet — this is a record of past answers"
        + " and says nothing about what the documents contain";
  }

  // --- search --------------------------------------------------------------------------

  /*
   * The three defaults below are SearchTools.DEFAULT_PAGE_SIZE,
   * SearchTools.DEFAULT_MAX and SearchTools.DEFAULT_PAGE, read directly
   * rather than re-declared here. A person at this terminal and a model
   * through MCP asking the same bare `search` have to be offered the same
   * numbers, and a second copy of a constant is a standing invitation for
   * exactly the drift this comment used to only promise would not happen —
   * one number that cannot disagree with itself is the actual guarantee.
   * See SearchTools' own javadoc for why DEFAULT_MAX is not simply
   * DEFAULT_PAGE_SIZE repeated.
   */

  private static int search(ServerClient server, Line line, PrintStream out, PrintStream problems)
      throws IOException {

    String query = line.word(0);
    Integer pageSizeGiven = line.number("--page-size");
    Integer maxGiven = line.number("--max");
    Integer pageGiven = line.number("--page");
    int pageSize = pageSizeGiven == null ? SearchTools.DEFAULT_PAGE_SIZE : pageSizeGiven;
    int max = maxGiven == null ? SearchTools.DEFAULT_MAX : maxGiven;
    int page = pageGiven == null ? SearchTools.DEFAULT_PAGE : pageGiven;

    SearchPage found = server.search(query, pageSize, max, page);

    if (found.refusal() != null) {
      // Composed by the server, and it CAN quote a provider — which is
      // why it is flattened rather than printed as-is. A refusal out of
      // an exhausted ladder carries SearchLadder.messageOrDefault's
      // note per rung, and that note embeds the failing provider's own
      // message verbatim: text off a process this deployment does not
      // run, arriving in this terminal's voice. This comment used to
      // say the sentence was never a provider's, which would have
      // invited a later edit to drop the belt as redundant. The SHAPE
      // of the sentence is the server's; the strings inside it are not.
      out.println(oneLine(found.refusal()));
      return 0;
    }
    if (found.hits().isEmpty()) {
      // total() tells an empty search apart from a page past the end of
      // a search that found something — the same distinction 'memory
      // recall' and 'conversation search' already draw, and for the same
      // reason: a person reading "nothing" with no count beside it would
      // conclude the search failed rather than that they overpaged it.
      out.println(
          found.total() == 0
              ? "no results for: " + oneLine(query)
              : "nothing on page "
                  + found.page()
                  + " of this search — it holds "
                  + count(found.total(), "result", "results")
                  + " in all.");
      return 0;
    }

    out.println(
        count(found.hits().size(), "result", "results")
            + " of "
            + count(found.total(), "result", "results")
            + ", page "
            + found.page()
            + (found.hasMore() ? " — more after this" : " — no more after this")
            + ", for: "
            + oneLine(query));
    for (Hit hit : found.hits()) {
      out.println();
      // Every one of these three came off a page somebody else's server
      // served, on the far side of a provider this deployment does not
      // run — the least trusted text this terminal ever prints. Flattened
      // rather than indented, unlike a memory's body: a search result has
      // no field expected to span lines, so there is nothing here for
      // indenting to preserve, and flattening is the whole of what column
      // zero needs protecting from.
      out.println(oneLine(hit.title()));
      out.println("    " + oneLine(hit.url()));
      out.println("    " + oneLine(hit.snippet()));
    }
    if (found.hasMore()) {
      out.println();
      // --max is repeated even when it is the default, deliberately: the
      // stored result set page 2 reads from is keyed on the exact max
      // page 1 asked with, and a continuation line that let a person
      // change it without saying so would send them looking at a
      // different search under the same query.
      out.println(
          "more: plowshare search "
              + shellQuoted(query)
              + " --max "
              + max
              + " --page-size "
              + pageSize
              + " --page "
              + (found.page() + 1));
    }
    return 0;
  }

  // --- fetch --------------------------------------------------------------------------

  /**
   * The one command on this surface that reuses a tool's own rendering rather than composing its
   * own — see {@link FetchTools}' own class comment, "{@code cli.Commands} calls render directly",
   * for why that is not the drift this class's own header otherwise warns against: {@code fetch} is
   * the CLI verb's own name as well as the tool's, and a page's title and body are the identical
   * untrusted bytes whichever surface prints them.
   */
  private static int fetch(ServerClient server, Line line, PrintStream out, PrintStream problems)
      throws IOException {

    String url = line.word(0);
    Integer offsetGiven = line.number("--offset");
    int offset = offsetGiven == null ? FetchTools.DEFAULT_OFFSET : offsetGiven;

    FetchWindow window = server.fetch(url, offset);
    out.println(FetchTools.render(window));
    return 0;
  }

  // --- projects ----------------------------------------------------------------------

  private static int projectDefine(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    List<String> exclusions = new ArrayList<>();
    for (String each :
        line.flag("--exclude") == null ? new String[0] : line.flag("--exclude").split(",")) {
      if (!each.isBlank()) {
        exclusions.add(each.trim());
      }
    }
    leash(server.defineProject(line.word(0), line.word(1), List.copyOf(exclusions)), out);
    return 0;
  }

  private static int projectWorkspace(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    leash(server.setProjectWorkspace(line.word(0), line.word(1)), out);
    return 0;
  }

  /**
   * Lend, and take back, further directories.
   *
   * <p>Variadic rather than a comma-separated flag like {@code --exclude}, and the difference is
   * what a shell can do for you: a directory is a thing a person tab-completes and a
   * comma-separated list is a thing they type, and a path may legally contain a comma. {@code
   * --exclude} predates this and takes paths that need not exist, so completion has nothing to
   * offer it.
   */
  private static int projectLend(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    leash(server.lendProject(line.word(0), roots(line)), out);
    return 0;
  }

  private static int projectUnlend(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    leash(server.unlendProject(line.word(0), roots(line)), out);
    return 0;
  }

  /**
   * Every word after the project name. The arity floor of 2 is what makes this non-empty, so there
   * is no empty-list branch here to test.
   */
  private static List<String> roots(Line line) {
    return List.copyOf(line.words().subList(1, line.words().size()));
  }

  /**
   * A project's leash as the server now holds it, and never as it was asked for.
   *
   * <p>The exclusions are longer than whatever was sent — the server adds its own directory, so its
   * configuration, its agent definitions, its sampling profiles and its own console token stay out
   * of reach whatever a row says — and that is the point of printing them: a person who never sees
   * the server's own paths has no way to know the leash is shorter than the directory they named.
   *
   * <p><b>The lent directories are printed as part of the leash and the workspace is printed as the
   * place</b>, which is why the first line says "is at" rather than "reads". They were one line
   * while a project had one directory; keeping them one line would have made every path after the
   * first look like somewhere the project might be, and where a project is is the thing its full
   * name is composed from.
   */
  private static void leash(ServerClient.ProjectView project, PrintStream out) {
    out.println(
        "project '"
            + oneLine(project.name())
            + "' is at "
            + oneLine(project.workspace())
            + " on the server.");
    // A null list and not merely an empty one, because a client is a
    // separately installed binary: a server that predates the `lent` column
    // sends this field not at all, and Jackson leaves it null. Printing the
    // workspace and nothing else is the right answer for that server -- it
    // is the whole leash there -- and throwing would make an old server look
    // like a broken client.
    for (String lent : project.lent() == null ? List.<String>of() : project.lent()) {
      out.println("  and reads " + oneLine(lent));
    }
    if (project.exclusions().isEmpty()) {
      return;
    }
    out.println();
    out.println("it may not reach:");
    for (String excluded : project.exclusions()) {
      out.println("  " + oneLine(excluded));
    }
  }

  private static int projectRename(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    server.moveProject(line.word(0), line.word(1));
    out.println(
        "project '"
            + oneLine(line.word(0))
            + "' is now called '"
            + oneLine(line.word(1))
            + "'. Its memories and its conversations came with it —"
            + " they reach the project through an id that did not change — and nothing"
            + " moved on any disk.");
    return 0;
  }

  private static int projectForget(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    server.forgetProject(line.word(0));
    out.println(
        "project '"
            + oneLine(line.word(0))
            + "' has no workspace now, and nothing"
            + " it was lending is lent any more. Its memories"
            + " are untouched; its runs reach only what the server itself can see, unless a"
            + " client roots it somewhere.");
    return 0;
  }

  // --- a run already going -----------------------------------------------------------

  private static int jobStatus(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    ServerClient.JobStatus job = server.job(line.word(0));
    out.println(oneLine(job.id()) + " — " + oneLine(job.agent()));
    if (job.outcome() == null) {
      out.println(
          "still running."
              + (job.cancelRequested()
                  ? " It has been asked to stop and will do so at its next turn boundary."
                  : " Nothing has been concluded yet."));
      return 0;
    }
    // Plowshare's own reading, and shared rather than copied: `answered` and
    // not the ending decides whether the text is shown, and a second reading
    // of that rule is a second place for it to drift. Nothing was dropped
    // here — this terminal watched no stream — so the count is 0.
    return Plowshare.say(job.outcome(), 0, out, problems) ? 0 : 1;
  }

  private static int jobCancel(
      ServerClient server, Line line, PrintStream out, PrintStream problems) throws IOException {

    ServerClient.JobStatus job = server.cancelJob(line.word(0));
    out.println(oneLine(job.id()) + " — " + oneLine(job.agent()));
    if (job.outcome() != null) {
      out.println(
          "it had already finished, so there was nothing to stop. Read how it"
              + " ended with 'plowshare job status "
              + oneLine(job.id())
              + "'.");
      return 0;
    }
    out.println(
        "asked to stop. It stops at its next turn boundary rather than immediately"
            + " — a turn already in flight is paid for either way — so it reads as running"
            + " for a moment yet. Whatever it already wrote to the archive or the queue"
            + " stands.");
    return 0;
  }

  // --- usage --------------------------------------------------------------------------

  /**
   * The command surface as the top-level usage block shows it: one line per group, naming its
   * verbs.
   *
   * <p><b>Not every command in full.</b> Seventeen blocks of prose is what a person gets for
   * mistyping a flag, and a usage block that has to be scrolled past is one nobody reads the top
   * of. The full block for one group is printed by the thing that asks for it — a group word with
   * no verb, or a verb nothing offers — which is the moment somebody is actually looking for it.
   */
  static String usage() {
    StringBuilder out = new StringBuilder("\n");
    for (String group : groups()) {
      List<Command> inGroup = new ArrayList<>();
      for (Command command : TABLE.values()) {
        if (command.group().equals(group)) {
          inGroup.add(command);
        }
      }
      if (inGroup.size() == 1 && inGroup.get(0).verb().isEmpty()) {
        // A solo command: there is no <verb> to fill in, because
        // everything after the group word is this command's own
        // arguments rather than a choice among several actions. Its
        // own usage string stands in where the verb list would.
        out.append(String.format("  plowshare %-21s %s%n", group, inGroup.get(0).usage()));
        continue;
      }
      List<String> verbs = new ArrayList<>();
      for (Command command : inGroup) {
        verbs.add(command.verb());
      }
      out.append(
          String.format("  plowshare %-21s %s%n", group + " <verb>", String.join(", ", verbs)));
    }
    out.append("\nAny of those with no verb says what each of its verbs does.\n");
    return out.toString();
  }

  private static String usageFor(String group) {
    StringBuilder out = new StringBuilder();
    for (Command command : TABLE.values()) {
      if (command.group().equals(group)) {
        out.append(oneUsage(command));
      }
    }
    return out.toString();
  }

  /**
   * One command's own block: how it is typed, then what it does, wrapped.
   *
   * <p>Wrapped here rather than written pre-wrapped, because the help text is one paragraph in a
   * source file and the column it has to fit is a property of this block. Prose that had its line
   * breaks typed in would be re-indented by hand every time a verb was renamed.
   */
  private static String oneUsage(Command command) {
    StringBuilder out = new StringBuilder("  plowshare ").append(command.name());
    if (!command.usage().isEmpty()) {
      out.append(' ').append(command.usage());
    }
    out.append('\n');
    for (String wrapped : wrap(command.help())) {
      out.append("      ").append(wrapped).append('\n');
    }
    return out.toString();
  }

  /**
   * The words a person types after {@code plowshare} that are groups rather than agents, in the
   * order the usage block reads them.
   */
  private static Set<String> groups() {
    Set<String> groups = new LinkedHashSet<>();
    for (Command command : TABLE.values()) {
      groups.add(command.group());
    }
    return groups;
  }

  /**
   * How to run an agent whose name is one of the reserved words. Said in every refusal a group word
   * produces, because that is the moment somebody who has such an agent finds out.
   */
  private static String escape(String group) {
    return "If you meant to run an agent called '"
        + group
        + "', that is 'plowshare run "
        + group
        + " <task>' — after 'run', the next word is an agent whatever it spells.";
  }

  private static List<String> wrap(String prose) {
    List<String> lines = new ArrayList<>();
    StringBuilder line = new StringBuilder();
    for (String word : prose.split("\\s+")) {
      if (line.length() > 0 && line.length() + 1 + word.length() > 70) {
        lines.add(line.toString());
        line.setLength(0);
      }
      line.append(line.length() == 0 ? "" : " ").append(word);
    }
    if (line.length() > 0) {
      lines.add(line.toString());
    }
    return lines;
  }

  // --- rendering ------------------------------------------------------------------------

  /**
   * One memory as a person reads it: what it claims, when it applies, where it came from, and —
   * when it is retired — why.
   */
  private static void render(Memory memory, PrintStream out) {
    out.println(
        oneLine(memory.id())
            + "  ["
            + oneLine(memory.state().wireName())
            + "]  "
            + (memory.home().isGlobal()
                ? "everywhere"
                : "project " + oneLine(memory.home().project())));
    out.println(oneLine(memory.summary()));
    out.println("when: " + oneLine(memory.scope()));
    out.println(
        "formed: "
            + memory.formed().at()
            + " by "
            + oneLine(memory.formed().by())
            + (memory.formed().where() == null || memory.formed().where().isBlank()
                ? ""
                : " — " + oneLine(memory.formed().where())));
    if (memory.supersedes() != null) {
      out.println("replaced: " + oneLine(memory.supersedes()));
    }
    if (memory.supersededBy() != null) {
      out.println("superseded by: " + oneLine(memory.supersededBy()) + " — prefer that one");
    }
    if (memory.invalidation() != null) {
      // The reason is the whole point of keeping an invalidated memory: it
      // is what stops the stale fact being rediscovered and written back
      // in.
      out.println(
          "no longer true, as of "
              + memory.invalidation().at()
              + " ("
              + oneLine(memory.invalidation().by())
              + "): "
              + oneLine(memory.invalidation().reason()));
    }
    out.println();
    out.println(indent(memory.body()));
  }

  /**
   * "global" or "project 'payments'", for the sentences that have to name a tier. A message that
   * says nothing was found without saying where it looked cannot be acted on.
   */
  private static String tier(String project) {
    return project == null ? "global" : "project '" + oneLine(project) + "'";
  }

  private static String count(int howMany, String one, String many) {
    return howMany + " " + (howMany == 1 ? one : many);
  }

  /**
   * A single-line slot, flattened, so nothing a stored value contains can put a line of its own at
   * column zero beside this class's headings.
   */
  /**
   * {@code value} as a single shell word, ready to be pasted back into the terminal it was printed
   * to.
   *
   * <p><b>A continuation line is a command, not prose, and that is the whole point of it.</b>
   * Wrapping the argument in bare single quotes — which is what this used to do inline — produces
   * an unrunnable line the moment the argument contains an apostrophe: {@code plowshare search
   * 'what's new'} closes the quote at {@code what}, and a person who copies the line this terminal
   * printed gets a shell continuation prompt instead of page two. The words a person searched for
   * are exactly where apostrophes live, so this is the common case rather than an exotic one.
   *
   * <p>The POSIX idiom for it: close the quote, emit an escaped apostrophe, reopen. {@code what's}
   * becomes {@code 'what'\''s'}, which every bourne-family shell reads back as the original four
   * characters. Nothing else needs escaping inside single quotes, which is why they are the quoting
   * to use here rather than double quotes plus a list of metacharacters to remember.
   *
   * <p>Flattened through {@link #oneLine} first, on this file's standing rule: a value that reached
   * a continuation line with a newline in it would open a line of its own at column zero, and a
   * quoted newline is still a newline. Not to be confused with {@link #escape}, which is about a
   * group word colliding with an agent's name and does no quoting at all.
   */
  private static String shellQuoted(String value) {
    return "'" + oneLine(value).replace("'", "'\\''") + "'";
  }

  private static String oneLine(String value) {
    return value == null ? "" : LINE_BREAK.matcher(value).replaceAll(" ").strip();
  }

  /**
   * Content this class is quoting rather than saying, indented line by line. Flattening would
   * destroy a body that legitimately has line breaks in it; indenting keeps the shape and still
   * leaves column zero to the terminal.
   */
  private static String indent(String content) {
    StringBuilder out = new StringBuilder();
    for (String each : LINE_BREAK.split(content == null ? "" : content.strip(), -1)) {
      out.append(out.length() == 0 ? "" : "\n").append(QUOTE).append(each);
    }
    return out.toString();
  }

  /**
   * Whoever is at this terminal, for the two calls that record who did something.
   *
   * <p>A login name and not a person's name, which is what the archive gets to know and is enough
   * to tell one decider from another months later. Falls back to the word the tool surface uses
   * when a JVM does not report one at all — that word means "nobody recorded this", which is then
   * true.
   */
  private static String whoIsHere() {
    String user = System.getProperty("user.name");
    return user == null || user.isBlank() ? "unknown" : user.trim();
  }

  private static String describe(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.toString() : message;
  }

  // --- what was typed ------------------------------------------------------------------

  /**
   * One command line, split into what it names and what it sets.
   *
   * <p>Hand-parsed and not a library, for {@code cli.Plowshare.Options}' reason: this module's
   * dependencies are okhttp, Jackson and slf4j, and a terminal that is the acceptance test for a
   * protocol is a poor reason to make that four.
   */
  private record Line(List<String> words, Map<String, String> flags, Command command) {

    static Line of(String[] rest, Command command) {
      List<String> words = new ArrayList<>();
      Map<String, String> flags = new LinkedHashMap<>();
      for (int at = 0; at < rest.length; at++) {
        String argument = rest[at];
        if (!argument.startsWith("--")) {
          words.add(argument);
          continue;
        }
        if (!SERVER.equals(argument) && !command.flags().contains(argument)) {
          // Refused rather than ignored. A mistyped flag that is
          // silently dropped is a command that quietly did something
          // other than what was asked — 'memory recall x --projekt pay'
          // would answer from the global tier and look like an answer.
          throw new IllegalArgumentException(
              "'"
                  + command.name()
                  + "' takes no "
                  + argument
                  + (command.flags().isEmpty()
                      ? " — it takes no options at all."
                      : ". It takes: " + String.join(", ", new TreeSet<>(command.flags())) + "."));
        }
        if (at + 1 >= rest.length) {
          throw new IllegalArgumentException(argument + " needs a value.");
        }
        String value = rest[++at];
        if (value.startsWith("--")) {
          // A missing argument, not a value. Without this, '--project
          // --limit 5' files under a project literally called
          // "--limit", which is two wrong things to notice from the
          // output.
          throw new IllegalArgumentException(
              argument
                  + " needs a value and was given"
                  + " the option "
                  + value
                  + "; a value starting with -- is a missing"
                  + " argument.");
        }
        flags.put(argument, value);
      }
      if (words.size() < command.leastWords()) {
        throw new IllegalArgumentException(
            "'"
                + command.name()
                + "' needs "
                + (command.leastWords() == 1 ? "one argument" : command.leastWords() + " arguments")
                + " and was given "
                + (words.isEmpty() ? "none" : String.valueOf(words.size()))
                + ".");
      }
      if (words.size() > command.mostWords()) {
        throw new IllegalArgumentException(
            "'"
                + command.name()
                + "' takes "
                + (command.mostWords() == 0 ? "no arguments" : command.mostWords() + " at most")
                + ", and this line gives "
                + words.size()
                + ": "
                + String.join(" ", words));
      }
      return new Line(List.copyOf(words), Map.copyOf(flags), command);
    }

    String word(int at) {
      return words.get(at);
    }

    String flag(String name) {
      return flags.get(name);
    }

    String required(String name) {
      String value = flags.get(name);
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("'" + command.name() + "' needs " + name + ".");
      }
      return value;
    }

    /**
     * The {@code --project} flag: the tier this command is about.
     *
     * <p>A blank one is refused rather than read as global, which is the line {@code MemoryTools}
     * draws on the other surface and for the same reason: an empty value is what an unset shell
     * variable expands to, and reading it as "everywhere" would file a project's memory into the
     * tier every project reads.
     */
    String project() {
      String project = flags.get("--project");
      if (project == null) {
        return null;
      }
      if (project.isBlank()) {
        throw new IllegalArgumentException(
            "--project was given nothing. Leave it out"
                + " entirely for the memories that hold everywhere — an empty project is"
                + " not the global tier.");
      }
      return project;
    }

    Integer number(String name) {
      String value = flags.get(name);
      if (value == null) {
        return null;
      }
      try {
        return Integer.valueOf(value.trim());
      } catch (NumberFormatException notANumber) {
        throw new IllegalArgumentException(
            name + " wants a whole number and was given '" + value + "'.");
      }
    }
  }
}
