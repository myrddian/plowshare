package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.requests.RequestedDefinition;
import io.aeyer.plowshare.server.requests.RequestedProjectId;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Writing one definition and answering with what it resolves to: the whole
 * decision, above {@link DefinitionWriter} and {@link DefinitionResolver} and
 * below whatever surface was asked.
 *
 * <h2>Why this is a class and not a dozen lines in a controller</h2>
 *
 * <p>It was a dozen lines in {@code api.AgentController.define}, and a
 * WebSocket frame reaching the same capability calls the service and never the
 * controller — so every rule left in the handler is a rule the second surface
 * silently does not have. What moved here is <b>the decision</b>: the order
 * the body is read in, the write, the cache invalidation that runs only when
 * something landed, and the one branch below that changes the shape of the
 * answer rather than its status.
 *
 * <h2>The global tier, which is the dangerous half</h2>
 *
 * <p>A write with no project <b>does not resolve at all</b>. {@link
 * DefinitionResolver}'s own top javadoc is explicit that the boot set is read
 * once and never rebuilt while this process runs; only project tiers are. So a
 * definition written to {@code global/bots/} lands on disk correctly and this
 * process cannot serve it until it restarts, and asking the resolver would
 * answer with what was already believed — <b>stale data that looks
 * current</b>. There is no status that differs and no exception to catch: a
 * surface that re-derived this by calling the writer and then resolving
 * normally would be wrong silently, in the one direction nothing fails in.
 *
 * <p>So the result type refuses to carry that mistake. {@link Defined} holds
 * <em>either</em> a resolved definition <em>or</em> a reason a restart is
 * needed, never both and never neither, and its own constructor enforces that.
 * A surface rendering a global write has no resolved view available to render
 * — not because it was careful, but because there is not one.
 *
 * <h2>Filed on its own subject</h2>
 *
 * <p>{@link DefinitionWriter} was the obvious home and is the wrong one. Its
 * subject is the write — one file, validated as it would be loaded and landed
 * atomically or not at all — and its class javadoc is emphatic that nothing in
 * this package hands an agent a reference to it. Teaching it to resolve what
 * it wrote would give it a second subject and a second collaborator for the
 * sake of one caller's answer. The subject here is a definition being defined,
 * write and read-back together, so the class is {@code Definitions} and the
 * writer stays the thing it already answers for. The four {@code
 * *Definitions} classes beside it are all {@link DefinitionSource}
 * implementations — read paths, and a different subject again.
 */
@Service
public final class Definitions {

    private final DefinitionWriter writer;
    private final DefinitionResolver resolver;
    private final ProjectStore projects;
    private final Callers callers;

