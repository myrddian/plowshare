package io.aeyer.plowshare.server.union;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One union project's bare hub and the server's working copy of it. Not
 * thread-safe: {@code UnionGate} serialises every mutating call. Spec §3.2.
 */
public final class Hub {

    public static final String MAIN = "refs/heads/main";
    public static final String CONFLICTS = "refs/plowshare/conflicts/";
    /**
     * Where {@link #commitTree} parks a dirty tree it refused to commit onto a
     * {@code main} the tree was never reset to — {@code <prefix><epoch-millis>}.
     */
    public static final String STRANDED = "refs/plowshare/stranded/";

    private static final Logger log = LoggerFactory.getLogger(Hub.class);
    public static final String SERVER_AUTHOR = "plowshare";

    /**
     * The email domain {@link #commitTree} authors a server commit under —
     * {@code author + SERVER_EMAIL_DOMAIN}. {@link #lastClientCommitTime} tells
     * a client's commit from the server's own by this suffix and no other mark,
     * so every server commit must carry it and nothing a client pushes ever may.
     */
    public static final String SERVER_EMAIL_DOMAIN = "@plowshare.invalid";

    private final Path dir;

    public Hub(Path projectDir) {
        this.dir = Objects.requireNonNull(projectDir, "projectDir");
    }

    public Path bare() {
        return dir.resolve("sync.git");
    }

    public Path tree() {
        return dir.resolve("tree");
    }

    public boolean exists() {
        return Files.isDirectory(bare().resolve("objects"));
    }

    public void create() {
        try {
            Files.createDirectories(tree());
            Git.init().setBare(true).setInitialBranch("main").setDirectory(bare().toFile())
                    .call().close();
        } catch (IOException | GitAPIException failed) {
            throw new IllegalStateException("could not create the hub at " + bare(), failed);
        }
    }

