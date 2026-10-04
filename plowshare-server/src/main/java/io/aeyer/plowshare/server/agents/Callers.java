package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.requests.RequestedAgent;
import io.aeyer.plowshare.server.requests.RequestedHome;
import io.aeyer.plowshare.server.requests.RequestedProjectId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Which caller a request is, for {@link DefinitionResolver}, and which agent that caller may run —
 * above {@link ProjectStore} and {@link Turn} and below {@code api.AgentController}.
 *
 * <h2>The package rule, applied to a class whose collaborators are elsewhere</h2>
 *
 * <p>A {@link DefinitionResolver.Caller} is an agents concept — {@code DefinitionResolver} owns the
 * type, resolves against it and is the one thing every method here ultimately serves — so this
 * class lives in {@code agents} even though {@link #callerFor} and {@link #callerForConversation}
 * both read project rows to build one. The rule is {@code archive.Projects}' own, stated first
 * there: a moved helper goes where its <em>subject</em> is, not where its collaborators are.
 *
 * <h2>{@link #callerFor} and {@link #callerForConversation} are two doors and not one, on {@code
 * archive.Conversations}' shape</h2>
 *
 * <p>Both build a {@link DefinitionResolver.Caller}, and neither is the other with a parameter
 * swapped in. {@link #callerFor} translates a request's own {@code project} field — a name that may
 * be blank, malformed, or simply unwritten — through {@link RequestedProjectId#lenient}. {@link
 * #callerForConversation} never touches that field: a conversation's project is fixed at the moment
 * it was opened, so the caller for a turn spoken into one is the conversation's own {@link Home},
 * read fresh through {@link Turn#homeOf} and translated through {@link RequestedProjectId#of} — the
 * same home {@link Turn#speak} would run the turn in, and read here so that the agent a turn names
 * is judged against it before anything else in the request is even looked at.
 *
 * <h2>{@link #requireAgent}'s enumeration is not the set the name is looked up in</h2>
 *
 * <p>The lookup is against {@code caller}'s own resolved registry, which for a submission carrying
 * a session includes that client's own {@code .plowshare/} — a client-local bot is nameable and
 * starts. The list a refusal offers, though, is resolved for the <em>same caller without its
 * session</em> — exactly the set {@code GET /v1/agents} answers for that project — so that naming
 * an unknown agent alongside any live session never enumerates that session's local definitions in
 * the 400. The enumeration is a {@code Supplier} so the second resolution it costs is paid only by
 * a request that is already being refused.
 *
 * <h2>An edge that used to be a leak: {@code agents} → {@code requests}</h2>
 *
 * <p>This class imports {@link RequestedAgent} and {@link RequestedProjectId} from methods called
 * on every run and every listing. When this class was built, both still lived in {@code api}, so
 * the import was {@code agents} reaching up into the HTTP surface — the first thing to cross that
 * boundary since a configuration type read at boot. That leak was accepted rather than avoided:
 * both types are parse-and-refuse helpers with nowhere else to live, by the same subject rule this
 * class's own javadoc argues from ({@code archive.Projects}' precedent), and duplicating the parse
 * into {@code agents} instead of importing it would have been the worse edge, one class doing the
 * same parse two ways.
 *
 * <p>Moving both types to {@code requests} closed that leak rather than merely renaming it: {@code
 * requests} carries no HTTP surface of its own — see that package's own javadoc — so this class
 * importing from it is the reuse the package exists to provide, not a boundary crossing this class
 * is asked to tolerate. The argument above for accepting the edge is obsolete, not restated under a
 * new name — there is no leak left here to argue for.
 *
 * <h2>{@link #withheldFrom} is filed here on precedent, not on subject</h2>
 *
 * <p>Every other method on this class earns its place by the rule above: its subject is a {@link
 * DefinitionResolver.Caller}, an agents concept. {@link #withheldFrom} does not — it reads {@link
 * AgentRegistry#withheldEdges} and {@link AgentRegistry#withheldTools}, and {@code AgentRegistry}
 * is its subject, not this class. It is here because it left {@code AgentController} in the same
 * move that built this class, not because this is where it belongs. Named as a known imperfection
 * in the filing rather than left to look deliberate: a future pass that gives {@code AgentRegistry}
 * a home for its own read-side helpers should take this one with it.
 */
@Service
public final class Callers {

  private io.aeyer.plowshare.server.archive.ConversationStore conversations;
  private io.aeyer.plowshare.server.session.SessionRegistry sessions;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void useOwners(
      io.aeyer.plowshare.server.archive.ConversationStore conversations,
      io.aeyer.plowshare.server.session.SessionRegistry sessions) {
    this.conversations = conversations;
    this.sessions = sessions;
  }

  private final CallerAccess access;
  private final DefinitionResolver resolver;
  private final ProjectStore projects;
  private final Turn turns;

  public Callers(
      DefinitionResolver resolver, ProjectStore projects, Turn turns, CallerAccess access) {
    this.access = access;
    this.resolver = resolver;
    this.projects = projects;
    this.turns = turns;
  }

  /** The conversation's own home, for execution and prompt inspection alike. */
  public io.aeyer.plowshare.protocol.Home homeOfConversation(String conversation) {
    return turns.homeOf(conversation);
  }

  public void requireSession(String session, String handle) {
    access.requireSession(session, handle);
  }

  public void requireWork(String project, String handle) {
    access.requireWork(RequestedHome.in(project).project(), handle);
  }

  public void requireConversationWork(String conversation, String handle) {
    requireConversationProject(conversation, handle);
    access.requireWork(turns.homeOf(conversation).project(), handle);
  }

  public void requireProject(String project, String handle) {
    access.requireProject(RequestedHome.in(project).project(), handle);
  }

  public void requireConversationProject(String conversation, String handle) {
    Home home = turns.homeOf(conversation);
    if (home.isGlobal())
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "Global holds shared setup and resources; conversations run in Personal or a project");
    access.requireProject(home.project(), handle);
  }

  /**
   * The caller {@link DefinitionResolver#forCaller} resolves for a plain run: the project this
   * request names, translated to the row's own id through {@link RequestedProjectId#lenient}, and
   * the session it named — already validated by {@code requests.RequestedSession} before this is
   * called.
   *
   * <p><b>A project name with no row resolves to {@code null}</b>, not to a refusal — see {@link
   * RequestedProjectId#lenient}'s own javadoc. The blank or malformed case is left to whatever
   * validates the request's own {@code project} field next — {@code requests.RequestedHome.in},
   * reached on every path through {@link Runs#start} that has not already thrown — so this method
   * must not race it with a second, differently worded refusal for the same blank string.
   *
   * <p><b>Not what {@code GET /v1/agents} resolves through.</b> That door has no later check to
   * hand a malformed name to, so it resolves its own project id through {@link
   * RequestedProjectId#forListing} and refuses a malformed name outright rather than swallowing it.
   */
  public DefinitionResolver.Caller callerFor(String project, String session) {
    return callerFor(
        project,
        session,
        sessions == null || session == null ? null : sessions.accountOf(session).orElse(null));
  }

  public DefinitionResolver.Caller callerFor(String project, String session, String handle) {
    return new DefinitionResolver.Caller(
        RequestedProjectId.lenient(projects, project), session, handle);
  }

  /**
   * The caller for a turn spoken into {@code conversation}: the conversation's own home, never a
   * request's {@code project} field, which {@link Runs#start} refuses outright when a conversation
   * is also named.
   *
   * <p><b>Read before a turn is ever spoken</b>, because the agent a turn names has to be judged
   * against the conversation's own resolved set before anything else in the body is even looked at
   * — the same ordering a plain submission already gets. A conversation this id does not name is
   * still discovered here and not later: {@link Turn#homeOf} throws the identical {@code
   * ArchiveException} {@link Turn#speak} would have, merely sooner.
   */
  public DefinitionResolver.Caller callerForConversation(String conversation, String session) {
    return new DefinitionResolver.Caller(
        RequestedProjectId.of(projects, turns.homeOf(conversation)),
        session,
        conversations == null ? null : conversations.ownerOf(conversation).orElse(null));
  }

  /**
   * The agent this run names, or the refusal that lists the ones that exist — against {@code
   * caller}'s own resolved set, not the server-wide boot set. See this class's own javadoc for why
   * the set a refusal lists is not the set the name is looked up in.
   *
   * <p>Delegates to {@link RequestedAgent#toRun(AgentRegistry, java.util.function.Supplier,
   * String)}. The whole of {@link RequestedAgent} now lives in {@code requests}, not {@code api} —
   * {@code ConversationController} still calls its sibling overload, from {@code api}, into the
   * same package this class reaches from below it.
   */
  public AgentDefinition requireAgent(String name, DefinitionResolver.Caller caller) {
    return RequestedAgent.toRun(
        resolver.forCaller(caller),
        () -> resolver.forCaller(caller.withoutSession()).exportedNames(),
        name);
  }

  /**
   * The agent a read names — a price, a projection — against {@code caller}'s own resolved set,
   * exactly as {@link #requireAgent} looks one up, and without {@code exported}'s check, which
   * gates running and not reading.
   */
  public AgentDefinition readAgent(String name, DefinitionResolver.Caller caller) {
    return RequestedAgent.toRead(
        resolver.forCaller(caller),
        () -> resolver.forCaller(caller.withoutSession()).exportedNames(),
        name);
  }

  /**
   * Everything this server took away from one served agent, as sentences.
   *
   * <p>Read out of the registry rather than kept beside it, so a screen and the boot log cannot
   * come to disagree about what was dropped.
   *
   * <p><b>Routes and grant items land in one list, and that is the right seam.</b> Both say "this
   * agent is running, and something it declared is not"; the distinction a screen must not lose is
   * that from an agent that is <em>not</em> running, and {@code api.AgentView}'s own {@code served}
   * carries it. Splitting these into two fields would make a console pick between renderings of one
   * sentence.
   *
   * <p>Routes first, then items, so a row reads in the order the loader decided them and does not
   * reshuffle when one kind is added.
   */
  public List<String> withheldFrom(AgentRegistry registry, String name) {
    List<String> reasons = new ArrayList<>();
    for (Map.Entry<String, String> edge : registry.withheldEdges().entrySet()) {
      if (edge.getKey().startsWith(name + " -> ")) {
        reasons.add(edge.getKey() + ": " + edge.getValue());
      }
    }
    for (Map.Entry<String, String> tool : registry.withheldTools().entrySet()) {
      if (tool.getKey().startsWith(name + ": ")) {
        reasons.add(tool.getKey() + ": " + tool.getValue());
      }
    }
    return reasons;
  }
}
