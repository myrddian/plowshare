package io.aeyer.plowshare.server.documents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.server.agents.ModelJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What {@code ask_synthesiser} said, split into the prose a reader gets and the attributions a
 * machine checks.
 *
 * <p>Anchor's {@code SynthesiserOutputParser}, lifted out of the orchestrator for the reason its
 * javadoc gives — <i>"the failure modes are all 'what does this specific text input produce'"</i> —
 * and carrying the three it records as observed on a real paper rather than reasoned about:
 *
 * <ol>
 *   <li>the model omits the {@code RESPONSE:} label and simply starts;
 *   <li>the model fences the JSON;
 *   <li>the model echoes the prompt's trailing label and re-emits the prose.
 * </ol>
 *
 * <h2>The third does not port, and its absence is the finding</h2>
 *
 * <p>Anchor's parser terminates the response at {@code GROUNDING:} <em>or</em> at {@code
 * SYNTHESISER OUTPUT:}, whichever comes first, because its prompt file ends with that label and a
 * small model echoes it. <b>An agent body is not a prompt file that ends in a label.</b> {@code
 * ask_synthesiser.md} ends with the output template, the task is a separate message, and there is
 * no trailing label to echo — so porting the marker would be porting a branch nothing can reach,
 * which is a defence that reads as coverage and is not. What is kept is the shape the failure had:
 * the response ends at the first recognised marker after it, and a second marker is one line to add
 * if a body ever grows one.
 *
 * <h2>What the grounding block carries, and why it is not Anchor's</h2>
 *
 * <p>Anchor grounds in arrays of verbatim <em>title strings</em> because it has no stable ids; half
 * its synthesiser prompt and a paren-insensitive scrubber are the cost of that, and {@code
 * scrubSyntheticMarkers} is <b>not ported</b> — with no title arrays there is nothing to scrub, and
 * the synthetic-title hazard stays at the render boundary where {@link StructuralRef} keeps it.
 *
 * <p>What replaces it is a paragraph id and the words the claim rests on. The id is V18's surrogate
 * key, which survives a re-ingest of unchanged text, and the quote is the thing Anchor's design
 * could not check: a title can only be string-matched against another title, while <b>a quote
 * either occurs in the paragraph it names or it does not</b>. {@link Deliberation} is where that
 * check runs; this class only reads what was claimed.
 *
 * <p>Field validation is atomic: an invalid entry makes the whole grounding block unreadable.
 * Retaining only its valid subset would make a partial account of evidence look complete. The prose
 * is still returned, with the existing unreadable-grounding diagnostic; this boundary never replays
 * a model call. Historical omitted lists mean empty, but present fields must meet their declared
 * contracts. Corpus membership and whether a quote actually occurs remain Deliberation's
 * responsibility, after this structural validation.
 */
final class SynthesiserOutput {

  private static final String RESPONSE = "RESPONSE:";
  private static final String GROUNDING = "GROUNDING:";

  /**
   * How much of an unreadable block is quoted back. Anchor's 500, and its arithmetic: enough to see
   * what the model did, short of putting a whole second answer in a sentence somebody reads.
   */
  private static final int MOST_QUOTED = 500;

  /**
   * The shortest thing that can be an attribution.
   *
   * <p><b>Three, and it is a floor on evidence rather than a style rule.</b> The check {@link
   * Deliberation} runs is a substring, so a quotation's whole value is that finding it in the
   * paragraph it names is <em>evidence</em> rather than coincidence. {@code "."} occurs in every
   * paragraph in the corpus and {@code "the"} in most of them; three words is where a quotation
   * starts distinguishing one from another, and it is the shortest thing a claim has ever been
   * written in.
   */
  private static final int FEWEST_WORDS = 3;

  /**
   * Whitespace, including the kinds a PDF leaves behind — {@code \s} is ASCII-only and {@code
   * Deliberation.flattened} owns the argument.
   */
  private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");

  private final String response;
  private final List<Grounded> grounding;
  private final List<Integer> incorporated;
  private final List<Rejection> rejected;
  private final List<Integer> objectionsAddressed;
  private final String unreadable;
  private final List<Refusal> refusals;
  private final String confidence;

  private SynthesiserOutput(
      String response,
      List<Grounded> grounding,
      List<Integer> incorporated,
      List<Rejection> rejected,
      List<Integer> objectionsAddressed,
      String unreadable) {

    this(
        response,
        grounding,
        incorporated,
        rejected,
        objectionsAddressed,
        unreadable,
        List.of(),
        null);
  }