    public Repository openBare() {
        try {
            return new FileRepositoryBuilder().setGitDir(bare().toFile()).setMustExist(true).build();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    public Optional<String> main() {
        try (Repository repo = openBare()) {
            Ref ref = repo.exactRef(MAIN);
            return ref == null || ref.getObjectId() == null
                    ? Optional.empty() : Optional.of(ref.getObjectId().getName());
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    public Optional<Instant> mainTime() {
        try (Repository repo = openBare(); RevWalk walk = new RevWalk(repo)) {
            Ref ref = repo.exactRef(MAIN);
            if (ref == null || ref.getObjectId() == null) {
                return Optional.empty();
            }
            return Optional.of(walk.parseCommit(ref.getObjectId()).getCommitterIdent()
                    .getWhenAsInstant());
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /**
     * The commit {@code tree/} and its index were last reset to or committed
     * as, read from {@code <projectDir>/tree-at}. Empty for a hub whose tree
     * never was.
     */
    public Optional<String> treeAt() {
        Path file = dir.resolve("tree-at");
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            String id = Files.readString(file).trim();
            return id.isEmpty() ? Optional.empty() : Optional.of(id);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /**
     * Commits the dirty tree onto {@code main} as {@code author}.
     *
     * <p><b>Never onto a {@code main} the tree is not at.</b> {@code add .}
     * stages against an index that reflects the last {@link #resetTree}; if
     * {@code main} moved since without one (a reset that threw after a push, a
     * crash between the ref update and post-receive), committing would make a
     * child of the new {@code main} holding the old files — a silent revert of
     * the push. So when {@code main} is present and {@link #treeAt} is absent or
     * differs, a dirty tree is committed on top of {@code treeAt} (or with no
     * parent) under {@link #STRANDED} instead, a warning names that ref, and the
     * tree is reset to {@code main}; nothing is committed and the answer is
     * empty.
     */
    public Optional<String> commitTree(String author, String message) {
        Optional<String> main = main();
        Optional<String> at = treeAt();
        if (main.isPresent() && !at.equals(main)) {
            strand(author, message, at);
            resetTree(main.get());
            return Optional.empty();
        }
        try (Repository repo = worked(); Git git = new Git(repo)) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            Status status = git.status().call();
            if (status.getAdded().isEmpty() && status.getChanged().isEmpty()
                    && status.getRemoved().isEmpty()) {
                return Optional.empty();
            }
            PersonIdent who = new PersonIdent(author, author + SERVER_EMAIL_DOMAIN);
            String committed = git.commit().setAuthor(who).setCommitter(who).setMessage(message)
                    .call().getName();
            recordTreeAt(committed);
            return Optional.of(committed);
        } catch (GitAPIException failed) {
            throw new IllegalStateException("could not commit the tree at " + tree(), failed);
        }
    }

    /** Parks the tree's changes against its index on a {@link #STRANDED} ref, if it has any. */
    private void strand(String author, String message, Optional<String> at) {
        try (Repository repo = worked(); Git git = new Git(repo)) {
            Status before = git.status().call();
            if (before.getUntracked().isEmpty() && before.getModified().isEmpty()
                    && before.getMissing().isEmpty()) {
                return;
            }
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            try (ObjectInserter inserter = repo.newObjectInserter()) {
                ObjectId treeId = repo.readDirCache().writeTree(inserter);
                PersonIdent who = new PersonIdent(author, author + SERVER_EMAIL_DOMAIN);
                CommitBuilder builder = new CommitBuilder();
                builder.setTreeId(treeId);
                at.ifPresent(parent -> builder.setParentId(ObjectId.fromString(parent)));
                builder.setAuthor(who);
                builder.setCommitter(who);
                builder.setMessage("stranded: " + message + "\n\nThe tree was not at main when this"
                        + " was committed; committing onto main would have reverted a push.\n");
                ObjectId commit = inserter.insert(builder);
                inserter.flush();
                for (long millis = System.currentTimeMillis(); ; millis++) {
                    String ref = STRANDED + millis;
                    RefUpdate update = repo.updateRef(ref);
                    update.setNewObjectId(commit);
                    update.setExpectedOldObjectId(ObjectId.zeroId());
                    RefUpdate.Result result = update.update();
                    if (result == RefUpdate.Result.NEW) {
                        log.warn("the tree at {} was not at main ({}); its changes are stranded on {}"
                                + " rather than committed over a push", tree(), at.orElse("unknown"), ref);
                        return;
                    }
                    if (result != RefUpdate.Result.LOCK_FAILURE && result != RefUpdate.Result.REJECTED) {
                        throw new IllegalStateException("could not store stranded changes on " + ref
                                + ": " + result);
                    }
                }
            }
        } catch (GitAPIException failed) {
            throw new IllegalStateException("could not strand the tree at " + tree(), failed);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private void recordTreeAt(String commit) {
        try {
            Path file = dir.resolve("tree-at");
            Path next = dir.resolve("tree-at.next");
            Files.writeString(next, commit + "\n");
            Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /**
     * When the machine last pushed, as opposed to {@link #mainTime}, which is
     * the tip of {@code main} and can be a later server commit made by {@link
     * #commitTree} at a run's end. Walks history from {@code main}, newest
     * first, and answers the committer time of the first commit whose author
     * email does not carry {@link #SERVER_EMAIL_DOMAIN} — a client's push, never
     * one this server made. Empty when {@code main} is absent or every commit
     * reachable from it is the server's own.
     */
    public Optional<Instant> lastClientCommitTime() {
        try (Repository repo = openBare(); RevWalk walk = new RevWalk(repo)) {
            Ref ref = repo.exactRef(MAIN);
            if (ref == null || ref.getObjectId() == null) {
                return Optional.empty();
            }
            walk.markStart(walk.parseCommit(ref.getObjectId()));
            for (RevCommit commit : walk) {
                String email = commit.getAuthorIdent().getEmailAddress();
                if (email == null || !email.endsWith(SERVER_EMAIL_DOMAIN)) {
                    return Optional.of(commit.getCommitterIdent().getWhenAsInstant());
                }
            }
            return Optional.empty();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    public void resetTree(String commit) {
        try (Repository repo = worked(); Git git = new Git(repo)) {
            git.reset().setMode(ResetType.HARD).setRef(commit).call();
            recordTreeAt(ObjectId.fromString(commit).getName());
        } catch (GitAPIException failed) {
            throw new IllegalStateException("could not check " + commit + " out into " + tree(), failed);
        }
    }

    public void delete() {
        if (!Files.exists(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** The hub's object store with {@code tree/} as its work tree; the index lives in the hub. */
    private Repository worked() {
        try {
            Files.createDirectories(tree());
            return new FileRepositoryBuilder().setGitDir(bare().toFile()).setWorkTree(tree().toFile())
                    .setMustExist(true).build();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