    public Definitions(
            DefinitionWriter writer, DefinitionResolver resolver, ProjectStore projects,
            Callers callers) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.callers = Objects.requireNonNull(callers, "callers");
    }

    /**
     * What one caller asked to define, in fields and never in a wire shape.
     *
     * <p>{@code api.DefineAgentRequest} is the HTTP body Jackson binds and it
     * stays on the HTTP surface; this is what that body is parsed into, and
     * what a frame's payload is parsed into as well — {@code requests}' own
     * package rule, applied one level up, exactly as {@link Runs.Ask} applies
     * it.
     *
     * @param project the tier to write into, by name, or {@code null} for the
     *     global one. A name this server holds no row for is refused rather
     *     than folded into global — see {@link RequestedProjectId#forWrite}
     * @param name the definition's own name, which is also its filename stem
     * @param text the whole file, frontmatter and body
     * @param overwrite whether a name already taken may be replaced
     */
    public record Ask(String project, String name, String text, boolean overwrite) {
    }

    /**
     * One definition, written — and either what it resolves to now or why it
     * does not resolve at all.
     *
     * <p><b>Exactly one of {@link #definition} and {@link #restartReason} is
     * present.</b> That is this type's whole point: see the class javadoc. A
     * global write has no {@code definition} to hand back because nothing
     * resolved one, and a surface that wants to show a view for it has to
     * reach {@link #restartReason} and say what is actually true.
     *
     * @param name the definition's own name, as the registry spells it for a
     *     tier that resolved and as the caller wrote it for one that did not
     * @param written where the bytes landed, and whether the file was created
     *     or replaced — the bit a surface turns into {@code 201} or {@code 200}
     * @param definition what this server now serves under {@link #name}, or
     *     {@code null} for a write that cannot be resolved until a restart
     * @param withheld what this server took away from the resolved agent, in
     *     sentences; empty for a write that was not resolved
     * @param restartReason why nothing was resolved, in a sentence naming
     *     where the file landed — or {@code null} for a write that resolved
     */
    public record Defined(
            String name,
            DefinitionWriter.Written written,
            AgentDefinition definition,
            List<String> withheld,
            String restartReason) {

        /** @throws IllegalArgumentException if a caller builds either shape
         *      this type exists to make unrepresentable — a resolved view and
         *      a restart reason at once, or neither; or something withheld
         *      from an agent that was never resolved. */
        public Defined {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(written, "written");
            if ((definition == null) == (restartReason == null)) {
                throw new IllegalArgumentException(
                        "a written definition either resolved or could not be resolved until a"
                                + " restart, and this one claims "
                                + (definition == null ? "neither" : "both"));
            }
            withheld = withheld == null ? List.of() : List.copyOf(withheld);
            // WHAT IS WITHHELD IS READ OUT OF THE RESOLVED REGISTRY, so there
            // is nothing to read for a write that was not resolved: `withheld`
            // is Callers.withheldFrom over the registry `definition` came out
            // of, and the restart branch has no registry to ask. The javadoc
            // said this already and the constructor permitted the opposite --
            // a Defined carrying a restart reason and a list of sentences
            // about what a running agent was denied, which would be a claim
            // about an agent this process does not serve. Enforced rather than
            // documented, for the same reason the branch above is.
            if (restartReason != null && !withheld.isEmpty()) {
                throw new IllegalArgumentException(
                        "nothing was resolved for '" + name + "', so nothing can have been"
                                + " withheld from it: what is withheld is read out of the"
                                + " registry the definition came from, and this write has no"
                                + " definition");
            }
        }

        /** Whether this process can serve what was just written, or whether
         *  only a restart makes it visible. */
        public boolean restartRequired() {
            return restartReason != null;
        }
    }

    /**
     * Write it, or refuse it — and then say honestly whether this process
     * serves it.
     *
     * <p><b>The order below is behaviour and not style.</b> The project name
     * is resolved before either field is read, so a body naming a project this
     * server does not know is told that rather than told it forgot a field,
     * and nothing is written for a name that was a typo. {@code name} is read
     * before {@code text} for the same reason applied one level down. The
     * writer's own ladder — the size ceiling, the filename stem, the parse,
     * the reserved names, the fleet's checks, the collision — runs after all
     * of it and is that class's to order.
     *
     * @param ask what the caller asked for
     * @return where it landed, and either the view it resolves to or the
     *     reason it does not
     * @throws CallerFault for a body this server can name as wrong: an unknown
     *     project, a missing {@code name} or {@code text}, or anything {@link
     *     DefinitionWriter#write} refuses
     * @throws DefinitionAlreadyExistsException if the name is taken and the
     *     caller did not ask to replace it
     */
    public Defined define(Ask ask) {
        Long projectId = RequestedProjectId.forWrite(projects, ask.project());
        RequestedDefinition.name(ask.name());
        RequestedDefinition.text(ask.text());

        DefinitionWriter.Written written =
                writer.write(projectId, ask.name(), ask.text(), ask.overwrite());

        // Only once the write has actually landed: nothing changed on disk on
        // any path that refused, so there is nothing for the cache to have
        // gone stale about. See DefinitionResolver.invalidate's own javadoc for
        // why this is needed at all though the cache is already stamped -- a
        // same-length replacement inside one filesystem timestamp tick is
        // invisible to the stamp, and this is the caller most likely to
        // produce that gap.
        resolver.invalidate(projectId);

        if (projectId == null) {
            // THE GLOBAL TIER, AND THE ONLY CASE THAT REACHES HERE:
            // RequestedProjectId.forWrite has already refused an unknown
            // project name, so nothing else can produce "written, but not
            // found on re-resolution". The resolver is not asked, deliberately
            // -- see this class's own javadoc. Resolving would answer with the
            // boot set this process read at start-up, which cannot contain
            // what was just written and never will until it restarts.
            return new Defined(ask.name(), written, null, List.of(),
                    "written to " + written.origin() + ", but this server's global tier is read"
                            + " once at boot and is not rebuilt while running -- it will be"
                            + " resolvable once this process restarts. See DefinitionResolver's"
                            + " own javadoc, \"the boot set is never rebuilt\"");
        }
        AgentRegistry registry =
                resolver.forCaller(new DefinitionResolver.Caller(projectId, null));
        AgentDefinition definition = registry.get(ask.name());
        return new Defined(definition.name(), written, definition,
                callers.withheldFrom(registry, definition.name()), null);
    }
}
