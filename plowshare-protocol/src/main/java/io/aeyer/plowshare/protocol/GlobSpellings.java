package io.aeyer.plowshare.protocol;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What one glob pattern means, in the language a model actually writes.
 *
 * <h2>Java's {@code **} is not the {@code **} the model learned</h2>
 *
 * <p>Measured on JDK 21: {@code getPathMatcher("glob:**&#47;*.java")} does not match {@code
 * A.java}, because the separator written after {@code **} has to be there in the path too. Python's
 * {@code Path.glob} — which is what Excalibur's {@code file_glob} is, and what every model has seen
 * a thousand examples of — reads {@code **&#47;} as <em>zero or more directories</em> and matches
 * it.
 *
 * <p>Shipping Java's reading would not be a refusal a model could correct. It would be a <em>wrong
 * answer</em>: {@code **&#47;*.java} over a repository whose entry point sits at the top would come
 * back without it, and nothing in the result would say a file had been missed.
 *
 * <p>So each {@code **&#47;} contributes both readings — kept, for one or more directories, and
 * elided, for none — and a path matching any spelling is a hit. That is the union Python computes
 * in one pass. Duplicates are dropped because {@code **&#47;**&#47;} produces the same string
 * twice, which is also why adjacent separators cost far less than {@code 2^n} — {@link
 * #MAX_RECURSIVE_WILDCARDS} measures both shapes.
 *
 * <p>A pattern of {@code **&#47;} alone expands to the empty spelling, and {@code
 * getPathMatcher("glob:")} compiles and matches nothing that reaches a filter — measured. It is
 * harmless rather than handled: a walk only ever offers regular files, and the empty relative path
 * is a directory.
 *
 * <p><b>This class owns the Java-versus-Python fact.</b> Everything else that needs it points here.
 *
 * <h2>Why it is in {@code plowshare-protocol} rather than beside the provider</h2>
 *
 * <p>It shipped inside {@code LocalProvider} while one process searched anything. Task 7 gives the
 * client module a tree of its own to search, and <b>a second expansion of {@code **&#47;} is the
 * one duplication that changes an answer rather than a refusal</b>: the two machines would return
 * different file sets for one pattern in one {@code file_glob} listing, with nothing in the listing
 * to say which reading each half used. A cap or a decoder that differs between the two produces a
 * visible refusal from one of them; this produces a shorter list.
 *
 * <p>The same argument {@link FileAccess} carries, one level down: what is shared is what would
 * otherwise be two implementations of one answer.
 *
 * <p>What is deliberately <em>not</em> shared is the rest of the disk mechanics — how large a file
 * either side will read, how many hits either will return, what it does with an unreadable
 * directory. Those bound a refusal, and a refusal from either side is a sentence the model reads
 * and can act on. This one is silent.
 */
public final class GlobSpellings {

  /**
   * How many {@code **&#47;} a pattern may contain.
   *
   * <p>{@link #spellings} expands each one into two, so the matcher count is {@code 2^n} — and the
   * cost that matters is <b>time</b>, since every spelling is matched against every file a walk
   * reaches. Compiling them is the smaller half.
   *
   * <p><b>The worked example has to be a non-adjacent one, and an earlier version of this javadoc
   * used an adjacent one that measures 6 rather than 32.</b> Adjacent separators collapse, because
   * eliding the first of {@code **&#47;**&#47;} produces the same string as eliding the second and
   * the set keeps one — so {@code **&#47;**&#47;**&#47;**&#47;**&#47;*.java} is five separators and
   * <b>6</b> spellings, measured. The genuine {@code 2^n} needs them apart: {@code
   * a&#47;**&#47;b&#47;**&#47;c&#47;**&#47;d&#47;**&#47;e&#47;**&#47;f.java} is five separators and
   * <b>32</b>, also measured, and is what this constant refuses.
   *
   * <p><b>Four, and not one, is held by {@code
   * a_pattern_at_the_wildcard_cap_still_finds_its_file}</b> in {@code LocalProviderTest}, which
   * globs a literal four-separator pattern and expects the file. The refusal test cannot hold it:
   * its pattern is built from this constant, so it moves with it and any value passes. That is the
   * standing hazard this slice has now met three times — <em>a fixture derived from the constant it
   * is meant to pin holds nothing</em> — and the accepted side has to be spelled out absolutely for
   * the number to mean anything.
   *
   * <p>It is one number for both processes on purpose. A client that expanded more than the server
   * would search a tree the server refused to describe, and the two halves of one listing would
   * disagree about what the pattern was.
   */
  public static final int MAX_RECURSIVE_WILDCARDS = 4;

  /**
   * The separator that means "zero or more directories".
   *
   * <p>Named so that {@link #recursiveAt} and {@link #spellings} step over the same number of
   * characters as they search for: a bare {@code 3} beside a bare {@code "**&#47;"} is two
   * spellings of one fact.
   */
  private static final String RECURSIVE = "**/";

  private GlobSpellings() {}

  /**
   * The matchers one pattern means.
   *
   * <p>Unchecked {@link IllegalArgumentException} rather than either module's refusal type, because
   * this module has neither: {@code WorkspaceRefusedException} is the server's and the client
   * answers over a wire. <b>The message is the whole of what a caller passes on</b>, so it is
   * written to be true wherever it is read — it says what the pattern has and what is expanded, and
   * never "this server", which is false on the half of the calls that happen on somebody's laptop.
   *
   * @throws IllegalArgumentException if the pattern has more {@code **&#47;} segments than will be
   *     expanded, or if some spelling of it is not a glob the platform can compile
   */
  public static List<PathMatcher> matchers(String pattern) {
    int recursive = 0;
    for (int at = recursiveAt(pattern, 0); at >= 0; at = recursiveAt(pattern, at + 1)) {
      recursive++;
    }
    if (recursive > MAX_RECURSIVE_WILDCARDS) {
      throw new Unusable(
          pattern,
          recursive,
          null,
          "'"
              + pattern
              + "' has "
              + recursive
              + " '**/' segments and at most "
              + MAX_RECURSIVE_WILDCARDS
              + " are expanded; one is almost always what is meant");
    }
    Set<String> spellings = new LinkedHashSet<>();
    spellings(pattern, 0, "", spellings);
    List<PathMatcher> matchers = new ArrayList<>(spellings.size());
    for (String spelling : spellings) {
      try {
        matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + spelling));
      } catch (IllegalArgumentException unusable) {
        // Measured: an unclosed '[' raises PatternSyntaxException from
        // getPathMatcher, and it is unchecked — so without this the
        // JDK's own regex type would leave this seam untranslated, and
        // no provider on the far end of a socket could ever reproduce
        // it. Caught at IllegalArgumentException, which
        // PatternSyntaxException extends and which getPathMatcher also
        // declares for an argument that is not `syntax:pattern` — a
        // shape this method cannot produce, since it writes the prefix
        // itself. The wider catch is free rather than load-bearing: the
        // two cannot be named together anyway, because the compiler
        // refuses multi-catch alternatives related by subclassing.
        //
        // Rethrown rather than passed through so that the message names
        // the pattern the caller wrote. PatternSyntaxException's own
        // message describes one expanded spelling, which is a string
        // nobody typed.
        throw new Unusable(
            pattern,
            0,
            unusable.getMessage(),
            "'" + pattern + "' is not a usable glob: " + unusable.getMessage());
      }
    }
    return matchers;
  }

  /** Whether any spelling of the pattern matches a path relative to its root. */
  public static boolean matches(List<PathMatcher> matchers, Path relative) {
    for (PathMatcher matcher : matchers) {
      if (matcher.matches(relative)) {
        return true;
      }
    }
    return false;
  }

  private static void spellings(String pattern, int from, String built, Set<String> into) {
    int at = recursiveAt(pattern, from);
    if (at < 0) {
      into.add(built + pattern.substring(from));
      return;
    }
    String head = built + pattern.substring(from, at);
    // Past the separator itself. `recursiveAt` and the count in `matchers`
    // walk the same positions by the same rule, which is why the number of
    // recursions and the number counted agree; they agree by construction
    // and not by luck, because both ask `recursiveAt` and nothing else.
    int after = at + RECURSIVE.length();
    spellings(pattern, after, head + RECURSIVE, into);
    spellings(pattern, after, head, into);
  }

  /**
   * The next {@code **&#47;} that is a whole path component, or -1.
   *
   * <p><b>A whole component, not the characters wherever they fall.</b> Only a standalone {@code
   * **} means "zero or more directories" in the language this is porting; {@code src**} is one
   * component with a wildcard in it, and both Java and Python read it that way. Eliding it anyway
   * would expand {@code src**&#47;*.java} to {@code src*.java} and return a top-level {@code
   * srcMain.java} that <em>neither</em> language matches — an invented hit, which is worse than the
   * missing hit the elision exists to prevent, because a wrong file in a listing is one a model
   * will go on to read. {@code src**&#47;*.java} is an ordinary typo for {@code
   * src&#47;**&#47;*.java}.
   */
  private static int recursiveAt(String pattern, int from) {
    for (int at = pattern.indexOf(RECURSIVE, from);
        at >= 0;
        at = pattern.indexOf(RECURSIVE, at + 1)) {
      if (at == 0 || pattern.charAt(at - 1) == '/') {
        return at;
      }
    }
    return -1;
  }

  /**
   * A pattern this cannot expand, with the facts a refusal is worded from — the file sides answer
   * with those and the server words them (spec 2026-09-30). Still an {@link
   * IllegalArgumentException}, whose message is the one it always had, for every caller that
   * catches that.
   */
  public static final class Unusable extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String pattern;
    private final int recursive;
    private final String detail;

    Unusable(String pattern, int recursive, String detail, String message) {
      super(message);
      this.pattern = pattern;
      this.recursive = recursive;
      this.detail = detail;
    }

    /** The pattern as it was written. */
    public String pattern() {
      return pattern;
    }

    /**
     * How many {@code **}{@code /} segments it has, when that is why — more than {@link
     * #MAX_RECURSIVE_WILDCARDS}; 0 otherwise.
     */
    public int recursive() {
      return recursive;
    }

    /** What the platform's matcher said, when it is why; null otherwise. */
    public String detail() {
      return detail;
    }

    /** This, as the facts a refusal of a glob carries. */
    public FileResult result() {
      return detail == null
          ? FileResult.tooManyWildcards(pattern, recursive, MAX_RECURSIVE_WILDCARDS)
          : FileResult.pattern(FileResult.BAD_PATTERN, pattern, detail);
    }
  }
}
