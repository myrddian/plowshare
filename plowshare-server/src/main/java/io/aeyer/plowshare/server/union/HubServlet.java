package io.aeyer.plowshare.server.union;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.lib.ObjectChecker;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;

/**
 * A union's hub over git smart-HTTP at {@code /v1/sync/<project>.git}. Spec §3.2,
 * §8, §11.2 and §11.7. {@code AuthFilter} already gates {@code /v1/**}; this adds
 * the union's own rules:
 *
 * <ul>
 * <li>only {@code refs/heads/main} and {@code refs/plowshare/conflicts/*};</li>
 * <li>{@code main} only fast-forwards, is never deleted, and moves only while
 *     the gate is not {@code OFFLINE};</li>
 * <li>every pushed {@code main} passes {@link SyncRules}.</li>
 * </ul>
 *
 * <p><b>{@link UnionGate#admitPush} and {@link UnionGate#pushEnded} bracket the
 * whole receive, not each command.</b> A plain {@code OFFLINE} check in the
 * pre-receive hook would leave a window between that check and the ref update
 * in which a {@code SYNCING} claim could expire, or a session could close, and
 * a mirror write could land on the tree before this push's own ref update runs
 * — the two would then race for the tree rather than one strictly following the
 * other. Calling {@code admitPush} once, at the start of the pre-receive hook,
 * closes that window: a {@code true} answer holds the gate's write and
 * run-ended paths off until {@link UnionGate#pushEnded} is called, in a
 * {@code finally} in the post-receive hook, for every outcome — win, loss or a
 * pack that fails before the post-receive hook even runs (the gate drops a
 * stale mark on its own after its sync patience, so a receive that never calls
 * back cannot wedge a project offline forever).
 *
 * <p><b>The receive-pack factory reads the project off the request rather than
 * re-parsing {@code pathInfo} itself.</b> {@link #projectOf} cuts at the last
 * {@code .git} boundary and is exercised directly by its own test, but a
 * project name may itself contain {@code .git} as a substring of one of its
 * own segments (a name like {@code site.github.io}), and a second, independent
 * parse of the same path risks drifting from what the resolver actually opened
 * — which is exactly what happened before this was written this way: the
 * resolver correctly opened {@code site.github.io}'s bare repository, but a
 * naive first-match parse handed the receive hooks {@code site} instead, so a
 * push was screened against the wrong project's hidden-path allowlist, gated
 * by the wrong project's {@code UnionGate} state, and reset the wrong hub's
 * tree to a commit its object store never received. The resolver is the one
 * place that has already decided which project a request names, so it stores
 * that decision on the request under {@link #PROJECT_ATTRIBUTE} and the
 * receive-pack factory reads it back rather than computing its own answer.
 */
public final class HubServlet {

    public static final String MAPPING = "/v1/sync/*";

    /**
     * Request attribute the repository resolver stores the resolved project
     * name under, for the receive-pack factory to read back. JGit resolves the
     * repository for a request before it creates that request's {@code
     * ReceivePack}, both on the same request, so the attribute is always set by
     * the time the factory runs.
     */
    static final String PROJECT_ATTRIBUTE = "plowshare.union.project";

    private HubServlet() {}

