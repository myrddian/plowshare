package io.aeyer.plowshare.protocol;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.stream.Stream;

/**
 * Which files below a root a pattern actually names.
 *
 * <h2>Why this is shared, and where the line is</h2>
 *
 * <p>{@link GlobSpellings} argues that what changes an <em>answer</em> is shared
 * and what produces a <em>refusal</em> may differ, because a refusal is a
 * sentence the model reads and a different answer is silent. <b>That line was
 * first drawn one level too shallow.</b> The pattern expansion moved and the walk
 * did not, so two copies of the same four steps sat in {@code LocalProvider} and
 * {@code ClientEnforcer} — and two of those four steps change an answer:
 *
 * <ul>
 *   <li><b>the regular-file filter.</b> A side that dropped it puts directories
 *       and links-to-directories in a listing the other side would not, and the
 *       model reads a name and asks for it;
 *   <li><b>the containment filter, on the resolved path.</b> A side that dropped
 *       it hands back names for files outside the workspace — which is a read
 *       tool with no boundary, one turn later.
 * </ul>
 *
 * <p>Either divergence puts a different file set from each machine into one
 * {@code file_glob} listing, silently, with nothing in the listing to say which
 * half used which rule. That is exactly the harm {@code GlobSpellings} was
 * extracted to prevent, one level down.
 *
 * <p><b>What stays with each caller is the two refusals</b>, and they are
 * genuinely each machine's own: how many hits it will return before refusing, and
 * what it says when a directory under its own root cannot be read. Those bound a
 * refusal and a laptop and a server are entitled to different answers about their
 * own memory.
 *
 * <p>The walk's own two measured facts live here with it: {@code Files.walk} does
 * not follow symlinks by default — which is what stops a link to a parent
 * directory making the walk endless — and it still <em>lists</em> them, so the
 * containment filter is what keeps a link out of the answer. It reports an
 * unreadable directory as an {@code UncheckedIOException} while the stream is
 * being consumed rather than when it is opened.
 */
public final class FileSearch {

    private FileSearch() {
    }

    /**
     * More files matched than the caller said it would return.
     *
     * <p>A type rather than a signal in the return value, because the answer must
     * not come back at all: a list that stopped at a limit reads as a complete
     * one, which is the confident empty answer wearing a different hat.
     *
     * <p>Unchecked and defined here for the reason {@link GlobSpellings#matchers}
     * raises {@link IllegalArgumentException}: this module has neither side's
     * refusal type, so <b>the caller words the sentence</b> and only the fact
     * travels. {@link #limit} is on it so that neither caller has to remember to
     * put its own number back into its own message.
     */
    public static final class TooManyMatches extends IllegalStateException {

        private final int limit;

        TooManyMatches(int limit) {
            super("more than " + limit + " files match");
            this.limit = limit;
        }

        /** The number the caller passed in, so its refusal can name it. */
        public int limit() {
            return limit;
        }
    }

    /**
     * Add every file below {@code root} that this pattern names to {@code into}.
     *
     * @param root a directory the caller has already established is reachable
     * @param matchers every spelling of one pattern, from {@link
     *     GlobSpellings#matchers}
     * @param access the containment this caller enforces. Applied to the
     *     <em>resolved</em> candidate, which is what makes a listing agree with
     *     what a later read would be allowed to open
     * @param into the hits so far, appended to. Passed in rather than returned so
     *     that a caller walking several roots keeps one budget across them
     * @param limit the most {@code into} may hold
     * @throws TooManyMatches if the limit would be exceeded — never a silent
     *     truncation
     * @throws IOException if the tree cannot be listed in full. {@code
     *     UncheckedIOException} is unwrapped into this, so a caller has one thing
     *     to catch rather than two shapes of the same fact
     */
    public static void matching(Path root, List<PathMatcher> matchers, FileAccess access,
            List<Path> into, int limit) throws IOException {

        eachFile(root, access, candidate -> {
            if (!GlobSpellings.matches(matchers, root.relativize(candidate))) {
                return true;
            }
            if (into.size() >= limit) {
                throw new TooManyMatches(limit);
            }
            into.add(candidate);
            return true;
        });
    }

    /**
     * What a walker is handed, once per file that is inside the leash.
     *
     * <p>Checked rather than unchecked, because both things that walk a tree in
     * this system open files while they do it, and an {@link IOException} from
     * one of them belongs to the same {@code catch} the walk's own goes to.
     */
    @FunctionalInterface
    public interface Visitor {

        /**
         * @return whether to keep walking. False stops the walk where it is,
         *     which is how a caller with a spent budget avoids reading the rest
         *     of a repository to discover it has nothing left to put the answers
         *     in
         */
        boolean visit(Path file) throws IOException;
    }

    /**
     * Every regular file below {@code root} that {@code access} permits, one at
     * a time.
     *
     * <h2>Why the walk is shared and not only the pattern expansion</h2>
     *
     * <p>This is the class's own argument applied a second time. {@link
     * #matching} was extracted because two copies of the four steps had drifted
     * apart, and two of those steps — <b>a directory is not a hit</b> and
     * <b>containment is checked on the resolved path</b> — change an
     * <em>answer</em> rather than a refusal. {@code file_grep} walks the same
     * trees for the same reason and would otherwise have been a third and fourth
     * copy of exactly those two steps, on the tool where getting the second one
     * wrong is worst: a glob that leaked an excluded path leaks a name, and a
     * grep that leaked one leaks the line.
     *
     * <p><b>The order of the filters is not the order {@code matching} used to
     * apply them</b>, and nothing observable turns on it: the pattern test used
     * to run first and now runs last, but all three are filters over the same
     * candidates and only a candidate passing all three ever reached the limit
     * check or the list.
     *
     * <p>The two measured facts about {@code Files.walk} that this class was
     * written around are still what this method is for. It does not follow
     * symlinks, which is what stops a link to a parent directory making the walk
     * endless — and it still <em>lists</em> them, so the containment filter is
     * what keeps a link pointing out of the root out of the answer. It reports a
     * directory it cannot open as an {@code UncheckedIOException} raised while
     * the stream is being consumed, not when it is opened.
     *
     * @param root a directory the caller has already established is reachable
     * @param access the containment this caller enforces, applied to the
     *     <em>resolved</em> candidate — which is what makes a listing and a
     *     search agree with what a later read would be allowed to open
     * @param visitor what to do with each one, and whether to go on
     * @throws IOException if the tree cannot be listed in full, or if the
     *     visitor itself fails on a file
     */
    public static void eachFile(Path root, FileAccess access, Visitor visitor)
            throws IOException {

        try (Stream<Path> tree = Files.walk(root)) {
            for (Path candidate : (Iterable<Path>) tree::iterator) {
                // isRegularFile follows the link, so a link to a file inside the
                // root is a file and a link to a directory is not. A directory
                // handed back here is a refusal one turn later.
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                // On the resolved path, as a read's check is. A listing tool that
                // skips this is a read tool with no boundary: it hands back names
                // that name files outside the leash. PER CANDIDATE, and that is
                // the whole of the leash on a search with no path in it — a walk
                // that checked once at the top would be a way to read exactly
                // what a read refuses.
                if (!access.permits(candidate)) {
                    continue;
                }
                if (!visitor.visit(candidate)) {
                    return;
                }
            }
        } catch (UncheckedIOException failed) {
            // Measured: this is how Files.walk reports a directory it cannot
            // open, and it arrives while the stream is being consumed rather than
            // when it is opened. Unwrapped so both callers catch one type.
            throw failed.getCause();
        }
    }
}
