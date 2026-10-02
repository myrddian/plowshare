package io.aeyer.plowshare.server.union;

import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.files.Changed;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.LocalProvider;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A union's server copy, reached under the client's own paths. Spec §5.1.
 *
 * <p>The workspace string is never resolved on this server (V15): a path under it
 * is rewritten onto {@code tree/}, and every path and sentence coming back is
 * rewritten the other way, so a conversation that read
 * {@code /Users/example/proj/ledger/src/a.ts} while the laptop was connected reads
 * the same path while it is not. A path not under the workspace is passed through
 * untouched and refused by the leash, which covers only {@code tree/}.
 *
 * <p><b>Exclusions are the project row's own, spelled on the client and mapped
 * onto the tree.</b> {@code exclusions} names paths under {@code workspace}
 * exactly the way a {@code projects} row does — {@code
 * /Users/example/proj/ledger/secrets}, say — and each one is rewritten onto {@code
 * tree/} with the same translation {@link #toTree} applies to an ordinary path,
 * so a directory the row excludes on the laptop is excluded here too. An
 * exclusion that does not lie under {@code workspace} is dropped rather than
 * passed through, since there is no tree path for it to become. {@code
 * ProjectStore.effectiveExclusions}' mandatory pair — the server's own console
 * token and config file — do <b>not</b> apply here: those are paths on this
 * server's disk, never on the client's, and folding them in would exclude a
 * directory under {@code tree/} that happens to share their spelling by
 * accident rather than by the row's own declaration.
 */
public final class MirrorProvider implements FileProvider {

    public static final String NAME = "mirror";

    private final String project;
    private final Path workspace;
    private final Path tree;
    private final Path canonicalTree;
    private final UnionGate gate;
    private final LocalProvider inner;

    public MirrorProvider(String project, Path workspace, List<Path> exclusions, Path tree,
            UnionGate gate, List<Grant> grants) {
        this.project = Objects.requireNonNull(project, "project");
        this.workspace = Objects.requireNonNull(workspace, "workspace").normalize();
        this.tree = Objects.requireNonNull(tree, "tree");
        this.canonicalTree = FileAccess.canonical(tree);
        this.gate = Objects.requireNonNull(gate, "gate");
        List<Path> mapped = exclusions.stream()
                .map(excluded -> excluded.normalize())
                .filter(excluded -> excluded.startsWith(this.workspace))
                .map(excluded -> tree.resolve(this.workspace.relativize(excluded).toString()))
                .toList();
        this.inner = LocalProvider.over(FileAccess.of(List.of(tree), mapped), grants);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Path> roots() {
        return translated(() -> inner.roots().stream().map(this::toWorkspace).toList());
    }

    @Override
    public Span read(Path path, Window window) {
        return translated(() -> inner.read(toTree(path), window));
    }

    @Override
    public Span stat(Path path) {
        return translated(() -> inner.stat(toTree(path)));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The pattern crosses unmapped, never prefixed with the workspace and
     * never rewritten onto {@code tree/}: {@link LocalProvider#glob} matches a
     * pattern relative to its roots and refuses an absolute one outright, so
     * there is nothing here to translate on the way in. Only the answer — every
     * hit is an absolute path under {@code tree/} — is rewritten back onto the
     * client's paths.
     */
    @Override
    public List<Path> glob(String pattern) {
        return translated(() -> inner.glob(pattern).stream().map(this::toWorkspace).toList());
    }

    @Override
    public Found grep(Needle needle, Path path) {
        return translated(() -> {
            Found found = inner.grep(needle, path == null ? null : toTree(path));
            return new Found(found.matches().stream()
                    .map(m -> new Found.Match(toWorkspace(Path.of(m.path())).toString(), m.offset(),
                            m.line(), m.truncated()))
                    .toList(), found.stoppedBy());
        });
    }

    @Override
    public Changed write(Path path, String content) {
        return translated(() -> gated(() -> inner.write(toTree(path), content)));
    }

    @Override
    public Changed create(Path path, String content) {
        return translated(() -> gated(() -> inner.create(toTree(path), content)));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The facts are the file's own lines and where they are, handed back as
     * they came: rewriting the tree's spelling inside a file's text would change
     * what the model copies into its next edit. Their path is the tree's, and
     * nothing above reads it: the file tools name the file by the path they were
     * called with, and a refusal is reworded here like any other.
     */
    @Override
    public Changed edit(Path path, String old, String replacement) {
        return translated(() -> gated(() -> inner.edit(toTree(path), old, replacement)));
    }

    @Override
    public Changed delete(Path path) {
        return translated(() -> gated(() -> inner.delete(toTree(path))));
    }

    /** The change, made under the project's write gate, and what it reported. */
    private Changed gated(Supplier<Changed> change) {
        Changed[] made = new Changed[1];
        gate.write(project, () -> made[0] = change.get());
        return made[0];
    }

    /**
     * Refused: a union's server copy is a mirror that sync rewrites, and a build
     * run against it would be run against a tree nobody works in. Not in this
     * slice (spec 2026-09-14, run).
     */
    @Override
    public CommandRunner.Outcome run(Path cwd, List<String> argv,
            EnvironmentFile.Side side, Duration timeout,
            BooleanSupplier cancelled) {
        throw new WorkspaceRefusedException("commands do not run on the server's copy of a union"
                + " project; run them on the machine the files are worked on");
    }

    @Override
    public Changed move(Path from, Path to) {
        return translated(() -> gated(() -> inner.move(toTree(from), toTree(to))));
    }

    private Path toTree(Path path) {
        Path normal = path.normalize();
        return normal.startsWith(workspace) ? tree.resolve(workspace.relativize(normal).toString()) : normal;
    }

    private Path toWorkspace(Path path) {
        Path normal = path.normalize();
        if (normal.startsWith(canonicalTree)) {
            return workspace.resolve(canonicalTree.relativize(normal).toString());
        }
        if (normal.startsWith(tree)) {
            return workspace.resolve(tree.relativize(normal).toString());
        }
        return normal;
    }

    private String words(String said) {
        return said == null ? null : said.replace(canonicalTree.toString(), workspace.toString())
                .replace(tree.toString(), workspace.toString());
    }

    private <T> T translated(Supplier<T> call) {
        try {
            return call.get();
        } catch (WorkspaceRefusedException refused) {
            throw new WorkspaceRefusedException(words(refused.getMessage()));
        } catch (WorkspaceUnavailableException unavailable) {
            throw new WorkspaceUnavailableException(words(unavailable.getMessage()));
        }
    }
}
