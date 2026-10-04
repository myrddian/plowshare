package io.aeyer.plowshare.protocol;

import java.util.List;
import java.util.Objects;

/**
 * What the machine that owns the files answered.
 *
 * <h2>The two kinds of failure travel, and the third one cannot</h2>
 *
 * <p>{@code FileProvider} says its failures come in exactly two kinds and must not be merged:
 * <b>the caller was wrong</b>, which the model corrects on its next turn, and <b>the disk could not
 * be asked</b>, which ends the run because nothing it does next will help. Both of those are facts
 * a client has and the server does not, so both are on this wire:
 *
 * <ul>
 *   <li>{@link #REFUSED} — the path is outside the workspace this session holds, the file is not
 *       text, no workspace has been set at all. The model reads {@link #sentence} and names
 *       something else;
 *   <li>{@link #UNAVAILABLE} — the workspace this session <em>was</em> set to is no longer on the
 *       disk. {@code LocalProvider} draws the line in the same place, and it is not a fussy one: a
 *       workspace that was never set is somebody's to set, while one that has been deleted under a
 *       running job makes every later answer meaningless. Told apart because {@code file_glob} over
 *       a deleted tree is otherwise a confident {@code (no matches)}.
 * </ul>
 *
 * <p><b>What a client can never say is that it is gone</b>, and the absence of that outcome is the
 * design rather than an omission: a client well enough to send a frame is not absent, and one that
 * is absent sends nothing. So a session having gone is established by the server, from the two
 * facts only the server has — the socket closed, or the deadline passed — and never from a field a
 * wedged client would have had to be well enough to set.
 *
 * <h2>A read's text has one carrier, because two of them could disagree</h2>
 *
 * <p>This frame used to carry the window twice — a {@code text} string beside {@link #span}, the
 * one being the other's lines joined. <b>Two fields carrying one fact can disagree, and the
 * disagreement available here is a file's contents differing from the range that claims to describe
 * them</b>: a reader that trusted the text would show a model lines the numbering said were
 * somewhere else, and neither field would look wrong on its own. One carrier means the question
 * cannot be asked. The lines are the content and {@link Span#offset()} is where they start.
 *
 * <p>It was also the larger half of a frame this slice exists to shrink. Measured on JDK 21, one
 * window cut at {@link Window#MAX_WINDOW_BYTES}: 98 251 bytes of text serialised to a 201 096-byte
 * frame with both fields on it, and to a 101 352-byte frame with only the span.
 *
 * <p><b>The transport buffer is still sized against the frame and not against the window.</b>
 * Halving the payload did not close the gap that caused the bug, only narrowed it: that surviving
 * frame is 101 352 bytes against a 98 304-byte ceiling, because JSON quotes every line, escapes
 * what needs escaping, and puts a comma between them. Sizing a buffer from {@code MAX_WINDOW_BYTES}
 * alone would still leave it under the payload it exists to carry. That buffer is now a chosen
 * number rather than a container default — {@code FileChannelConfig} holds it, sized from a
 * measured worst-case frame and not from this ceiling, and {@code FileChannelTest} pins the two
 * together across the module boundary that let them drift in the first place.
 *
 * <h2>An outcome this server does not recognise is not an answer</h2>
 *
 * <p>{@link #outcome} is a string and not an enum, so that a client built against a later server
 * does not fail to bind at all — the two halves ship separately, which is the same reason both
 * mappers on this wire ignore unknown fields. The reader treats anything it does not recognise as
 * {@link #UNAVAILABLE}: <b>the failure direction has to be the one where nothing is concluded</b>,
 * because the alternative is reading a word this build has never heard of as success.
 *
 * @param id the {@link FileRequest#id()} this answers. An unknown id is dropped, which is what a
 *     reply arriving after its own deadline looks like
 * @param outcome {@link #OK}, {@link #REFUSED} or {@link #UNAVAILABLE}
 * @param sentence why, for the two that are not {@link #OK} — <b>from a current client only for
 *     {@link FileRequest#RUN}'s own refusals</b> (its consent, its shell, its bounds). Every other
 *     op answers with {@link #result}, and the server words it. A client built before that sends a
 *     sentence here instead — on an ok edit, the view of the edited region — and the server passes
 *     it through as it always did, and records that it did
 * @param paths the roots or the hits, for {@link FileRequest#ROOTS} and {@link FileRequest#GLOB}.
 *     <b>Strings and not paths</b>, because they name files on a filesystem this process cannot
 *     see; the server turns them back into {@code Path}s with its own {@code Path.of}, which is
 *     exact while both ends are POSIX and is the one thing on this wire a Windows client would
 *     break
 * @param span the whole of an answered read — the lines, where they start, and whether more remain
 *     — for {@link FileRequest#READ} and {@link FileRequest#STAT}; null for every reply that moved
 *     no text and for one sent by a client built before windows existed. <b>It says what to do
 *     next</b>, which lines alone cannot: a full window and a finished file look identical from the
 *     text, and {@link Span} says why that is the distinction a model gets wrong
 * @param found the whole of an answered search — the matches, and whether the allowance stopped it
 *     — for {@link FileRequest#GREP}; null for every other reply. <b>Its own field rather than a
 *     second meaning for {@link #paths}</b>, though a search does hand back a set of files: a match
 *     is a path and a line number and a line, and flattening it into a list of strings would put
 *     the parsing of that back into whoever reads the frame — which is the
 *     hand-written-JSON-on-both-sides failure this record's own javadoc opens by refusing
 * @param exitCode for {@link FileRequest#RUN}: the command's exit status, or null when it was
 *     killed. The fields below are null on every other reply
 * @param timedOut for a run: whether its deadline killed it
 * @param stdout for a run: the tail of standard output, decoded as UTF-8 with replacement
 * @param stdoutCut for a run: how many bytes of standard output were dropped from the front to keep
 *     that tail
 * @param stderr for a run: the tail of standard error
 * @param stderrCut for a run: bytes of standard error dropped
 * @param millis for a run: how long it took
 * @param source raw source metadata or a bounded byte range for SOURCE; null on ordinary file
 *     replies. Conversion is exclusively server-side on this path.
 * @param result what a {@link FileRequest#WRITE}, {@link FileRequest#EDIT}, {@link
 *     FileRequest#DELETE} or {@link FileRequest#MOVE} did or why it was not done, as facts — on an
 *     ok reply and on a refused one alike; why any other op was refused or could not be answered;
 *     and, on an ok read, stat or search of a picture, the id it was named with ({@link
 *     FileResult#NAMED}). Null on an ok reply that carries its answer in {@link #paths}, {@link
 *     #span} or {@link #found}, on a run's own refusal, and from a client built before it (spec
 *     2026-09-30: the file side reports facts, the server words them)
 */
