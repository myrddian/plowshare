package io.aeyer.plowshare.server.union;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

/**
 * The hub's own check of what a client pushed. The client applies the same rules
 * when it builds {@code info/exclude}; these bytes are about to land on this
 * server's disk, so it verifies them rather than trusting that. Spec §8.
 */
public final class SyncRules {

    public static final long DEFAULT_MAX_FILE_BYTES = 5_242_880L;

    public record Breach(String path, String why) {}

    /**
     * Unicode default-ignorable code points a hostile path can splice into {@code .git} or
     * {@code .plowshare} to slip past a naive comparison while still resolving, on disk, as the
     * reserved name once a filesystem or terminal drops them: soft hyphen, combining grapheme
     * joiner, the zero-width family, the bidi override family, word joiner and friends, and the
     * BOM. Built from code points rather than written as literal characters or backslash-u escapes
     * in this source file, since Java's own Unicode-escape translation runs before the lexer sees
     * string-literal syntax (and applies inside comments too) and would either mangle an escape
     * sitting next to another backslash or (written raw) leave actual invisible characters in this
     * file. Mirrors the TUI's client-side check in {@code conflicts.ts}.
     */
    private static final Set<Integer> IGNORABLE = Set.of(
            0x00AD, 0x034F,
            0x200B, 0x200C, 0x200D, 0x200E, 0x200F,
            0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
            0x2060, 0x2061, 0x2062, 0x2063, 0x2064, 0x2065, 0x2066, 0x2067, 0x2068, 0x2069,
            0x206A, 0x206B, 0x206C, 0x206D, 0x206E, 0x206F,
            0xFEFF);
    private static final Pattern GIT_ALIAS = Pattern.compile("git~\\d+");
    private static final Pattern PLOWSHARE_ALIAS = Pattern.compile("plowsh~\\d+");

    private SyncRules() {}

    /**
     * True for a path segment that names this project's control directories — {@code .git} or
     * {@code .plowshare} — on a case-insensitive filesystem (the macOS/Windows default), allowing
     * for a trailing run of dots/spaces NTFS and APFS both ignore, Windows' short 8.3 alias for
     * either name ({@code git~1}, {@code plowsh~1}, ...), an NTFS alternate-data-stream suffix
     * ({@code .git::$DATA} still opens the {@code .git} file), or Unicode default-ignorable code
     * points spliced into the name. The segment is folded to NFC first, since two visually
     * identical names can be different code point sequences an exact match would treat as
     * different strings. Mirrors {@code reservedSegment} in the TUI's {@code conflicts.ts}.
     */
    public static boolean reservedSegment(String segment) {
        String composed = Normalizer.normalize(segment, Normalizer.Form.NFC);
        StringBuilder stripped = new StringBuilder(composed.length());
        composed.codePoints().filter(point -> !IGNORABLE.contains(point)).forEach(stripped::appendCodePoint);
        String cut = stripped.toString();
        int colon = cut.indexOf(':');
        if (colon >= 0) {
            cut = cut.substring(0, colon);
        }
        String normalized = cut.toLowerCase(Locale.ROOT).replaceAll("[. ]+$", "");
        return normalized.equals(".git") || normalized.equals(".plowshare")
                || GIT_ALIAS.matcher(normalized).matches() || PLOWSHARE_ALIAS.matcher(normalized).matches();
    }

    /**
     * Whether a repository-relative path may be in a union. A segment beginning
     * with a dot is allowed only when the path up to and including it is an
     * allowlist entry, or lies under one ending in {@code /}. {@code .git} and
     * {@code .plowshare} anywhere in the path are never allowed, in any case,
     * Unicode-folded form, or with a trailing NTFS alternate-data-stream suffix
     * ({@code reservedSegment} cuts at the first {@code :}). A bare {@code :} in
     * an otherwise ordinary segment (e.g. a timestamp like
     * {@code 2026-09-14T10:00.md}) is legal on macOS and Linux and is not
     * refused here — only Windows/NTFS treats it specially, and this hub does
     * not assume every client is one.
     */
    public static boolean allowed(String path, List<String> hidden) {
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (reservedSegment(segment)) {
                return false;
            }
        }
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                prefix.append('/');
            }
            prefix.append(segments[i]);
            if (!segments[i].startsWith(".")) {
                continue;
            }
            String so = prefix.toString();
            boolean listed = hidden.stream().anyMatch(entry -> entry.endsWith("/")
                    ? (so + "/").startsWith(entry)
                    : so.equals(entry));
            if (!listed) {
                return false;
            }
        }
        return true;
    }

    /**
     * The first path in {@code commit} that breaks this project's rules, if any.
     * With an {@code oldCommit} (the ref's previous tip), only entries {@code
     * commit} adds or modifies over it are checked: a push is judged on what it
     * brings, so a file that was already on {@code main} before a rule tightened
     * (a smaller cap, a shorter hidden allowlist) does not refuse every later
     * push. With {@code oldCommit} null (a first push) every entry is checked.
     */
    public static Optional<Breach> check(Repository repo, String oldCommit, String commit,
            List<String> hidden, long maxFileBytes) {
        try (RevWalk walk = new RevWalk(repo); TreeWalk tree = new TreeWalk(repo);
                ObjectReader reader = repo.newObjectReader()) {
            int newest = 0;
            if (oldCommit != null) {
                tree.addTree(walk.parseCommit(ObjectId.fromString(oldCommit)).getTree());
                tree.setFilter(TreeFilter.ANY_DIFF);
                newest = 1;
            }
            tree.addTree(walk.parseCommit(ObjectId.fromString(commit)).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                FileMode mode = tree.getFileMode(newest);
                if (FileMode.MISSING.equals(mode)) {
                    continue; // deleted by this commit
                }
                String path = tree.getPathString();
                if (!allowed(path, hidden)) {
                    return Optional.of(new Breach(path, "a hidden path this project does not sync"));
                }
                if (FileMode.GITLINK.equals(mode)) {
                    return Optional.of(new Breach(path, "a submodule, which a union cannot hold"));
                }
                long size = reader.getObjectSize(tree.getObjectId(newest), Constants.OBJ_BLOB);
                if (size > maxFileBytes) {
                    return Optional.of(new Breach(path, size + " bytes, over the " + maxFileBytes
                            + "-byte limit per file"));
                }
            }
            return Optional.empty();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