  private SynthesiserOutput(
      String response,
      List<Grounded> grounding,
      List<Integer> incorporated,
      List<Rejection> rejected,
      List<Integer> objectionsAddressed,
      String unreadable,
      List<Refusal> refusals,
      String confidence) {
    this.refusals = List.copyOf(refusals);
    this.confidence = confidence;
    this.response = response;
    this.grounding = List.copyOf(grounding);
    this.incorporated = List.copyOf(incorporated);
    this.rejected = List.copyOf(rejected);
    this.objectionsAddressed = List.copyOf(objectionsAddressed);
    this.unreadable = unreadable;
  }

  /**
   * One claim's attribution, as the model made it.
   *
   * <p><b>Structurally validated.</b> The paragraph may name no row and the quote may appear in no
   * paragraph; this record is what was <em>said</em>, and {@link Deliberation} is what asks the
   * corpus.
   *
   * @param paragraph the id the model copied off a passage
   * @param quote the words it says the claim rests on. Uploaded text — somebody's document quoted
   *     back — and never blank
   */
  record Grounded(UUID paragraph, String quote) {

    Grounded {
      Objects.requireNonNull(paragraph, "paragraph");
      quote = DocumentModelValues.text(quote, "quote");
      if (words(quote) < FEWEST_WORDS)
        throw new IllegalArgumentException("Quote must contain at least three words");
    }
  }

  /**
   * One challenge the answer says it ruled against, and what it ruled from.
   *
   * <p><b>Structurally validated, like {@link Grounded}.</b> The number may name no challenge the
   * critic raised; this record is what was <em>said</em>, and {@link Deliberation} is what holds it
   * against the critic's own list.
   *
   * @param challenge the challenge's number as the synthesiser was shown it, counting from one
   * @param reason why it does not hold. <b>Model prose</b> — it reaches a reader quoted, for {@code
   *     Deliberation}'s reason
   */
  record Rejection(int challenge, String reason) {

    Rejection {
      reason = DocumentModelValues.text(reason, "reason");
      if (challenge < 1) throw new IllegalArgumentException("Challenge number must be positive");
    }
  }

  /**
   * Read one synthesiser answer.
   *
   * @param raw what the model said, or null. Null and empty are the same fact — it said nothing —
   *     and neither throws: this is on the path that ends in a person's answer, and a parse failure
   *     must never be the thing that loses one
   */
  static SynthesiserOutput of(String raw) {
    if (raw == null) {
      return new SynthesiserOutput("", List.of(), List.of(), List.of(), List.of(), null);
    }
    int labelled = raw.indexOf(RESPONSE);
    int from = labelled >= 0 ? labelled + RESPONSE.length() : 0;
    int marker = raw.indexOf(GROUNDING, from);
    int to = marker >= 0 ? marker : raw.length();
    String response = raw.substring(from, to).strip();

    if (marker < 0) {
      return new SynthesiserOutput(response, List.of(), List.of(), List.of(), List.of(), null);
    }
    String block = raw.substring(marker + GROUNDING.length()).strip();
    try {
      JsonNode read = ModelJson.object(block);
      DocumentModelValues.fields(
          read,
          "grounded_in",
          "refusals",
          "confidence",
          "incorporated_critic_challenges",
          "rejected_critic_challenges",
          "objections_addressed");
      List<Integer> incorporated = incorporated(read.path("incorporated_critic_challenges"));
      List<Rejection> rejected = rejections(read.path("rejected_critic_challenges"));
      List<Integer> rulings = new ArrayList<>(incorporated);
      rejected.forEach(value -> rulings.add(value.challenge()));
      DocumentModelValues.distinct(rulings);
      List<Refusal> refusals = new ArrayList<>();
      for (JsonNode value : DocumentModelValues.array(read.path("refusals"))) {
        DocumentModelValues.fields(value, "sub_claim", "reason");
        refusals.add(
            new Refusal(
                DocumentModelValues.text(value.path("sub_claim"), "sub_claim"),
                DocumentModelValues.text(value.path("reason"), "reason")));
      }
      String confidence = null;
      if (read.has("confidence")) {
        confidence = DocumentModelValues.text(read.get("confidence"), "confidence");
        if (!List.of("high", "medium", "low").contains(confidence))
          throw new IllegalArgumentException("Invalid confidence");
      }
      return new SynthesiserOutput(
          response,
          attributions(read.path("grounded_in")),
          incorporated,
          rejected,
          incorporated(read.path("objections_addressed")),
          null,
          refusals,
          confidence);
    } catch (ModelJson.Unreadable | IllegalArgumentException invalidBlock) {
      return new SynthesiserOutput(
          response,
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          invalidBlock.getMessage() + ": " + truncated(block));
    }
  }

