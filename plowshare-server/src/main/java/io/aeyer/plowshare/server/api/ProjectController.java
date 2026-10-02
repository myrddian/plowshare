package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.archive.Projects;
import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.requests.RequestedPaths;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import java.nio.file.Path;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Managing a project: name its workspace, lend it further directories and take
 * them back, move that workspace, move the project itself, drop the lot, list
 * them all.
 *
 * <h2>Managed at runtime, and never in configuration</h2>
 *
 * <p>The spec's sentence is that <b>a server whose project list requires a
 * restart is not a server</b>. Projects are the unit an operator manages, the
 * way a Claude project groups chats, so this exists for the same reason {@code
 * AgentController} does: the alternative is a YAML key and a redeploy every time
 * somebody points a project at a different checkout.
 *
 * <h2>Why no agent can reach any of this</h2>
 *
 * <p><b>Structurally, and not by a check in this file.</b> The six tools that
 * call these endpoints — {@code project_define}, {@code project_lend}, {@code
 * project_unlend}, {@code project_workspace_set}, {@code project_move}, {@code
 * project_forget} — live on the client's MCP
 * surface, where the caller is a person deciding what their own server exposes.
 * {@code JobRuntime.knownTools()} never names them, so the tool is never built
 * and never offered: an agent that declared one is not denied at run time, it is
 * <b>never handed the schema at all</b>. {@code AgentRegistry} takes the name
 * out of the definition at boot and says so, naming the file and this paragraph's
 * argument; a definition naming one on an agent this server's code depends on
 * still takes the boot with it.
 *
 * <p>That is worth the mechanism rather than a guard, because the two things an
 * agent could do with this are exactly the two the mandatory exclusions exist to
 * stop. It could point its own project's workspace at the directory holding the
 * model API key — the one rule this repository keeps without exceptions — and it
 * could point it at the data directory, where writing a definition into
 * {@code global/agents} or {@code global/bots} grants itself any tool at the
 * next boot. <b>Lending is the same rule and not a milder
 * one</b>: {@link #lend} extends the leash without disturbing the workspace, so
 * an agent holding it could add {@code $HOME} to what it reads and leave every
 * sentence an operator would look at unchanged. It is withheld beside the other
 * five, and {@code no_agent_tool_can_lend_a_project_a_directory} is what says
 * the surface never grew one. A run-time denial protects neither, because
 * an agent that can call a tool can call it in the run where nobody is looking.
 *
 * <p><b>{@code GET /v1/projects} is unreachable for a different reason, and
 * deliberately.</b> No MCP tool calls it at all — it is reached by the console
 * over HTTP and by nothing else — so the argument above, which rests on a tool
 * name that {@code JobRuntime.knownTools()} declines to bind, does not apply to
 * it and is not what protects it. The missing tool is the protection: this
 * listing hands out every project's workspace and every path fenced off around
 * it, which is a map of the server's disk and of where its secrets are not, and
 * an agent holding it would learn in one call what the three verbs above are
 * withheld to stop it learning at all.
 *
 * <p>{@code POST /v1/proposals/reconsider} is held to the same rule from the
 * other direction and for a different reason, recorded in {@code
 * ProposalController}: it is the one door to the escape hatch, and an agent that
 * could open it could undo its own convergence.
 *
 * <h2>Six verbs, and a listing</h2>
 *
 * <p><b>{@code lend} and {@code unlend} are the two that arrived last, and they
 * are the reason {@code define} is not enough.</b> {@code 809338d} made a
 * dot-prefixed path component unreachable unless a root names it and said the
 * cost was recoverable by adding {@code .github} as an explicit root — which, on
 * a server project, there was no verb for. Doing it through {@code define} would
 * mean reading the workspace and the exclusions back and posting all three,
 * which is {@link #setWorkspace}'s argument one column along: a caller that
 * forgets half silently drops it.
 *
 * <p><b>{@code move} is not a workspace operation at all.</b>
 * The other three answer about a project's leash; {@link #move} answers about its
 * <em>identity</em>, which since V14 is one row and one column with every memory
 * and every conversation following untouched. It is the one verb that works on a
 * project with no workspace, because such a project still has an archive.
 *
 * <p>This file used to argue there was deliberately no {@code GET /v1/projects},
 * on {@code AgentController}'s grounds for removing the same endpoint for jobs:
 * no spec named it, no client tool called it, and it would have existed only
 * because a store method happened to be public. <b>That argument's own terms are
 * what has changed, not its principle.</b> The console has a projects screen,
 * and it lists; the three verbs each answer with the leash they just set, which
 * tells a person nothing about the projects they set last week. The rule was
 * "add one when something wants it", and something does.
 */
@RestController
public class ProjectController {

    private final ProjectStore projects;

    /**
     * The presence rule {@link #move} refuses under, built from the registry
     * that says which live session roots which project.
     *
     * <p>Built here rather than injected, because {@link Projects} is a thin,
     * stateless wrapper around the one thing that carries any state — the
     * {@link PresenceRegistry} singleton {@code FileChannelConfig} wires — and
     * this constructor is kept taking that registry directly rather than a
     * {@link Projects} of its own so that a caller holding a registry, such as
     * this class's own tests, need not also construct the wrapper.
     *
     * <p>Required rather than optional, on {@code AgentsConfig.runProviders}'
     * reasoning for the same bean: {@code FileChannelConfig} is unconditional in
     * this module, so a Plowshare with no registry is not a deployment anybody
     * has — and a null one would silently let every move through, which is the
     * behaviour this parameter exists to stop and which produces no error
     * anywhere.
     */
    private final Projects rules;

    public ProjectController(ProjectStore projects, PresenceRegistry presences) {
        this.projects = projects;
        this.rules = new Projects(presences);
    }

    /**
     * {@code GET /v1/projects} — every leash that has been set.
     *
     * <p><b>Each project's {@code exclusions} is its effective list</b>, asked of
     * {@link ProjectStore#effectiveExclusions(ProjectRecord)} per row, for the
     * reason {@link ProjectView} states: a row carries only what was configured,
     * and the paths no project may override are added on top. A console that
     * listed rows would show an operator a shorter leash than the one they have,
     * and this is the screen they read to learn exactly that.
     *
     * <p>Unfiltered and unpaged. A person has as many projects as they have made,
     * and a limit here would be a number invented rather than measured. Nor is
     * there a {@code ?project=} the way {@code ProposalController.waiting} has
     * one: a project is the thing an operator chooses <em>between</em> on this
     * screen, so filtering this listing by project would answer a question
     * nobody is here to ask.
     *
     * <p><b>Each row also names which machine roots it</b>, asked of {@link
     * ProjectStore#rootedElsewhere} per project — {@code null} when this server
     * holds the files or nothing does. Ruling 5 keeps that query on this
     * listing alone: {@link #listView} rather than the shared {@link #view},
     * because {@code define}, {@code lend}, {@code unlend} and {@code
     * setWorkspace} each already answer with the row they just wrote and have
     * no reason to ask a fifth question about it.
     */
    @GetMapping("/v1/projects")
    public ResponseEntity<List<ProjectView>> list() {
        return ResponseEntity.ok(projects.all().stream().map(this::listView).toList());
    }

    /**
     * {@code POST /v1/projects} — name a project's workspace and its exclusions,
     * replacing whatever it had.
     *
     * <p>An upsert, which {@code ProjectStore.define} argues: defining a project
     * that already exists is one write rather than a forget and a define, so
     * there is no instant at which the project has no leash at all.
     */
    @PostMapping("/v1/projects")
    public ResponseEntity<ProjectView> define(@RequestBody DefineProjectRequest request,
            @RequestAttribute(name = AuthFilter.HANDLE_ATTRIBUTE, required = false) String handle) {
        Path workspace = RequestedPaths.workspace(request.workspace());
        // null means none, for both lists. The store refuses a null list on
        // purpose — it will not guess what a caller meant by it — and this is
        // where the wire shape's omitted key is turned into the empty list it
        // does mean. A caller that sends nothing has lent nothing extra and
        // fenced off nothing extra; the mandatory exclusions are added by the
        // store regardless.
        //
        // defineLending and not define, even when `lent` is absent: one call
        // means the empty case and the populated case take the same path, so a
        // fixture exercising either exercises the branch the other uses. An
        // `if` choosing between the two would leave a branch nothing measures.
        return ResponseEntity.ok(view(projects.defineLending(
                request.name(), workspace, RequestedPaths.each(request.lent()),
                RequestedPaths.each(request.exclusions()), handle)));
    }

    /**
     * {@code POST /v1/projects/{name}/lend} — let a project also reach further
     * directories on this server, without disturbing where it is.
     *
     * <p><b>Additive, and it does not touch {@code workspace}.</b> That is the
     * point rather than a convenience: the workspace is the {@code <PATH>} of the
     * project's canonical name, so a verb that lent by rewriting it would rename
     * the project. V30 is where that argument lives.
     *
     * <p><b>A lent directory need not be inside the workspace</b>, and refusing
     * one that is not would turn this into the per-project unhide list that was
     * considered and declined — a narrower feature wearing this one's name. The
     * bound is the mandatory exclusions, which {@code FileAccess.of} applies to a
     * lent root exactly as it applies to a workspace: lending the agents
     * directory, which is one of them, yields a root that covers nothing.
     *
     * <p><b>Lending the directory the server's configuration sits in is the
     * other case and is not that one</b>, which is worth saying here because it
     * is the arrangement an operator is likelier to type. The mandatory
     * exclusion is the configuration <em>file</em>; a directory holding it is a
     * strict ancestor of an exclusion rather than something an exclusion covers,
     * so the root stands and the file below it is refused on longest match.
     * {@code a_mandatory_exclusion_still_beats_a_lent_root} measures both, and
     * either way the fence holds.
     *
     * <p>Answers with the whole leash and not with what was added, so an operator
     * who mistyped one path of three reads the result and sees it.
     */
    @PostMapping("/v1/projects/{name}/lend")
    public ResponseEntity<ProjectView> lend(
            @PathVariable String name, @RequestBody LendRequest request) {
        return ResponseEntity.ok(view(projects.lend(name, RequestedPaths.roots(request.roots()))));
    }

    /**
     * {@code POST /v1/projects/{name}/unlend} — stop lending directories.
     *
     * <p><b>A route of its own rather than a direction flag on {@link #lend}.</b>
     * One endpoint taking a direction would make "lend this and unlend it" a
     * request with no defensible answer, and a caller who filled in the wrong
     * field would do the opposite of what they meant with nothing to say so.
     * {@link #move} draws the same line.
     *
     * <p>The paths are not checked against the disk, and {@code
     * ProjectStore.unlend} argues why: the commonest reason to unlend a directory
     * is that it has gone, and a check would leave such a root permanently stuck
     * in the row.
     *
     * <p>200 with the leash and not 204, unlike {@link #forget}. There is still a
     * leash to describe — that is the whole difference between taking a lent
     * directory back and dropping the project's place — and it is the thing an
     * operator is checking.
     */
    @PostMapping("/v1/projects/{name}/unlend")
    public ResponseEntity<ProjectView> unlend(
            @PathVariable String name, @RequestBody LendRequest request) {
        return ResponseEntity.ok(view(projects.unlend(name, RequestedPaths.roots(request.roots()))));
    }

    /**
     * {@code POST /v1/projects/{name}/workspace} — point an existing project at
     * a different directory.
     *
     * <p><b>Not {@code define} with the old exclusions passed back in.</b> That
     * would be a read and a write with a window between them, and a caller that
     * forgot the read would silently drop every path an operator had fenced off.
     * {@code ProjectStore.moveWorkspace} is one statement and keeps them.
     *
     * <p>A project that has no workspace is a 404 rather than a create: {@code
     * define} upserts because naming and moving are the same act when you write
     * the whole definition, and here they are not. A mistyped name that quietly
     * created would leave a project nobody meant to define holding a real
     * directory.
     */
    @PostMapping("/v1/projects/{name}/workspace")
    public ResponseEntity<ProjectView> setWorkspace(
            @PathVariable String name, @RequestBody SetWorkspaceRequest request) {
        return ResponseEntity.ok(
                view(projects.moveWorkspace(name, RequestedPaths.workspace(request.workspace()))));
    }

    /**
     * {@code POST /v1/projects/{name}/move} — give a project a different name,
     * and its whole archive with it.
     *
     * <p><b>This is what moving a project between machines is.</b> A project's
     * canonical name is {@code <MACHINE>/<PATH>/<PROJ_NAME>}, so moving it
     * changes the name — and since V14 that is one row and one column, because
     * {@code memories} and {@code conversations} reference the surrogate id.
     * {@code ProjectStore.rename} carries the argument and the two refusals it
     * makes: a project no row is called is a 404, and a name another project
     * already holds is a 409.
     *
     * <p>204 and no body, on {@link #forget}'s reasoning: nothing about the
     * project is new except what it is called, so a body would describe a change
     * that did not happen. The sentence a person reads is {@code project_move}'s.
     *
     * <h2>Not while a live session roots it, at either end</h2>
     *
     * <p><b>Decided rather than defaulted, and this is the record of it.</b> A
     * presence declares {@code project=} on its file channel and {@link
     * PresenceRegistry} keys on that name, so a rename that went through under a
     * live presence would leave three things disagreeing: the session would root
     * a name no row holds, every run in the new name would find no presence and
     * get an {@code AbsentPresence}, and the client would re-declare the <em>old</em>
     * name at its next reconnect — undoing the move for the registry and for
     * nothing else. The registry is runtime state and the row is a database row;
     * there is no transaction that spans them and nothing that could reconcile
     * them afterwards.
     *
     * <p><b>The destination is checked as well as the source</b>, and that half
     * is not the uniqueness constraint's: a project a live session roots need
     * have no {@code projects} row at all — {@code define} can only name a
     * directory on the server's own disk, so a project whose files are on a
     * laptop typically has none — so moving another project onto its name would
     * conflict with nothing in the database and would hand one project's whole
     * archive to another machine's files. That is precisely the silent merge the
     * unique constraint exists to prevent, arriving by the one door it does not
     * cover.
     *
     * <p><b>It is a courtesy check and not an invariant, which is worth saying
     * rather than leaving to be discovered.</b> A presence can be declared
     * between this check and the write; nothing here can hold a lock across two
     * authorities. What the check buys is that the ordinary case — an operator
     * moving a project while the client that serves it is running — is refused
     * with a sentence naming the machine to close, instead of succeeding into a
     * state nobody can read. Losing the race leaves exactly the state {@code
     * AbsentPresence} now describes in words, which is recoverable by closing the
     * client.
     */
    @PostMapping("/v1/projects/{name}/move")
    public ResponseEntity<Void> move(
            @PathVariable String name, @RequestBody MoveProjectRequest request) {

        String to = RequestedPaths.to(request.to());
        rules.refuseIfRooted(name, "moved");
        rules.refuseIfRooted(to, "moved onto");
        projects.rename(name, to);
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code DELETE /v1/projects/{name}} — drop a project's workspace.
     *
     * <p>Its memories are untouched: there is no foreign key from {@code
     * memories} to {@code projects}, and a project that loses its workspace
     * keeps everything it has ever remembered. Its jobs go back to having no
     * local file access, which every file tool then says in words.
     *
     * <p><b>The lent directories go with it.</b> {@code ProjectStore.forget}
     * argues that at length; the short of it is that a stale exclusion left
     * behind would fence off more than an operator meant, while a stale lent root
     * would <em>reach</em> more — handed to whoever defines the name next,
     * without either of them ever being told it was there.
     *
     * <p>204 and no body, because there is no longer a leash to describe. A
     * project that had none is a 404 rather than silence — {@code
     * ProjectStore.forget} refuses it so that a mistyped name cannot read as a
     * workspace successfully removed, which is the one outcome an operator would
     * not go back and check.
     */
    @DeleteMapping("/v1/projects/{name}")
    public ResponseEntity<Void> forget(@PathVariable String name) {
        projects.forget(name);
        return ResponseEntity.noContent().build();
    }

    private ProjectView view(ProjectRecord project) {
        // effectiveExclusions and never project.exclusions(): ProjectView says
        // why at length, and the overload taking the row is what stops this
        // reading the table a second time.
        return ProjectView.of(project, projects.effectiveExclusions(project));
    }

    /**
     * The row {@link #list} renders, which is {@link #view} plus which machine
     * — if any — currently roots the project. Kept apart from {@link #view}
     * rather than folded into it, per ruling 5: the four verbs above answer
     * with the row they just wrote, and giving all of them a {@code
     * rootedElsewhere} lookup they never asked for would be one query per
     * write for an answer nothing reads.
     */
    private ProjectView listView(ProjectRecord project) {
        return ProjectView.of(project, projects.effectiveExclusions(project),
                projects.rootedElsewhere(project.name()).orElse(null));
    }
}
