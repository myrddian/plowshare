package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The provider a run gets for a project no live session is rooting: no root, and one sentence
 * saying why.
 *
 * <h2>Why an absence is a provider rather than a missing one</h2>
 *
 * <p>Because the alternative is silence at the exact moment a person needs a sentence. A run in a
 * project nobody is running a client for would otherwise get the server's own disk alone, and every
 * path the model tried would come back "outside every root this job can reach" — a refusal that is
 * true, that names nothing anybody can act on, and that reads as <em>the file is not there</em>.
 * The design spec's §6 asks for the opposite in as many words: the refusal <em>"should name the
 * project and say no presence is serving it, rather than reporting a missing file"</em>.
 *
 * <p>This is the shape that gets the sentence out through machinery that already exists rather than
 * through a new branch in every tool. {@link ProviderRouter#absence} asks a rootless provider to
 * search and renders the refusal it gives; {@code FileTools.Roots} and {@code FileTools.Glob} both
 * go through it, and {@link ProviderRouter}'s own {@code outOfScope} puts it in the account it
 * assembles for a path nothing covers. So one class makes three tools say the useful thing, and
 * none of them learns what a presence is.
 *
 * <h2>It is not the empty-capability case, and must not be read as one</h2>
 *
 * <p>{@code RunProviders.forRun} says a run with no session <b>"has a smaller set, not an empty
 * capability"</b>, and this is that rule extended to a project rooted nowhere. The run still has
 * the server's own disk; a project that lives on the server is served exactly as before, and this
 * provider sits beside {@link LocalProvider} rather than replacing it.
 *
 * <h2>Except when the server does not hold the workspace either, and then it does replace it</h2>
 *
 * <p><b>This is the case presence made ordinary and it was measured pre-empting everything
 * above.</b> A project whose files are on somebody's laptop has no directory of that name on the
 * server, so a {@code projects} row pointed at one names a path this machine does not have — and
 * {@link LocalProvider#roots()} then <em>raises</em> rather than answering. This provider covers no
 * path, so {@link ProviderRouter}'s rule makes that unavailability fatal, the run ends {@code
 * UNAVAILABLE}, and the sentence this class exists to say is never read.
 *
 * <p>So {@code AgentsConfig.runProviders} asks, once, whether this server is the machine that roots
 * the project, and when it is not it builds this through {@link #AbsentPresence(String, String)}
 * <em>instead of</em> a local provider that could only raise. The second argument is the server's
 * own account of why it cannot serve the project, carried into the refusal alongside the absence
 * because the two are one operator's problem and the second half says what, beyond starting a
 * client, is also true of the deployment.
 *
 * <p><b>Which of the two states produced it used to be untellable from the row</b> — a project that
 * lives on a laptop and one that lived here until somebody deleted the directory were the same
 * workspace this server cannot reach — so the account named both possible fixes because neither
 * could be ruled out. V15 stores the discriminator: {@code projects.machine} names the box a
 * project's files are on, written by the client that holds them, and {@code
 * AgentsConfig.serverCannotServe} reads it instead of probing a filesystem. So the account is now
 * one true statement with one fix in it, and this class no longer completes it — see {@link
 * #capitalised}, which is where the sentence that had to move is recorded.
 *
 * <p><b>It also cannot make a run fail on its own.</b> {@link ProviderRouter}'s rule — a provider
 * that could not be asked is fatal only when no provider that could be asked covers the path —
 * holds here by construction: this one advertises no root, so it never covers a path, so it never
 * competes and never raises {@link AmbiguousPathException}. Everything it throws is a {@link
 * WorkspaceRefusedException}, which is the correctable kind: it is a fact about the deployment that
 * a person can change by starting a client, and never an outage of something that was there a
 * moment ago.
 */
public final class AbsentPresence implements FileProvider {

  /**
   * What this provider is called in an account that names several.
   *
   * <p>{@code local} is the server's own disk and {@code remote} is a machine that answered; this
   * is the third thing an account can say, and it says <em>there should have been a machine
   * here</em>. Naming it after what is missing rather than after this class is what makes the
   * account line read as a sentence.
   */
  private static final String NAME = "presence";

  private final String project;

  private final String serverAccount;

  /**
   * The ordinary one: nothing roots the project, and the server serves it as far as its own row
   * allows.
   *
   * @param project the friendly name of the project nothing is rooting — what a person typed and
   *     what {@code Home} carries. Never a canonical name: a canonical name is composed by the
   *     presence that declares it, and the whole content of this class is that there is no such
   *     presence
   */
  public AbsentPresence(String project) {
    this(project, null);
  }

  /**
   * The one for a project no machine at all is serving.
   *
   * @param serverAccount the server's own reason for not being able to serve this project, <b>as a
   *     clause and complete with whatever fix it has</b> — carried rather than rewritten, because
   *     only the caller knows which of the two states it is looking at and therefore which fix is
   *     true. {@code null} for the ordinary case, where the server is serving the project perfectly
   *     well and only the presence is missing
   */
  public AbsentPresence(String project, String serverAccount) {
    this.project = Objects.requireNonNull(project, "project");
    this.serverAccount = serverAccount;
  }

  @Override
  public String name() {
    return NAME;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Empty, and never a refusal. {@code roots()} is the one method where empty is a real answer —
   * it is what {@code file_roots} asks — and {@link ProviderRouter#absence} is the door the
   * sentence comes out of, exactly as it is for a {@link RemoteProvider} whose agent holds no
   * grant.
   */
  @Override
  public List<Path> roots() {
    return List.of();
  }

  @Override
  public Span read(Path path, Window window) {
    throw refusal();
  }

  @Override
  public Span stat(Path path) {
    throw refusal();
  }

  @Override
  public List<Path> glob(String pattern) {
    throw refusal();
  }

  @Override
  public Found grep(Needle needle, Path path) {
    throw refusal();
  }

  @Override
  public Changed write(Path path, String content) {
    throw refusal();
  }

  @Override
  public Changed create(Path path, String content) {
    throw refusal();
  }

  @Override
  public Changed edit(Path path, String old, String replacement) {
    throw refusal();
  }

  @Override
  public Changed delete(Path path) {
    throw refusal();
  }

  @Override
  public Changed move(Path from, Path to) {
    throw refusal();
  }

  @Override
  public CommandRunner.Outcome run(
      Path cwd,
      List<String> argv,
      EnvironmentFile.Side side,
      Duration timeout,
      BooleanSupplier cancelled) {
    throw refusal();
  }

  /**
   * The one sentence, said the same way whatever was asked.
   *
   * <p><b>The path is deliberately not in it.</b> Naming the file would invite exactly the reading
   * this class exists to prevent — that this particular path is the problem — and send a model on
   * to try another one. Nothing was looked for, on any path, because there is nowhere to look; that
   * is a fact about the project and it is the same fact for every argument.
   *
   * <p>It ends with what to do about it, because a refusal an operator can act on is worth more
   * than one that is merely accurate: a project is reached through the client that holds it, and
   * starting one is the fix.
   */
  /**
   * The account as a sentence of its own.
   *
   * <p><b>The fix used to be written here and it had to move.</b> This class ended the server's
   * account with "either that directory comes back, or the project is pointed at one this server
   * has", which is right for a project whose server-side directory has gone and <em>wrong</em> for
   * one whose files are on somebody's laptop — the design spec's §13.4 named exactly that sentence
   * as advice that cannot work. Since V15 the caller knows which of the two it is looking at, so
   * the whole account including its fix is composed there and this class carries it rather than
   * completing it.
   *
   * <p>What is left here is the joining: the account arrives as a clause, in the caller's own
   * voice, and this makes it a sentence.
   */
  private static String capitalised(String account) {
    return Character.toUpperCase(account.charAt(0)) + account.substring(1);
  }

  private WorkspaceRefusedException refusal() {
    // The server's own account comes SECOND and the presence's first, which
    // is the ranking this whole class is: what is missing is a machine, and
    // the server's inability to stand in for one is the corroborating
    // detail rather than the diagnosis. An operator reading the first
    // sentence already knows what to do; the second tells them the other
    // thing they could do instead.
    return new WorkspaceRefusedException(
        "no presence is serving the project '"
            + project
            + "': no live session has declared that it roots it, so there is no machine to"
            + " look on. This is not a missing file — nothing was looked for, on any path."
            + (serverAccount == null ? "" : " " + capitalised(serverAccount) + ".")
            + " A project is served by the client that holds it, so this is fixed by"
            + " starting a Plowshare client on the machine where '"
            + project
            + "' lives"
            + " and having it root the project there.");
  }
}