  /**
   * The prose the reader gets. Empty for an answer that said nothing, which is a decision the model
   * made and not a failure to parse.
   */
  String response() {
    return response;
  }

  /**
   * What the answer said it took from where, in the order it said it. Structurally validated; see
   * {@link Grounded}.
   */
  List<Grounded> grounding() {
    return grounding;
  }

  /**
   * The challenges the answer says it took up, as it numbered them. Unchecked; {@link Deliberation}
   * holds these against what was raised.
   */
  List<Integer> incorporated() {
    return incorporated;
  }

  /**
   * The challenges the answer says it ruled against, with the reason each gave. Unchecked, and an
   * invalid rejection makes the whole block unreadable.
   */
  List<Rejection> rejected() {
    return rejected;
  }

  /**
   * The review pass's objections the answer says it addressed.
   *
   * <p><b>A field of its own, and that is the whole point of it.</b> The synthesiser is handed two
   * numbered lists — the critic's challenges and the reviewer's objections — and with one pair of
   * fields to report in it put objection numbers into {@code incorporated_critic_challenges}.
   * Measured: one answer claimed three incorporations against a critic that raised none. {@code
   * Deliberation.unruled} resolves those numbers against the critic's list, so the check that
   * catches a synthesiser ignoring its critic was being satisfied by numbers that meant something
   * else.
   *
   * <p>Structurally validated like the rest of this block, and <b>nothing reconciles it yet</b>:
   * the objections are prose from a stage whose output no machine parses, so there is no list to
   * hold these against. It exists so that the challenge fields stop absorbing what does not belong
   * to them.
   */
  List<Integer> objectionsAddressed() {
    return objectionsAddressed;
  }

  /**
   * Why the grounding block could not be read, for an answer that emitted one and got it wrong.
   * Empty both for a block that read and for an answer that emitted none.
   */
  Optional<String> groundingWasUnreadable() {
    return Optional.ofNullable(unreadable);
  }

  /** A refused subclaim is explicit, bounded model prose rather than an ignored property bag. */
  record Refusal(String subClaim, String reason) {
    Refusal {
      subClaim = DocumentModelValues.text(subClaim, "sub_claim");
      reason = DocumentModelValues.text(reason, "reason");
    }
  }

  List<Refusal> refusals() {
    return refusals;
  }

  Optional<String> confidence() {
    return Optional.ofNullable(confidence);
  }

  private static List<Grounded> attributions(JsonNode entries) {
    List<Grounded> attributions = new ArrayList<>();
    for (JsonNode entry : DocumentModelValues.array(entries)) {
      DocumentModelValues.fields(entry, "paragraph", "quote");
      String id = DocumentModelValues.text(entry.path("paragraph"), "paragraph");
      UUID paragraph = UUID.fromString(id);
      if (!paragraph.toString().equalsIgnoreCase(id))
        throw new IllegalArgumentException("Paragraph must be a canonical UUID");
      attributions.add(
          new Grounded(paragraph, DocumentModelValues.text(entry.path("quote"), "quote")));
    }
    return attributions;
  }

  private static List<Integer> incorporated(JsonNode entries) {
    List<Integer> numbers = new ArrayList<>();
    for (JsonNode entry : DocumentModelValues.array(entries))
      numbers.add(DocumentModelValues.number(entry));
    DocumentModelValues.distinct(numbers);
    return numbers;
  }

  private static List<Rejection> rejections(JsonNode entries) {
    List<Rejection> rejections = new ArrayList<>();
    for (JsonNode entry : DocumentModelValues.array(entries)) {
      DocumentModelValues.fields(entry, "challenge", "reason");
      rejections.add(
          new Rejection(
              DocumentModelValues.number(entry.path("challenge")),
              DocumentModelValues.text(entry.path("reason"), "reason")));
    }
    return rejections;
  }

  private static int words(String quote) {
    String flattened = WHITESPACE.matcher(quote).replaceAll(" ").strip();
    return flattened.isEmpty() ? 0 : flattened.split(" ").length;
  }

  private static String truncated(String block) {
    String oneLine = WHITESPACE.matcher(block).replaceAll(" ").strip();
    return oneLine.length() <= MOST_QUOTED ? oneLine : oneLine.substring(0, MOST_QUOTED) + "...";
  }
}