    public static GitServlet build(Hubs hubs, UnionGate gate, Function<String, List<String>> hiddenFor,
            long maxFileBytes, long maxPushBytes) {
        Objects.requireNonNull(hubs, "hubs");
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(hiddenFor, "hiddenFor");
        GitServlet servlet = new GitServlet();
        servlet.setRepositoryResolver((HttpServletRequest request, String name) -> {
            String project = name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
            request.setAttribute(PROJECT_ATTRIBUTE, project);
            return hubs.of(project).filter(Hub::exists).map(Hub::openBare)
                    .orElseThrow(() -> new RepositoryNotFoundException(name));
        });
        servlet.setReceivePackFactory((HttpServletRequest request, org.eclipse.jgit.lib.Repository db) -> {
            if (!(request.getAttribute(PROJECT_ATTRIBUTE) instanceof String project)) {
                throw new ServiceNotEnabledException(
                        "no project was resolved for this receive; the repository resolver must run first");
            }
            // The receive starts now: a first push slower than the sync patience must not
            // see its SYNCING claim expire underneath it.
            gate.touch(project);
            AtomicBoolean admitted = new AtomicBoolean(false);
            ReceivePack pack = new ReceivePack(db);
            pack.setAllowNonFastForwards(false);
            pack.setAllowDeletes(true);
            pack.setAtomic(true);
            pack.setMaxPackSizeLimit(maxPushBytes);
            // A second, independent check on the objects a push actually brings in: JGit's own
            // fsck rejects a tree carrying a literal .git entry at the object-parsing level, below
            // SyncRules' path-string screen. setSafeForWindows/setSafeForMacOS are deliberately
            // NOT enabled here — they also reject names that are perfectly legal on macOS and
            // Linux (reserved DOS device names like aux.js/con.txt, a ':' anywhere in a name,
            // trailing dot/space, and two names that only differ by case in the same tree), which
            // would make one such ordinary file stop the whole project from syncing (pushes are
            // atomic). The Unicode-confusable and short-name .git/.plowshare variants those two
            // flags would also catch are still refused by SyncRules.reservedSegment's own,
            // narrower normalisation.
            ObjectChecker checker = new ObjectChecker();
            pack.setObjectChecker(checker);
            pack.setCheckReceivedObjects(true);
            pack.setPreReceiveHook((receiving, commands) -> {
                admitted.set(gate.admitPush(project));
                screen(project, receiving, commands, admitted.get(), hiddenFor, maxFileBytes);
            });
            pack.setPostReceiveHook((receiving, commands) -> {
                try {
                    for (ReceiveCommand command : commands) {
                        if (command.getRefName().equals(Hub.MAIN)
                                && command.getResult() == ReceiveCommand.Result.OK) {
                            gate.pushed(project, nullIfZero(command.getOldId()), command.getNewId().getName());
                        }
                    }
                } finally {
                    if (admitted.get()) {
                        gate.pushEnded(project);
                    }
                }
            });
            return pack;
        });
        return servlet;
    }

    /**
     * The project a URL like {@code "/site.github.io.git/info/refs"} names —
     * {@code "site.github.io"}. Cuts at the <em>last</em> {@code .git}
     * boundary, not the first: a project name may itself contain {@code .git}
     * as a substring of one of its own segments, and the repository resolver
     * (which this must agree with) only ever strips one trailing {@code .git}
     * off the whole repository name JGit hands it.
     */
    public static String projectOf(String pathInfo) {
        String path = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        int end = path.lastIndexOf(".git/");
        if (end >= 0) {
            return path.substring(0, end);
        }
        return path.endsWith(".git") ? path.substring(0, path.length() - 4) : path;
    }

    private static void screen(String project, ReceivePack receiving, Collection<ReceiveCommand> commands,
            boolean admitted, Function<String, List<String>> hiddenFor, long maxFileBytes) {
        for (ReceiveCommand command : commands) {
            String ref = command.getRefName();
            if (ref.equals(Hub.MAIN)) {
                if (command.getType() == ReceiveCommand.Type.DELETE) {
                    command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, "main is never deleted");
                } else if (!admitted) {
                    command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, "project '" + project
                            + "' is not syncing; the client sends union.begin before it pushes");
                } else {
                    SyncRules.check(receiving.getRepository(), nullIfZero(command.getOldId()),
                            command.getNewId().getName(),
                            hiddenFor.apply(project), maxFileBytes).ifPresent(breach -> command.setResult(
                                    ReceiveCommand.Result.REJECTED_OTHER_REASON,
                                    breach.path() + ": " + breach.why()));
                }
            } else if (!ref.startsWith(Hub.CONFLICTS)) {
                command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON,
                        "a union hub holds only main and conflict refs");
            } else if (!admitted) {
                command.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, "project '" + project
                        + "' is not syncing; the client sends union.begin before it pushes");
            }
        }
    }

    private static String nullIfZero(ObjectId id) {
        return id == null || ObjectId.zeroId().equals(id) ? null : id.getName();
    }
}