public record FileReply(
    String id,
    String outcome,
    String sentence,
    List<String> paths,
    Span span,
    Found found,
    Integer exitCode,
    Boolean timedOut,
    String stdout,
    Long stdoutCut,
    String stderr,
    Long stderrCut,
    Long millis,
    FileResult result,
    FileSource source) {

  /** Legacy shape: source streaming is negotiated independently of text reads. */
  public FileReply(
      String id,
      String outcome,
      String sentence,
      List<String> paths,
      Span span,
      Found found,
      Integer exitCode,
      Boolean timedOut,
      String stdout,
      Long stdoutCut,
      String stderr,
      Long stderrCut,
      Long millis,
      FileResult result) {
    this(
        id, outcome, sentence, paths, span, found, exitCode, timedOut, stdout, stdoutCut, stderr,
        stderrCut, millis, result, null);
  }

  public static FileReply source(String id, FileSource source) {
    return new FileReply(
        id,
        OK,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Objects.requireNonNull(source));
  }

  /** The six-argument shape every reply but a run's and a change's is. */
  public FileReply(
      String id, String outcome, String sentence, List<String> paths, Span span, Found found) {
    this(id, outcome, sentence, paths, span, found, null, null, null, null, null, null, null);
  }

  /** The thirteen-argument shape, from before {@link #result} existed. */
  public FileReply(
      String id,
      String outcome,
      String sentence,
      List<String> paths,
      Span span,
      Found found,
      Integer exitCode,
      Boolean timedOut,
      String stdout,
      Long stdoutCut,
      String stderr,
      Long stderrCut,
      Long millis) {
    this(
        id, outcome, sentence, paths, span, found, exitCode, timedOut, stdout, stdoutCut, stderr,
        stderrCut, millis, null);
  }

  /** It was done. */
  public static final String OK = "ok";

  /** The caller was wrong, and can be right on its next turn. */
  public static final String REFUSED = "refused";

  /** The disk could not be asked, so nothing can be concluded. */
  public static final String UNAVAILABLE = "unavailable";

  /**
   * One window of one file, which is the span and nothing beside it.
   *
   * <p><b>The span is required, and there is no way to answer a read without one.</b> A reply
   * carrying text and no range is an unbounded read wearing this change's clothes — it is what
   * every caller wrote before the channel started dying on it, and leaving a shorter call available
   * is how that comes back. Reading one is not the same problem: {@link #span} is nullable on the
   * record because a client built against the older protocol sends exactly that, and refusing to
   * bind its reply would turn a version skew into an outage.
   *
   * <p>The null check stays even though the parameter is now the only thing this method takes. It
   * is not reachable from a call that compiles against this signature by accident, and it is
   * reachable from one that computed its span and got null back — which is a provider bug worth
   * naming here rather than a {@code NullPointerException} thrown out of a serialiser three frames
   * later.
   */
  public static FileReply answered(String id, Span span) {
    return new FileReply(
        id,
        OK,
        null,
        null,
        Objects.requireNonNull(span, "a read's reply says which lines it carried"),
        null);
  }

  /**
   * A list of roots or of hits.
   *
   * <p>Empty means <em>searched, and there was nothing</em> — {@code FileProvider} reserves it for
   * that — so a client with nowhere to look {@link #refused} rather than sending this with an empty
   * list.
   */
  public static FileReply listed(String id, List<String> paths) {
    return new FileReply(id, OK, null, List.copyOf(paths), null, null);
  }

  /**
   * The matches of one search.
   *
   * <p>Required, exactly as {@link #answered}'s span is, and for the reason that method gives: an
   * ok reply with nothing on it becomes {@code (no matches)} at the far end, which is the confident
   * empty answer Excalibur spent fifteen turns inside. Reading a null one is a different question
   * and is a client built before this op existed — {@code RemoteProvider} refuses that rather than
   * reading it as a search that found nothing.
   */
  public static FileReply found(String id, Found found) {
    return new FileReply(
        id,
        OK,
        null,
        null,
        null,
        Objects.requireNonNull(found, "a search's reply says what it matched"));
  }

  /** It was done and there is nothing to hand back — a cancel. */
  public static FileReply done(String id) {
    return new FileReply(id, OK, null, null, null, null);
  }

  /**
   * A change to a file, answered with what it did or why it was not made: ok when {@link
   * FileResult#made()}, refused otherwise.
   *
   * <p><b>No sentence, on either outcome.</b> The server words the result; a client that wrote one
   * as well would be the second author this field exists to retire.
   */
  public static FileReply changed(String id, FileResult result) {
    Objects.requireNonNull(result, "a change's reply says what it did");
    return new FileReply(
        id,
        result.made() ? OK : REFUSED,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        result);
  }

  /**
   * Any op refused, answered with why as facts, which the server words. What every op answers with
   * but {@link FileRequest#RUN}'s own consent and bounds, which are still sentences.
   */
  public static FileReply refused(String id, FileResult result) {
    Objects.requireNonNull(result, "a refusal's reply says why");
    return new FileReply(
        id, REFUSED, null, null, null, null, null, null, null, null, null, null, null, result);
  }

  /**
   * A read, a stat or a search of a picture, answered with the id it was named with ({@link
   * FileResult#NAMED}) and no lines: the server words the line a reader is handed, and cuts or
   * searches it as it would a file's.
   */
  public static FileReply named(String id, FileResult result) {
    Objects.requireNonNull(result, "a named picture's reply says what it was named");
    return new FileReply(
        id, OK, null, null, null, null, null, null, null, null, null, null, null, result);
  }

  /** The disk could not be asked, answered with why as facts. */
  public static FileReply unavailable(String id, FileResult result) {
    Objects.requireNonNull(result, "an outage's reply says why");
    return new FileReply(
        id, UNAVAILABLE, null, null, null, null, null, null, null, null, null, null, null, result);
  }

  /**
   * The client would not, and this is what to tell the model. <b>From a current client only for
   * {@link FileRequest#RUN}'s own refusals</b>; every other op answers with {@link #refused(String,
   * FileResult)}, and a client built before that sends this for all of them.
   */
  public static FileReply refused(String id, String sentence) {
    return new FileReply(id, REFUSED, sentence, null, null, null);
  }

  /**
   * How a command ended. A cancelled one is answered the same way, with no exit code and not timed
   * out.
   */
  public static FileReply ran(String id, CommandRunner.Outcome outcome) {
    return new FileReply(
        id,
        OK,
        null,
        null,
        null,
        null,
        outcome.exitCode(),
        outcome.timedOut(),
        outcome.stdout(),
        outcome.stdoutCut(),
        outcome.stderr(),
        outcome.stderrCut(),
        outcome.millis());
  }

  /**
   * This reply as a command's outcome, or null when it carries none — a client built before {@link
   * FileRequest#RUN}, which a caller must not read as a command that printed nothing.
   */
  public CommandRunner.Outcome commandOutcome() {
    if (millis == null || stdout == null || stderr == null) {
      return null;
    }
    boolean killed = exitCode == null;
    boolean late = timedOut != null && timedOut;
    return new CommandRunner.Outcome(
        exitCode,
        late,
        killed && !late,
        stdout,
        stdoutCut == null ? 0 : stdoutCut,
        stderr,
        stderrCut == null ? 0 : stderrCut,
        millis);
  }

  /**
   * The client would have, and its own disk is not there to be asked — in words, which only a
   * client built before {@link #unavailable(String, FileResult)} sends.
   */
  public static FileReply unavailable(String id, String sentence) {
    return new FileReply(id, UNAVAILABLE, sentence, null, null, null);
  }
}
