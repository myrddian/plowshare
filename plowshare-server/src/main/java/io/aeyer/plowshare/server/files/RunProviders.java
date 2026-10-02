package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.Home;
import java.util.List;

/**
 * The filesystems one run may reach: its tier, the grants its own definition
 * declared, and the session it was submitted under if it was submitted under
 * one.
 *
 * <p><b>Why this is not {@link ProviderRouter.Providers}.</b> That one answers
 * for a tier alone, because a router is shared by every job on the server and a
 * tier is all it can be told. This one is asked once per run, and the second
 * argument is the half a router can never supply: a {@link LocalProvider}
 * carries one agent's grants, so the provider a job talks to cannot exist until
 * it is known <em>which agent</em> is running. {@code JobRuntime} builds a
 * router over this seam per run, which is what closes the gap in one direction
 * without giving a shared object a job's state.
 *
 * <p>The list is re-asked on every routing call rather than taken once, so a
 * client that connects or goes away mid-run changes what the run can see. That
 * is {@link FileProvider#roots()}'s rule arriving one layer up, and the session
 * is what makes it a rule about a particular client rather than about whichever
 * one happened to be connected.
 *
 * <p>Empty is an ordinary answer: a server with no project table wired, or a
 * deployment that offers no filesystem at all, is a legal server, and the tools
 * say so rather than failing.
 */
@FunctionalInterface
public interface RunProviders {

    /**
     * The providers for one run, in the order they should be reported.
     *
     * @param home the tier the job was started for. Never a model's choice — see
     *     {@code AgentTool}
     * @param grants what the running definition's {@code scopes:} declared,
     *     already parsed and checked by {@code AgentRegistry.load}. Empty means
     *     this agent reaches no file at all, which every provider renders as its
     *     own refusal
     * @param sessionId the session the run was submitted under, or {@code null}
     *     for a run that was submitted under none. <b>Nullable is the contract
     *     and not a lapse.</b> A run started by a scheduled tick, by a curator
     *     pass, or over plain HTTP by a caller holding no socket has no client
     *     machine to reach, and the design spec's rule for it is that such a run
     *     <em>"has a smaller set, not an empty capability"</em> — so an
     *     implementation answers with the providers it can build and never
     *     refuses. It is also not a promise that a session by this name exists:
     *     the id is whatever the submitter sent, and {@code
     *     server.session.SessionRegistry.find} answers "no session here" for an
     *     id nothing ever attached to, which is an ordinary answer rather than a
     *     fault
     * @param owner the run log's account, or null for unattended work. Global file access
     *     requires a matching session account; project access requires membership for a named owner
     */
    List<FileProvider> forRun(Home home, List<Grant> grants, String sessionId, String owner);
}
