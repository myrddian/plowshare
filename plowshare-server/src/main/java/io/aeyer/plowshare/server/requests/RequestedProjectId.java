package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.archive.ValidationException;
import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * A project name, translated to {@link ProjectStore}'s own surrogate id — four
 * times, once per call context, because each context disagrees with the other
 * three about what to do with a malformed name, a database outage, or a name
 * with no row behind it.
 *
 * <h2>Four factories and no shared refusal, deliberately</h2>
 *
 * <p>{@link RequestedDocument}'s own class javadoc makes the argument this
 * class rests on: sharing a parse is fine, sharing a refusal is not, when the
 * callers do not agree on what the refusal should say. Here the four do not
 * even agree on whether there <em>is</em> a refusal for a given input — the
 * table below is the whole of what tells them apart, verified against {@link
 * ProjectStore#id}'s own contract:
 *
 * <table border="1">
 *   <caption>the three ways a name can fail to become an id</caption>
 *   <tr><th>factory</th><th>malformed name</th><th>database outage</th>
 *       <th>no row for the name</th></tr>
 *   <tr><td>{@link #lenient}</td><td>{@code null}, silently</td>
 *       <td>propagates</td><td>{@code null}</td></tr>
 *   <tr><td>{@link #of}</td><td>delegates to {@link #lenient}</td>
 *       <td>delegates</td><td>delegates</td></tr>
 *   <tr><td>{@link #forListing}</td><td>a {@link CallerFault}</td>
 *       <td>{@code null} — degrades to the boot set</td><td>{@code null}</td></tr>
 *   <tr><td>{@link #forWrite}</td><td>a {@link CallerFault}</td>
 *       <td>propagates</td><td>a {@link CallerFault}, argued on its own
 *       javadoc</td></tr>
 * </table>
 *
 * <h2>{@link #forListing}'s outage behaviour is the least obvious decision
 * here, and it is deliberate</h2>
 *
 * <p>{@link ProjectStore#id} wraps a dead connection in {@link
 * ArchiveUnavailableException}, and {@code AgentController}'s own class
 * javadoc promises a deployment with nothing configured an empty list rather
 * than a refusal on its first screen — the global tier already answers with no
 * database reachable at all, and a project scope failing to reach one is the
 * same promise, one layer in. <b>The outage is not lost</b>: a submission
 * still reaches {@link #lenient}, through {@code agents.Callers.callerFor},
 * and that factory does not catch {@link ArchiveUnavailableException} — so the
 * fault a caller would act on is still a 503 on the door that writes
 * something, and only a <em>read</em> of the catalogue degrades quietly.
 *
 * <h2>{@link CallerFault} and not {@code BadRequestException}</h2>
 *
 * <p>These four factories now live in {@code requests}, unlike the domain
 * rules that left {@code api} for {@code agents.Callers} — a value type
 * parsing a request field belongs beside the other {@code Requested*} types,
 * and {@code agents.Callers.callerFor} and {@code .callerForConversation}
 * call {@link #lenient} and {@link #of} directly, as a plain static value
 * type with no injected collaborator of its own to wire. Staying in {@code
 * api} would have let {@link #forListing} and {@link #forWrite} keep throwing {@code
 * BadRequestException} without changing anything a caller can observe. They
 * throw {@link CallerFault} anyway, for consistency with {@link
 * RequestedDocument}, {@link RequestedHome} and {@link RequestedPaths} — the
 * three {@code Requested*} types already shipped — and because {@link
 * CallerFault} is what a value type reused from below {@code api} is going to
 * need regardless. Both map to 400 with an identical body through {@code
 * ApiExceptionHandler}, so this is not a behaviour change.
 */
public final class RequestedProjectId {

    private RequestedProjectId() {
    }

    /**
     * A project name's id, or {@code null} for a name this store cannot
     * resolve — malformed, or simply naming no row yet.
     *
     * <p><b>A malformed name resolves to {@code null}, not to a refusal.</b>
     * {@link ProjectStore#id} answers the same way {@link ProjectStore#find}
     * does for a workspace-less project: a project nothing has written to yet
     * is a question, not a mistake, and {@code
     * DefinitionResolver.Caller#projectId() == null} degrades to the boot set
     * exactly as the global tier does. The malformed case is left to whatever
     * validates the request's own {@code project} field next — {@link
     * RequestedHome#in}, reached on every path through {@code
     * agents.Runs.start} that has not already thrown — so this factory must
     * not race it with a second, differently-worded refusal for the same
     * blank string.
     *
     * <p><b>Not what a listing or a write resolves through.</b> Those doors
     * have no later check to hand a malformed name to, so {@link #forListing}
     * and {@link #forWrite} refuse a {@link ValidationException} rather than
     * swallowing it.
     *
     * @throws ArchiveUnavailableException if {@link ProjectStore#id} cannot
     *     reach the database — left to propagate, unlike {@link #forListing}
     */
    public static Long lenient(ProjectStore projects, String project) {
        if (project == null || project.isBlank()) {
            return null;
        }
        try {
            return projects.id(project);
        } catch (ValidationException malformed) {
            return null;
        }
    }

    /**
     * {@link #lenient}, from a {@link Home} rather than the raw field a
     * request carried — what {@code agents.Callers.callerForConversation}
     * needs, since a conversation's project arrives as a {@code Home} and
     * never as a string this door has to validate itself: {@code
     * ConversationStore} already refused a malformed one, at the door that
     * wrote the row.
     */
    public static Long of(ProjectStore projects, Home home) {
        return home.isGlobal() ? null : lenient(projects, home.project());
    }

    /**
     * {@link #lenient}'s twin for a listing, which has no later check
     * downstream of it to catch what {@link #lenient} lets past.
     *
     * <p><b>A malformed name is refused here, not swallowed.</b> A run can
     * afford to answer {@code null} and let {@link RequestedHome#in} give the
     * real refusal a few lines later; a listing never reaches a second check,
     * so answering {@code null} here would silently hand back the boot set to
     * a caller who mistyped a project and never learn why.
     *
     * <p><b>A database outage degrades to the boot set instead of refusing.</b>
     * See this class's own javadoc for why, and for why the outage is not
     * lost even so.
     */
    public static Long forListing(ProjectStore projects, String project) {
        if (project == null || project.isBlank()) {
            return null;
        }
        try {
            return projects.id(project);
        } catch (ValidationException malformed) {
            throw new CallerFault(malformed.getMessage(), malformed);
        } catch (ArchiveUnavailableException outage) {
            return null;
        }
    }

    /**
     * {@link #forListing}'s sibling for a write, and the one factory here
     * where a name with no row is refused rather than folded into the global
     * tier.
     *
     * <h2>Why a write cannot take the read-side rule</h2>
     *
     * <p>{@link ProjectStore#id} answers {@code null} for a name with no row,
     * on its own javadoc's own reasoning: a project nothing has written to yet
     * is a question and not a mistake. {@link #lenient} and {@link
     * #forListing} both let that {@code null} degrade to the global tier —
     * exactly what {@code Caller#projectId() == null} already means — which is
     * the right answer for a <em>read</em>: a run or a listing against a
     * project that turns out to have no row simply sees the boot set, and
     * nothing is lost.
     *
     * <p>It is the wrong answer for a <em>write</em>. A caller who names a
     * project by a typo, or names one that has genuinely never been given a
     * workspace, would have {@code agents.Definitions.define} silently write into
     * {@code global/bots/} instead — every project this server holds, not the
     * one the caller named. That is the invisible-fact failure this whole
     * design fights, discovered later by somebody else looking at an agent
     * that changed for a reason nothing on their screen explains. So a name
     * with no row is refused here, by name, before {@code DefinitionWriter} is
     * ever asked to write anything.
     *
     * <p>A database this server cannot reach is left to propagate as {@link
     * ArchiveUnavailableException} rather than caught and degraded to global,
     * unlike {@link #forListing}. "No row" and "the database did not answer"
     * are indistinguishable from one failed query, and a write must not
     * proceed on that uncertainty — the outage-degrades-to-empty reasoning
     * {@link #forListing} gives for a listing's first screen does not apply to
     * an endpoint that is about to write a file. {@code
     * ApiExceptionHandler.archiveUnavailable} answers 503 and nothing is
     * written.
     *
     * @throws CallerFault if {@code project} is malformed — {@link
     *     ProjectStore#id}'s own {@link ValidationException} — or names no row
     *     at all
     */
    public static Long forWrite(ProjectStore projects, String project) {
        if (project == null || project.isBlank()) {
            return null;
        }
        Long id;
        try {
            id = projects.id(project);
        } catch (ValidationException malformed) {
            throw new CallerFault(malformed.getMessage(), malformed);
        }
        if (id == null) {
            throw new CallerFault(
                    "no project called '" + project + "' exists yet, so there is no project tier"
                            + " to write into. Create the project first, or write to the global"
                            + " tier instead by leaving 'project' out of the request. Refused"
                            + " rather than silently written to the global tier, which is what a"
                            + " project name with no row would otherwise fall back to");
        }
        return id;
    }
}
