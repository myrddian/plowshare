package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Turns one paragraph into the pieces that get embedded.
 *
 * <p>Ported from Anchor's {@code ingest/Chunker} — sentence-aware greedy packing, a chunk never
 * half a claim — with its two defects fixed rather than carried across. Both are the survey's
 * ({@code implementation rationale} §3.1) and both are about the same thing: <b>what a chunk's size
 * is measured in, and whether anything bounds it.</b>
 *
 * <h2>The unit is the configured tokenizer's, and bytes are not tokens</h2>
 *
 * <p>The quantity that matters is tokens: {@code nomic-embed-text} reports {@code
 * loaded_context_length} 2048 and {@code max_context_length} 2048 — measured against the reference
 * node on 2026-09-03 — so there is no headroom to be bought by configuration. Every bound here is
 * counted by the {@link Tokenizer} carried in {@link Chunking}, and this class works out no length
 * of its own.
 *
 * <p>Anchor's stand-in is {@code text.trim().split("\\s+").length}, whitespace words, and its
 * comment says: "the LLM tokeniser would count slightly more on average, so chunks land a touch
 * under target — fine." <b>The second clause does not follow from the first</b>: if the real
 * tokenizer counts <em>more</em> than the estimator, a chunk the chunker believes is 300 lands
 * <em>over</em> target, and a CJK line is one whitespace word. That is the defect of a unit this
 * class chose for itself.
 *
 * <p>This class used to bound UTF-8 bytes instead, on the argument that no tokenizer emits more
 * tokens than its input has bytes — a bound rather than an estimate. It was also a quarter of the
 * window for English prose, and it refused text the model would have taken whole: every 300-word
 * digest summary the archive wrote was two to three kilobytes and none of them was embedded. <b>The
 * tokenizer is the one place a count is made</b>, so a real tokenizer replacing the heuristic
 * behind the interface changes every chunk bound at once, with nothing here to edit.
 *
 * <p>What that costs, stated so it is not discovered: the shipped tokenizer is {@code
 * RatioTokenizer}, which estimates, and an estimate can be low. The ceiling is held below the
 * model's window to leave room for that error ({@code application.yml} carries the arithmetic), and
 * text that tokenizes far denser than prose — a script without spaces most of all — can still be
 * under-counted past that margin. The embedding endpoint is the backstop there.
 *
 * <h2>The overflow branch has a ceiling, and a cut is recorded</h2>
 *
 * <p>Anchor's overflow branch is unbounded:
 *
 * <pre>{@code
 * if (sentenceTokens > chunkTargetTokens) {
 *     ...
 *     chunks.add(new Chunk(sentence, sentenceTokens));   // no ceiling at all
 * }
 * }</pre>
 *
 * <p>and {@code BreakIterator} over PDF-extracted text does not always return a sentence: a table
 * re-flowed into one line, a reference list, a code block or an equation block is one "sentence" of
 * arbitrary size. Combined with {@code EmbeddingService.embedAll} sending every chunk of a document
 * as one HTTP request, one such run fails the whole document's batch <em>after</em> the
 * summarisation cascade has been paid for.
 *
 * <p><b>Here an oversized run is cut at the ceiling and the chunk says it was cut.</b> The
 * alternative — refuse — is this repository's usual answer to "this cannot be carried", and it is
 * the wrong one here: refusing loses a whole document over one re-flowed table, in a pipeline whose
 * input is other people's PDFs. What must never happen is a <em>silent</em> truncation, which
 * produces a vector for text that is not the text; {@link Chunk#splitMidSentence} is what makes the
 * cut a recorded fact rather than a silent one, and it is stored on the row. Every byte of the
 * paragraph is still in some chunk.
 *
 * <p>Cutting is by code point and never by {@code char} index, so a cut cannot land inside a
 * character, and it prefers the last whitespace in the window: a chunk ending mid-word embeds a
 * token sequence that occurs nowhere in the document.
 *
 * <h2>Static, and not a {@code @Service}</h2>
 *
 * <p>Anchor's is a {@code @Service} with no state and no dependency. There is nothing here to
 * inject and nothing to stub, and a bean would make a pure function look like a collaborator.
 */
public final class Chunker {

  private Chunker() {}

  /**
   * One paragraph's chunks, in order.
   *
   * <p>Every returned chunk counts at most {@link Chunking#maxTokens()} by the bounds' tokenizer,
   * and the concatenation of the chunks is the paragraph — with whitespace between sentences
   * normalised by the packing, and no whitespace introduced or lost inside a cut run.
   *
   * <p>Packing counts the string that would be emitted, joined and whole, and not the sum of its
   * sentences: a real tokenizer is not additive across a join, and the ceiling is a promise about
   * the string that is sent.
   *
   * @param paragraphText the paragraph. Null or blank yields no chunks, which is an ordinary answer
   *     rather than an error: a paragraph splitter that hands over an empty run has nothing to say
   *     and neither has this
   * @param bounds the tokenizer, the target packing aims at, and the ceiling nothing may exceed
   */
  public static List<Chunk> chunk(String paragraphText, Chunking bounds) {
    Objects.requireNonNull(bounds, "bounds");
    if (paragraphText == null || paragraphText.isBlank()) {
      return List.of();
    }

    List<Chunk> chunks = new ArrayList<>();
    String current = "";

    for (String sentence : sentences(paragraphText.trim())) {
      // Longer than the ceiling on its own: flush what is packed, then
      // cut the run into ceiling-sized pieces. Anchor emits it whole.
      if (bounds.tokens(sentence) > bounds.maxTokens()) {
        flush(current, chunks);
        current = "";
        cut(sentence, bounds, chunks);
        continue;
      }

      String joined = current.isEmpty() ? sentence : current + ' ' + sentence;
      if (!current.isEmpty() && bounds.tokens(joined) > bounds.targetTokens()) {
        flush(current, chunks);
        joined = sentence;
      }
      current = joined;
    }
    flush(current, chunks);
    return List.copyOf(chunks);
  }

  /**
   * A run longer than the ceiling, in ceiling-sized pieces, each flagged.
   *
   * <p>Where a piece ends just before a whitespace it is pulled back to it — but only when what
   * that gives up is under an eighth of the ceiling, so a run with one space near its start is not
   * cut down to almost nothing to honour a preference that is about readability rather than
   * correctness.
   */
  private static void cut(String run, Chunking bounds, List<Chunk> chunks) {
    int from = 0;
    while (from < run.length()) {
      int to = longestFitting(run, from, bounds);
      if (to < run.length()) {
        int space = lastSpace(run, from, to);
        if (space > from
            && bounds.tokens(run.substring(from, to)) - bounds.tokens(run.substring(from, space))
                < bounds.maxTokens() / 8) {
          to = space;
        }
      }
      String piece = run.substring(from, to);
      chunks.add(new Chunk(piece, utf8Length(piece), true));
      from = to;
      // The whitespace a cut landed on belongs to neither side; skipping
      // it here is what keeps the pieces from starting with a space.
      while (from < run.length() && Character.isWhitespace(run.codePointAt(from))) {
        from += Character.charCount(run.codePointAt(from));
      }
    }
  }

  /**
   * The end of the longest piece of {@code run} from {@code from} that the tokenizer counts within
   * the ceiling, always on a code point boundary.
   *
   * <p>A gallop and then a bisection, so a piece costs a handful of counts whatever the run's
   * length — counting the window a code point at a time would be a count per character, and a real
   * tokenizer is not cheap. Both assume a longer prefix never counts fewer tokens than a shorter
   * one, which holds of any tokenizer that covers its input.
   *
   * <p><b>One code point always goes in</b>, so every piece advances and the loop in {@link #cut}
   * terminates. A single code point that counts past the ceiling on its own cannot be made smaller;
   * a ceiling that low is a misconfiguration, and the embedding client refuses what it produces.
   */
  private static int longestFitting(String run, int from, Chunking bounds) {
    int fits = run.offsetByCodePoints(from, 1);
    int over = -1;
    for (long width = 64; fits < run.length(); width *= 2) {
      int probe = boundary(run, (int) Math.min(run.length(), from + width));
      if (bounds.tokens(run.substring(from, probe)) > bounds.maxTokens()) {
        over = probe;
        break;
      }
      fits = probe;
    }
    if (over < 0) {
      return fits;
    }
    while (true) {
      int mid = boundary(run, (fits + over) >>> 1);
      if (mid <= fits) {
        return fits;
      }
      if (bounds.tokens(run.substring(from, mid)) <= bounds.maxTokens()) {
        fits = mid;
      } else {
        over = mid;
      }
    }
  }

  /**
   * {@code index}, or the code point boundary just before it when it would land between the two
   * halves of a surrogate pair.
   */
  private static int boundary(String text, int index) {
    if (index > 0
        && index < text.length()
        && Character.isLowSurrogate(text.charAt(index))
        && Character.isHighSurrogate(text.charAt(index - 1))) {
      return index - 1;
    }
    return index;
  }

  /**
   * The last index in {@code (from, to]} holding whitespace, so that a piece ending there ends just
   * before it; or -1. Whitespace is never a surrogate, so reading {@code char}s here cannot land
   * inside a character.
   */
  private static int lastSpace(String run, int from, int to) {
    for (int i = to; i > from; i--) {
      if (Character.isWhitespace(run.charAt(i))) {
        return i;
      }
    }
    return -1;
  }

  private static void flush(String packed, List<Chunk> chunks) {
    if (packed.isEmpty()) {
      return;
    }
    chunks.add(new Chunk(packed, utf8Length(packed), false));
  }

  /**
   * The paragraph's sentences.
   *
   * <p>{@link Locale#ROOT} and not {@code Locale.US}, which is what Anchor uses. The sentence rules
   * are the same for every Latin-script locale this would plausibly see, and naming one language in
   * a class the survey already criticises for being English-and-ASCII throughout would be a claim
   * the code cannot keep.
   */
  private static List<String> sentences(String text) {
    List<String> found = new ArrayList<>();
    BreakIterator breaks = BreakIterator.getSentenceInstance(Locale.ROOT);
    breaks.setText(text);
    int start = breaks.first();
    for (int end = breaks.next(); end != BreakIterator.DONE; start = end, end = breaks.next()) {
      String sentence = text.substring(start, end).trim();
      if (!sentence.isEmpty()) {
        found.add(sentence);
      }
    }
    return found;
  }

  /**
   * The UTF-8 length a chunk row stores as {@code byte_size}. A fact about the text rather than a
   * bound on it: the bounds are tokens.
   */
  static int utf8Length(String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }

  /**
   * One piece of a paragraph, as it will be embedded and stored.
   *
   * @param text the chunk itself. Never blank
   * @param bytes its UTF-8 length, stored on the row as {@code byte_size}. Measured and not
   *     estimated, and no longer what any bound is checked against — those are tokens, counted by
   *     {@link Chunking#tokenizer()} — but kept, because it is what the endpoint is sent and a
   *     later slice comparing tokenizers can read it without re-deriving the corpus
   * @param splitMidSentence whether this chunk's boundaries are the chunker's rather than the
   *     prose's. <b>The one thing that separates a recorded cut from a silent truncation</b>: false
   *     for every chunk packed out of whole sentences, true for every piece of a run that had to be
   *     cut at the ceiling
   */
  public record Chunk(String text, int bytes, boolean splitMidSentence) {

    public Chunk {
      if (text == null || text.isBlank()) {
        throw new IllegalArgumentException("a chunk with no text is not a chunk");
      }
      if (bytes <= 0) {
        throw new IllegalArgumentException("a chunk of " + bytes + " bytes is not a chunk");
      }
    }
  }
}
