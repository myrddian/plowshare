package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.FileResult;
import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.images.ImageStore;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link FileProvider} whose disk belongs to the machine that submitted the job.
 *
 * <p>The other implementation of the seam, and the reason the seam exists. Everything above this
 * line — the file tools, {@link ProviderRouter} — talks to {@link FileProvider} and never to a
 * socket, so nothing there has to know that half its answers were assembled on somebody's laptop.
 *
 * <h2>Where the containment is, and where it is not</h2>
 *
 * <p><b>There is no containment check in this class at all, and a reader looking for one should
 * stop here rather than keep reading.</b> {@code FileProvider} says an implementation refuses a
 * path outside every root, and every other sentence in this system about "the provider refuses
 * out-of-scope" is about {@link LocalProvider}. <b>The doubling for a remote path is the router and
 * the client, not this and the client.</b> {@link ProviderRouter#providerFor} narrows against roots
 * it fetched over the wire, and {@code ClientEnforcer} refuses against the workspace its session
 * holds at the instant the file would be opened; this class is the wire between them and adds
 * nothing of its own.
 *
 * <p><b>Not here, and it could not be.</b> {@link LocalProvider} builds a {@code FileAccess} per
 * call and answers <em>where</em> itself, because the disk it is about is under it. This class
 * cannot: the tree is on another machine, and a path resolved against this server's filesystem is a
 * claim about a different set of files that happen to share a name — the same argument {@link
 * AmbiguousPathException} makes about the routing layer.
 *
 * <p>So the whole of <em>where</em> is the client's, checked in the process that actually opens the
 * file, against the workspace that session holds <b>now</b>. The client runs the same {@code
 * FileAccess} this server does — it moved into {@code plowshare-protocol} for exactly this, and
 * that class's javadoc argues why a second implementation of it was the one thing that could not be
 * allowed.
 *
 * <p>What is left here is the half only the server knows: the <b>grants the agent's definition
 * declared</b>. A client has no idea which agent is asking or what its {@code scopes:} say, and
 * telling it would be handing the enforcement of an agent's own declaration to the party the
 * declaration is about.
 *
 * <p>That splits the doubled enforcement along the line of who knows what, rather than duplicating
 * one check twice — which is what the spec means by the layers disagreeing being the design
 * working: the server may narrow, the client refuses regardless of what was asked, and a workspace
 * that moved between the two is a refusal the model can act on rather than a race to fix.
 *
 * <h2>The write mode is checked before the request is sent, and {@link LocalProvider} checks it
 * after</h2>
 *
 * <p>A deliberate divergence with a cost, stated rather than smoothed over. {@code
 * LocalProvider.write} refuses an out-of-root path <em>before</em> it mentions the grant, because
 * "the workspace is read-only to you" said about a path that was never in the workspace points a
 * reader at the agent's scopes when the thing to fix is the path — Excalibur recorded hearing
 * exactly that.
 *
 * <p>That order is not available over a wire: the containment check is the client's, and asking the
 * client first <em>is</em> the write. So the mode goes first, and the sentence is written to be
 * true whichever way the path would have gone — it says this agent holds no write grant, which is a
 * fact about the definition and not a claim about the path. The cost is that a read-only agent that
 * also named a bad path is told about the grant first and the path second, one turn later. {@code
 * a_read_only_agents_write_never_leaves_the_server} is what holds the order, and it holds it by
 * measuring that nothing was sent — a check after the send would have already written the file.
 *
 * <h2>Source clients stream bytes; the server cuts the text window</h2>
 *
 * <p>A source-capable client serves fenced metadata and bounded byte ranges. {@link FileContents}
 * converts and caches those bytes on the server, and this provider cuts the resulting lines with
 * {@link Window#cut}. Every access asks for fresh metadata before a cache hit can serve text.
 * Images use the server's home-scoped store and retain their one-line reference.
 *
 * <p>Older clients retain the text protocol: a read carries a window and comes back as the {@link
 * Span} the client cut. Both paths bound individual WS frames and preserve the same text
 * coordinates.
 *
 * <h2>Every answer is checked for being an answer at all</h2>
 *
 * <p>Four shapes {@link LocalProvider} has no branch for, because they are only reachable from the
 * far side of a wire: an ok reply carrying nothing where a result was due, a refusal with no
 * sentence in it, a reply whose id belongs to a different request, and <b>a list whose entries are
 * not paths this server can name</b>. The first would become the confident empty answer, the second
 * a blank tool result, the third is the failure this whole slice is about — reading the wrong file
 * and never knowing — and the fourth would leave this seam raising an unclassified {@code
 * RuntimeException}, which is neither of the two kinds {@link FileProvider} says it fails in.
 */
public final class RemoteProvider implements FileProvider {

  /**
   * What this provider is called in a refusal that names two machines.
   *
   * <p>Matches what {@code LocalProvider} does with {@code "local"}, and the pair is the point:
   * {@code ProviderRouter}'s ambiguity refusal has to say which two claimed the path, and "the
   * remote one" versus "the server's own" is the distinction an operator acts on.
   *
   * <p>Public for the reason {@link LocalProvider#NAME} gives: it names a kind, and the guard that
   * keeps LLM pool names off both words reads the constants rather than repeating the strings.
   */
  public static final String NAME = "remote";

  private static final Logger log = LoggerFactory.getLogger(RemoteProvider.class);

  /** The kind a change that was made answers with, by the op that made it. */
  private static final Map<String, String> MADE =
      Map.of(
          FileRequest.WRITE,
          FileResult.WRITTEN,
          FileRequest.EDIT,
          FileResult.EDITED,
          FileRequest.DELETE,
          FileResult.DELETED,
          FileRequest.MOVE,
          FileResult.MOVED);

  private final SessionChannel channel;
  private final String session;
  private final boolean anyGrant;
  private final boolean writable;
  private final Home home;
  private final ImageStore images;
  private final FileContents contents;

  /** How many file actions this session answered in an old client's words. */
  private final AtomicInteger oldClientReplies = new AtomicInteger();

  /**
   * @param channel how to reach the session and wait for it
   * @param session the id the client registered under. Fixed when the job started, exactly as
   *     {@link LocalProvider}'s {@code Home} is: a provider reaches one machine's files for the
   *     same reason a job reaches one project's
   * @param grants what the agent's definition declared. Empty is an ordinary answer and means this
   *     agent reaches no file at all
   */
  public RemoteProvider(SessionChannel channel, String session, List<Grant> grants) {
    this(channel, session, grants, Home.global(), ImageStore.NONE, FileContents.SHARED);
  }

  public RemoteProvider(
      SessionChannel channel, String session, List<Grant> grants, Home home, ImageStore images) {
    this(channel, session, grants, home, images, FileContents.SHARED);
  }

  public RemoteProvider(
      SessionChannel channel,
      String session,
      List<Grant> grants,
      Home home,
      ImageStore images,
      FileContents contents) {
    this.channel = Objects.requireNonNull(channel, "channel");
    this.session = Objects.requireNonNull(session, "session");
    Objects.requireNonNull(grants, "grants");
    // No comparison against Scope.WORKSPACE, for the reason LocalProvider's
    // constructor gives at length: Scope has one value, so a scope check
    // here is a line no mutant could kill until a second one exists.
    this.anyGrant = !grants.isEmpty();
    // Grant.allows is the single owner of "write implies read".
    this.writable = grants.stream().anyMatch(grant -> grant.allows(Mode.WRITE));
    this.home = Objects.requireNonNull(home);
    this.images = Objects.requireNonNull(images);
    this.contents = Objects.requireNonNull(contents);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public List<Path> roots() {
    if (!anyGrant) {
      // Before the wire, and empty rather than a refusal: roots() is the
      // one method where empty is a real answer, because "what can I see?"
      // is the question file_roots asks. The sentence for this state is
      // reached through glob, which is how ProviderRouter.absence gets at
      // it without FileProvider needing a method for it.
      return List.of();
    }
    return paths(FileRequest.ROOTS, ask(FileRequest.roots(id())));
  }

  @Override
  public String identity() {
    return NAME + ":" + session;
  }

  @Override
  public io.aeyer.plowshare.protocol.FileSource fingerprint(Path path) {
    refuseIfNoGrant();
    if (!channel.sources(session))
      throw new WorkspaceRefusedException("this session cannot serve raw code snapshots");
    var metadata = ask(FileRequest.source(id(), path.toString(), null, null)).source();
    if (metadata == null || metadata.data() != null)
      throw brokenAnswer(FileRequest.SOURCE, "missing source metadata");
    return metadata;
  }

  @Override
  public byte[] snapshot(Path path, io.aeyer.plowshare.protocol.FileSource metadata, int maxBytes) {
    refuseIfNoGrant();
    byte[] bytes = FileContents.transfer(path.toString(), metadata, maxBytes, this::ask);
    if (!metadata.equals(fingerprint(path)))
      throw new WorkspaceUnavailableException("code source changed during snapshot");
    return bytes;
  }

  /**
   * {@inheritDoc}
   *
   * <p>A picture the client named comes back as its facts and no lines, and the one line a reader
   * is handed is worded here and cut with the same window a file's lines would be.
   */
  @Override
  public Span read(Path path, Window window) {
    refuseIfNoGrant();
    if (channel.sources(session)) {
      try {
        return window.cut(source(path, FileRequest.READ));
      } catch (Window.LineTooWide wide) {
        throw new FileRefusedException(
            FileResult.lineTooWide(
                FileRequest.READ,
                path.toString(),
                wide.offset(),
                wide.bytes(),
                Window.MAX_WINDOW_BYTES),
            true);
      }
    }
    FileReply reply = ask(FileRequest.read(id(), path.toString(), window));
    List<String> named = named(reply);
    return named != null ? window.cut(named) : span(FileRequest.READ, reply);
  }

  @Override
  public Span stat(Path path) {
    refuseIfNoGrant();
    if (channel.sources(session)) {
      int total = source(path, FileRequest.STAT).size();
      return new Span(List.of(), 0, total, total > 0, total > 0 ? Span.LINES : Span.END);
    }
    FileReply reply = ask(FileRequest.stat(id(), path.toString()));
    List<String> named = named(reply);
    if (named != null) {
      // What a read of it would carry, counted as LocalProvider.stat counts.
      int total = named.size();
      return new Span(List.of(), 0, total, total > 0, total > 0 ? Span.LINES : Span.END);
    }
    return span(FileRequest.STAT, reply);
  }

  /**
   * The line a picture's read hands over, when the client answered with the id it named it with;
   * null for every other ok answer. An ok reply whose facts are a refusal's is refused, as {@link
   * #changed} refuses one: the facts are the specific half.
   */
  private static List<String> named(FileReply reply) {
    FileResult facts = reply.result();
    if (facts == null) {
      return null;
    }
    if (FileResult.NAMED.equals(facts.kind())) {
      return List.of(FileWords.named(facts, true));
    }
    throw new FileRefusedException(facts, true);
  }

  @Override
  public List<Path> glob(String pattern) {
    refuseIfNoGrant();
    return paths(FileRequest.GLOB, ask(FileRequest.glob(id(), pattern)));
  }

  /**
   * {@inheritDoc}
   *
   * <p>A named source file is converted and matched on the server. A directory or omitted path
   * retains the client's text-only walk and matching, as do older clients. Only a structured
   * directory refusal selects the walk; access refusals and transfer failures propagate unchanged.
   *
   * <p>The null path travels as a null path. This class cannot expand it — <b>the roots it would
   * expand against are the client's, at the instant the client looks</b>, and a server that fetched
   * them and then sent a file list would be routing on a snapshot one round trip old. That is the
   * same reason there is no containment check anywhere in this class.
   */
  @Override
  public Found grep(Needle needle, Path path) {
    refuseIfNoGrant();
    if (path != null && channel.sources(session)) {
      try {
        List<Found.Match> into = new ArrayList<>();
        boolean capped = needle.find(path.toString(), source(path, FileRequest.GREP), into);
        return Found.of(into, capped);
      } catch (FileRefusedException refused) {
        if (!FileResult.DIRECTORY.equals(refused.facts().reason())) throw refused;
      }
    }
    FileReply reply = ask(FileRequest.grep(id(), path == null ? null : path.toString(), needle));
    List<String> named = named(reply);
    if (named != null && path != null) {
      // A picture: the client names it and this words it, so the line is
      // searched here, by the same Needle, as the client would have.
      List<Found.Match> into = new ArrayList<>();
      boolean capped = needle.find(path.toString(), named, into);
      return Found.of(into, capped);
    }
    return found(reply);
  }

  @Override
  public Changed write(Path path, String content) {
    refuseIfReadOnly(path, "written");
    return changed(FileRequest.write(id(), path.toString(), content));
  }

  @Override
  public Changed create(Path path, String content) {
    refuseIfReadOnly(path, "written");
    return changed(FileRequest.create(id(), path.toString(), content));
  }

  /**
   * {@inheritDoc} The client applies the replacement and reports where the new text is; nothing is
   * read here, and nothing is worded here either — {@link FileWords} does that above this seam.
   */
  @Override
  public Changed edit(Path path, String old, String replacement) {
    refuseIfReadOnly(path, "edited");
    return changed(FileRequest.edit(id(), path.toString(), old, replacement));
  }

  @Override
  public Changed delete(Path path) {
    refuseIfReadOnly(path, "deleted");
    return changed(FileRequest.delete(id(), path.toString()));
  }

  @Override
  public Changed move(Path from, Path to) {
    refuseIfReadOnly(from, "moved");
    return changed(FileRequest.move(id(), from.toString(), to.toString()));
  }

  /**
   * A change's answer: the client's facts, or — from a client built before facts — whatever words
   * it sent, passed through and recorded.
   *
   * <p>A refused change never reaches here: {@link #checked} throws it, as facts worded by {@link
   * FileWords} or as the old client's sentence.
   */
  private Changed changed(FileRequest request) {
    FileReply reply = ask(request);
    if (reply.result() != null && !reply.result().made()) {
      // An ok outcome with a refusal's facts: the facts are the specific
      // half, and a refusal read as a change made is the wrong direction.
      throw new FileRefusedException(reply.result(), true);
    }
    if (reply.result() != null) {
      // Made, and made as the op that was asked: a delete answered
      // "written" is not a delete this server may report as done.
      if (!MADE.get(request.op()).equals(reply.result().kind())) {
        throw brokenAnswer(
            request.op(),
            "answered that it made a change of another" + " kind (" + reply.result().kind() + ")");
      }
      return Changed.of(reply.result());
    }
    heardFromAnOldClient(request.op());
    // Only an edit ever carried words on an ok reply: the view of the
    // edited lines, or nothing from a client built before views.
    return Changed.fromOldClient(FileRequest.EDIT.equals(request.op()) ? reply.sentence() : null);
  }

  /**
   * A client built before facts answered a change in its own words. They are passed through as they
   * always were — a person mid-upgrade keeps working — and this says so once per session, where an
   * operator looks, so the words a model read are traceable to the client that wrote them.
   */
  private void heardFromAnOldClient(String op) {
    if (oldClientReplies.getAndIncrement() == 0) {
      log.warn(
          "session '{}' answered a file {} in its own words and no facts: a client"
              + " built before the server words file actions. Its words are passed"
              + " through; rebuild that client.",
          session,
          op);
    }
  }

  /** How many file actions this session has answered as an old client. For the tests. */
  int oldClientReplies() {
    return oldClientReplies.get();
  }

  /** {@inheritDoc} */
  @Override
  public CommandRunner.Outcome run(
      Path cwd,
      List<String> argv,
      EnvironmentFile.Side side,
      Duration timeout,
      BooleanSupplier cancelled) {
    return run(cwd, argv, side, timeout, null, cancelled);
  }

  /**
   * {@inheritDoc}
   *
   * <p><b>Waits as long as the command may run, plus {@link #RUN_MARGIN}</b>, and not the channel's
   * own deadline: a four-minute build is not a client that went quiet. The wait happens on a
   * virtual thread so this one can watch {@code cancelled}; a cancel sends {@link
   * FileRequest#CANCEL} and then keeps waiting, because the client answers the run itself once it
   * has killed it. {@code stdin} rides along in the same {@link FileRequest#RUN} frame.
   */
  @Override
  public CommandRunner.Outcome run(
      Path cwd,
      List<String> argv,
      EnvironmentFile.Side side,
      Duration timeout,
      String stdin,
      BooleanSupplier cancelled) {
    refuseIfReadOnly(cwd, "run in");
    // Refused here, before the frame is built, not left to the client: input past the bound
    // is what would put a frame on the wire the channel cannot take (CommandRunner.
    // MAX_STDIN_BYTES), and the client would refuse it anyway, only later.
    String tooMuchInput = CommandRunner.stdinRefusal(stdin);
    if (tooMuchInput != null) {
      throw new WorkspaceRefusedException(tooMuchInput);
    }
    String id = id();
    FileRequest request =
        FileRequest.run(
            id,
            cwd.toString(),
            argv,
            side.env(),
            side.inherit(),
            timeout.toMillis(),
            side.outputBytes(),
            side.shells(),
            stdin);
    CompletableFuture<FileReply> answer =
        CompletableFuture.supplyAsync(
            () -> channel.ask(session, request, timeout.plus(RUN_MARGIN)),
            runnable -> Thread.ofVirtual().start(runnable));
    boolean cancelSent = false;
    while (true) {
      try {
        FileReply reply = answer.get(250, TimeUnit.MILLISECONDS);
        checked(request, reply);
        CommandRunner.Outcome outcome = reply.commandOutcome();
        if (outcome == null) {
          throw brokenAnswer(
              FileRequest.RUN,
              "answered without the outcome of the"
                  + " command, which a client that does not know how to run one would do");
        }
        return outcome;
      } catch (TimeoutException stillRunning) {
        if (!cancelSent && cancelled.getAsBoolean()) {
          cancelSent = true;
          try {
            channel.ask(session, FileRequest.cancel(id(), id));
          } catch (RuntimeException unsent) {
            // The run's own answer, or its deadline, still ends this wait.
          }
        }
      } catch (InterruptedException stopped) {
        Thread.currentThread().interrupt();
        try {
          channel.ask(session, FileRequest.cancel(id(), id));
        } catch (RuntimeException unsent) {
          // Interrupted is the run ending; the client's command is best-effort killed.
        }
        throw new WorkspaceUnavailableException(
            "waiting for the command on session '" + session + "' was interrupted", stopped);
      } catch (ExecutionException failed) {
        if (failed.getCause() instanceof RuntimeException thrown) {
          throw thrown;
        }
        throw new WorkspaceUnavailableException(
            "the command on session '" + session + "' failed: " + failed.getCause(), failed);
      }
    }
  }

  /** How much longer than its own timeout a run is waited for: the kill, and the reply. */
  static final Duration RUN_MARGIN = Duration.ofSeconds(30);

  /**
   * Every change to a file is refused here, BEFORE the send, when the grant is read-only. The class
   * javadoc argues the order and what it costs; the short of it is that asking the client to check
   * the path first would be the change.
   */
  private void refuseIfReadOnly(Path path, String done) {
    refuseIfNoGrant();
    if (!writable) {
      throw new WorkspaceRefusedException(
          "the workspace is read-only to this agent, so "
              + path
              + " cannot be "
              + done
              + "; its definition declares 'scopes:"
              + " [workspace:read]'");
    }
  }

  /**
   * One exchange, with the answer checked for being one.
   *
   * <p>{@link WorkspaceUnavailableException} out of {@link SessionChannel} is passed through
   * untouched: the reason travels with the ending, and a second sentence invented here would leave
   * an operator reading about a provider when the thing that broke is a session.
   *
   * <p><b>Untouched is now load-bearing for the type as well as the words.</b> The channel raises
   * {@link SessionGoneException} when the session is known to have gone, and {@code JobRuntime}
   * reads that subtype to reach {@code SESSION_GONE}. A {@code catch} here that rebuilt the
   * exception would downgrade the ending in silence, with every test that asserts the supertype
   * still green — {@code the_type_survives_the_router_and_the_tool_that_a_job_calls} is what would
   * fail.
   */
  private FileReply ask(FileRequest request) {
    return checked(request, channel.ask(session, request));
  }

  private List<String> source(Path path, String op) {
    return contents.remote(path.toString(), op, home, images, this::ask);
  }

  /** {@link #ask}'s checks on a reply that arrived some other way — a run's. */
  private FileReply checked(FileRequest request, FileReply reply) {
    if (!request.id().equals(reply.id())) {
      // The channel correlates, and this checks it anyway. It is the one
      // place a bug could hand a job another job's file with everything
      // downstream looking perfectly healthy, which is the failure the
      // whole slice is written against — so it gets a second, independent
      // check for the same reason the client re-checks containment.
      //
      // The stray answer is deliberately not quoted into the message: the
      // one thing known about it is that it belongs to somebody else, and
      // a file from another job's workspace has no business in this job's
      // log.
      throw brokenAnswer(request.op(), "answered a different request");
    }
    if (FileReply.REFUSED.equals(reply.outcome()) && reply.result() != null) {
      // Refused, as facts: worded here, by the one renderer, with the
      // file placed on the client's machine.
      throw new FileRefusedException(reply.result(), true);
    }
    if (FileReply.UNAVAILABLE.equals(reply.outcome()) && reply.result() != null) {
      throw new WorkspaceUnavailableException(FileWords.unavailable(reply.result()));
    }
    if (FileReply.REFUSED.equals(reply.outcome())) {
      // A run's own consent and bounds are the one refusal a current
      // client still words; every other in words is an old client's.
      if (!FileRequest.RUN.equals(request.op())) {
        heardFromAnOldClient(request.op());
      }
      // The client's own sentence, unchanged. It is the only party that
      // knows which of its states holds — no workspace set, a workspace
      // that moved, a file that is not text — and FileTools passes a
      // refusal through for the same reason.
      //
      // A refusal with no sentence in it is only reachable from the far
      // side of a wire. A WorkspaceRefusedException carrying a null
      // message becomes a blank tool result, which AgentTool forbids and
      // which reads to a model as a tool that does not work.
      throw new WorkspaceRefusedException(
          reply.sentence() == null ? NAME + " refused this and gave no reason" : reply.sentence());
    }
    if (FileReply.UNAVAILABLE.equals(reply.outcome())) {
      heardFromAnOldClient(request.op());
    }
    if (!FileReply.OK.equals(reply.outcome())) {
      // UNAVAILABLE, and anything this build does not recognise. THE
      // DIRECTION IS THE POINT: a word from a later client read as success
      // is a wrong answer, and read as an outage it is a run that stops
      // saying so. FileReply's javadoc owns that argument.
      //
      // The client's disk, not the client: its workspace was set and the
      // directory is gone. `LocalProvider` draws the same line between a
      // workspace nobody ever set and one that vanished under a running
      // job.
      throw new WorkspaceUnavailableException(
          reply.sentence() == null
              ? "the session '" + session + "' could not answer and gave no reason"
              : reply.sentence());
    }
    return reply;
  }

  /**
   * The window a read or a stat came back with, or the refusal to invent one.
   *
   * <p><b>Nothing is cut here and nothing is counted here.</b> The window went out in the frame and
   * the client cut with {@link Window#cut}; arithmetic on this side would be a second range
   * computed from the same request, which is the drift {@link FileProvider#read} says the shared
   * window exists to prevent. So this method is a null check and a return.
   *
   * <p>Null is the same far-side shape {@link #paths} guards against, arriving through the other
   * field: an ok reply that carries no result. It is what a client built before windows existed
   * answers a read with, and reading it as an empty span would hand a model a file that appears to
   * have nothing in it.
   */
  /**
   * The matches a search came back with, or the refusal to read an absent answer as an empty one.
   *
   * <p>{@link #span}'s guard through the other field, and the harm is the one {@link #paths} names:
   * a client built before this op existed answers a search with an ok reply and nothing on it, and
   * rendering that as "no matches" is Excalibur's fifteen wasted turns arriving over a socket —
   * with the model this time being told, confidently, that the thing it is looking for is nowhere
   * in the workspace.
   *
   * <p>No {@code op} parameter, unlike its two neighbours: only one operation can produce this
   * reply, so a caller-supplied word could only ever be the same word, and the one thing {@code op}
   * bought those methods — telling a missing roots list from a missing glob list — has no analogue
   * here.
   */
  private Found found(FileReply reply) {
    if (reply.found() == null) {
      throw brokenAnswer(
          FileRequest.GREP,
          "answered without the matches it was asked"
              + " for, and an absent result is not an empty one");
    }
    return reply.found();
  }

  private Span span(String op, FileReply reply) {
    if (reply.span() == null) {
      throw brokenAnswer(
          op,
          "answered without the lines it was asked for, and an absent"
              + " window is not an empty one");
    }
    return reply.span();
  }

  /**
   * A list of paths, or the refusal to read an absent one as an empty search.
   *
   * <p>{@link FileProvider} reserves an empty result for <em>searched, and there was nothing</em>.
   * A reply that says ok and carries no list has said nothing at all, and rendering that as {@code
   * (no matches)} is Excalibur's 15-wasted-turn failure arriving over a socket.
   */
  private List<Path> paths(String op, FileReply reply) {
    if (reply.paths() == null) {
      // `op` and not a placeholder. This read "when asked to this" for one
      // commit — a word that means nothing to the operator reading a run's
      // ending, and it threw away the one distinction the caller has and
      // this method does not: whether the missing list was a set of roots
      // or a set of hits. Those send a reader to different halves of a
      // client.
      throw brokenAnswer(
          op, "answered without a list, and an absent list is not" + " an empty one");
    }
    List<Path> paths = new ArrayList<>(reply.paths().size());
    for (String named : reply.paths()) {
      // A FOURTH FAR-SIDE SHAPE, and this one arrives as raw strings across
      // a process boundary. Measured: Path.of("a\0b") raises
      // InvalidPathException and a null element raises
      // NullPointerException, and NEITHER is one of the two kinds this seam
      // is allowed to fail in — both would leave FileProvider as an
      // unclassified RuntimeException, which JobRuntime reports as "the
      // tool failed; you may try something else" about a client that will
      // send the same list every time.
      //
      // ClientEnforcer.permitted has carried the mirror-image guard on the
      // other end of this same wire since it was written. This end had the
      // list and not the guard.
      if (named == null) {
        throw brokenAnswer(
            op, "answered with a list holding nothing where a path" + " should have been");
      }
      try {
        paths.add(Path.of(named));
      } catch (InvalidPathException unusable) {
        throw brokenAnswer(
            op,
            "answered with '"
                + named
                + "', which is not a path this"
                + " server can even name: "
                + unusable.getMessage());
      }
    }
    return List.copyOf(paths);
  }

  /** The session whose machine this reaches — whose own environment file applies. */
  public String session() {
    return session;
  }

  private void refuseIfNoGrant() {
    if (!anyGrant) {
      // Grant.noneDeclared owns the sentence; LocalProvider reaches the
      // same state from the other side and says the same thing.
      throw new WorkspaceRefusedException(Grant.noneDeclared());
    }
  }

  /**
   * A client that answered something that is not an answer.
   *
   * <p>Unavailable and not refused, which is the only reading that is not a guess: nothing can be
   * concluded from a reply this process cannot make sense of, and no turn the model takes will make
   * the client send a different shape. The alternative — a tool result — invites the retry loop
   * against a client that will answer the same way every time.
   */
  private WorkspaceUnavailableException brokenAnswer(String op, String what) {
    return new WorkspaceUnavailableException(
        "the session '"
            + session
            + "' "
            + what
            + " when asked to "
            + op
            + ", so nothing can be concluded from it");
  }

  /**
   * A correlation id no other outstanding request on this session has.
   *
   * <p>Random rather than a counter, because the counter would have to be shared with every other
   * provider on the same session — one per running job — and two jobs minting {@code 7} on one
   * socket is one job reading the other's file. {@code UUID.randomUUID} needs no coordination to
   * say that.
   */
  private static String id() {
    return UUID.randomUUID().toString();
  }
}
