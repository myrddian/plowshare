package io.aeyer.plowshare.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A migration that has shipped is never edited again.
 *
 * <h2>Why this is a test and not a habit</h2>
 *
 * <p>Flyway checksums each migration over its <b>whole text, comments
 * included</b>, and records that checksum in {@code flyway_schema_history} when
 * the file is applied. {@code application.yml} sets only {@code
 * spring.flyway.enabled} and {@code spring.flyway.locations} — read, not assumed
 * — so Boot's {@code validate-on-migrate} default of {@code true} stands. <b>A
 * one-character edit to an applied file therefore fails every database that has
 * already run it, at boot, with a checksum mismatch</b>, and the repair is a
 * manual {@code flyway repair} on each.
 *
 * <p><b>Nothing else in this suite can see that.</b> Every test that touches
 * Postgres gets a container of its own and replays the chain into an empty
 * database, where a rewritten file is simply the file. So the whole suite goes
 * green on a change that breaks every existing deployment — the shape {@code
 * SourceIsTextTest} exists for one directory over: a fault whose evidence of
 * absence is identical to its evidence of compliance.
 *
 * <p>It has happened twice. {@code 0eadb54} edited {@code V2__proposals.sql} to
 * record a deferral, and task 9's first commit edited the same file again to
 * record that the deferral was closed — the second one shipping alongside two
 * brand-new migrations, so one applied file and two unapplied ones looked alike
 * in the diff. That edit is reverted and {@code V2} is byte-identical to the
 * applied version again; this is what catches the third.
 *
 * <h2>Shipped means on {@code main}, and not merely committed</h2>
 *
 * <p><b>The first version of this class said "once a migration is committed,
 * treat it as applied", and its own commit broke that rule</b> — the same commit
 * rewrote {@code V4}'s header and then pinned the post-edit bytes as {@code V4}'s
 * shipped ones. The mitigation offered was that {@code V4} has never been
 * deployed, which is exactly the fact the rule refused to rely on. A rule whose
 * author negotiates it in the commit that introduces it teaches that it is
 * negotiable, which is the one thing this class exists to make it not.
 *
 * <p>So the rule is the honest one instead. <b>A migration is frozen once it is
 * on {@code main}</b>, because that is the line deployments run from; a
 * migration that exists only on a feature branch has been applied nowhere.
 * Freezing at "committed" is not merely stricter, it is <em>wrong</em>: a branch
 * that develops its schema over several commits would ship V4, V5, V6 and V7
 * encoding its own editing history, permanently, to protect deployments that
 * cannot exist. <b>Measured on this branch: {@code main} holds V1 and V2 and
 * nothing else</b> — V3 was written in this same slice — so "committed" would
 * have frozen a file this branch owns and is still writing.
 *
 * <p>There is no checked-in list of digests any more, and that is the second
 * repair. A manifest a commit can update is a manifest that commit can update:
 * it catches inattention and not intent, and it goes stale the moment a
 * migration's status changes with nobody remembering to move it. Asking {@code
 * git} what {@code main} holds has neither problem — nothing to maintain, and
 * the assertion is the rule rather than a proxy for it.
 *
 * <h2>What it does not claim</h2>
 *
 * <p>It cannot know which migrations a <em>particular</em> database has run — no
 * repository test can. {@code main} is the best available stand-in, and it errs
 * in the direction that costs nothing: editing forward is always available.
 *
 * <p>Bytes and not a checksum. The question is whether the file changed, and
 * reproducing Flyway's own algorithm would be an unmeasured claim about a
 * library to no benefit.
 */
class MigrationsAreImmutableTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /**
     * The line deployments run from, and therefore the line that freezes a file.
     *
     * <p>Named once. Pointing this at the current branch would make every
     * assertion below vacuously true, which is why {@link
     * #the_guard_can_see_at_least_one_shipped_migration} exists.
     */
    private static final String SHIPPED = "main";

    @Test
    void no_migration_that_has_shipped_has_been_edited() throws Exception {
        for (Path file : migrations()) {
            Optional<byte[]> shipped = asShipped(file);
            if (shipped.isEmpty()) {
                continue;
            }
            assertArrayEquals(shipped.get(), Files.readAllBytes(file),
                    file.getFileName() + " is on " + SHIPPED + " and has been edited. Flyway"
                            + " checksums a migration over its whole text, comments included, so"
                            + " every database that has already applied it now fails validation"
                            + " at boot. Revert this file and put the change in a new migration;"
                            + " if the change really is only a comment, it still needs a `flyway"
                            + " repair` on every deployment and is almost never worth it.");
        }
    }

    /**
     * The guard is looking at something, which is the half a loop over an empty
     * set cannot tell you.
     *
     * <p>Without this, a {@link #SHIPPED} that resolved to a ref holding no
     * migrations — a renamed default branch, a clone fetched with {@code
     * --single-branch} — would make the test above pass over zero iterations and
     * report nothing wrong. That is the instrument-that-cannot-record shape this
     * project has been caught by before, and an empty green run is exactly what
     * it looks like.
     */
    @Test
    void the_guard_can_see_at_least_one_shipped_migration() throws Exception {
        List<Path> onDisk = migrations();
        assertFalse(onDisk.isEmpty(), "no migrations found under " + MIGRATIONS.toAbsolutePath()
                + "; this test runs with the module directory as its working directory");

        long frozen = 0;
        for (Path file : onDisk) {
            if (asShipped(file).isPresent()) {
                frozen++;
            }
        }
        assertTrue(frozen > 0,
                "none of the " + onDisk.size() + " migrations is on " + SHIPPED + ", so the test"
                        + " above compared nothing. Either this working copy has no " + SHIPPED
                        + " ref — a shallow or single-branch clone — or the branch deployments"
                        + " run from has been renamed. Fix the ref rather than the assertion: a"
                        + " guard that passes because it could not run is worse than no guard.");
    }

    /** Every migration in the tree, found the way {@code SourceIsTextTest} finds
     *  its files: a walk and not a listing, so a migration filed in a
     *  subdirectory is not silently outside the guard. */
    private static List<Path> migrations() throws IOException {
        try (Stream<Path> found = Files.walk(MIGRATIONS)) {
            return found.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * The file's bytes as {@link #SHIPPED} holds them, or empty if that ref does
     * not have it — which is what "this migration has not shipped" means.
     *
     * <p>{@code git show <ref>:./<path>} resolves the path relative to the
     * process's working directory, which is this module rather than the
     * repository root. Measured rather than assumed, along with the exit status
     * for a path the ref does not hold: 128.
     */
    private static Optional<byte[]> asShipped(Path file) throws Exception {
        Process git = new ProcessBuilder(
                "git", "show", SHIPPED + ":./" + file.toString().replace('\\', '/'))
                .redirectErrorStream(false)
                .start();
        byte[] content;
        try (InputStream out = git.getInputStream()) {
            content = readAll(out);
        }
        git.getErrorStream().readAllBytes();
        return git.waitFor() == 0 ? Optional.of(content) : Optional.empty();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
