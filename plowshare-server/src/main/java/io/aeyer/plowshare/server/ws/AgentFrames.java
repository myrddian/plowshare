package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.Definitions;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Limits;
import io.aeyer.plowshare.server.agents.Pictures;
import io.aeyer.plowshare.server.agents.Runs;
import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.agents.curator.Passes;
import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.archive.ProjectStore;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code AgentController}'s area of the frame surface — every {@code agent.*} and {@code job.*}
 * type this server answers, which is all eight of that controller's endpoints.
 *
 * <h2>One constructor, and it is the controller's own</h2>
 *
 * <p>The nine services below are the same beans {@code AgentController} is injected with, in the
 * same order, which is what makes a frame's refusal the endpoint's own refusal rather than a second
 * one shaped like it. That controller's own constructor javadoc argues why the services are
 * injected rather than built: a surface that built its own copy would give this server two
 * instances of each, the scanned bean a frame handler injects and a private one the controller
 * called, and the day any of them gains a cache or a proxy the two surfaces would answer from
 * different objects with nothing failing. Taking the identical list here is that argument kept on
 * the second surface.
 *
 * <h2>Two nouns, because there are two resources</h2>
 *
 * <p>{@code agent.run}, {@code agent.curate}, {@code agent.define} and {@code agent.list} are about
 * what can be started; {@code job.list}, {@code job.status}, {@code job.cancel} and {@code
 * job.limits} are about a run that has been. They share one controller for the reason that class's
 * javadoc gives — an agent is what the run path names — and they do not share a dotted noun,
 * because the noun is what a client is asking about and a client polling a handle is asking about a
 * job. Both halves are this one area's, so the whole of one controller is still one file a reader
 * can open.
 *
 * <p>One of the {@link FrameArea} classes that interface's javadoc describes. A later breadth task
 * adds <b>its own</b> {@code *Frames.java} rather than editing this one.
 */
@Component
public class AgentFrames implements FrameArea {

  private final Watchers watchers;
  private final JobStore jobs;
  private final DefinitionResolver resolver;
  private final ProjectStore projects;
  private final Callers callers;
  private final Runs runs;
  private final Pictures pictures;
  private final Passes passes;
  private final Limits limits;
  private final Definitions definitions;
  private final Conversations rules;

  /**
   * @param jobs the one store a job is listed, read, cancelled and found in
   * @param resolver what answers which agents a caller can see
   * @param projects the store a project name becomes an id through
   * @param callers the service both surfaces build a caller with
   * @param runs the service that decides a submission or an utterance
   * @param pictures how the ids a run names become the bytes it is shown
   * @param passes the service that starts a curator pass
   * @param limits the service that moves a live run's ceilings
   * @param definitions the service that writes one agent definition
   * @param watchers where a token subscription and a conversation follow are recorded
   * @param rules what a follow asks whether its conversation exists of, as its siblings do
   */
  public AgentFrames(
      JobStore jobs,
      DefinitionResolver resolver,
      ProjectStore projects,
      Callers callers,
      Runs runs,
      Pictures pictures,
      Passes passes,
      Limits limits,
      Definitions definitions,
      Watchers watchers,
      Conversations rules) {
    this.watchers = Objects.requireNonNull(watchers, "watchers");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.projects = Objects.requireNonNull(projects, "projects");
    this.callers = Objects.requireNonNull(callers, "callers");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.pictures = Objects.requireNonNull(pictures, "pictures");
    this.passes = Objects.requireNonNull(passes, "passes");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.definitions = Objects.requireNonNull(definitions, "definitions");
    this.rules = Objects.requireNonNull(rules, "rules");
  }

  private io.aeyer.plowshare.server.agents.SkillResolver skills;
  private io.aeyer.plowshare.server.agents.OrchestrationResolver orchestrations;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void useCommandCatalog(
      io.aeyer.plowshare.server.agents.SkillResolver skills,
      org.springframework.beans.factory.ObjectProvider<
              io.aeyer.plowshare.server.agents.OrchestrationResolver>
          orchestrations) {
    this.skills = skills;
    this.orchestrations = orchestrations.getIfAvailable();
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.ofEntries(
        Map.entry(FrameTypes.AGENT_RUN, new AgentRunHandler(runs, pictures)),
        Map.entry(FrameTypes.AGENT_CURATE, new AgentCurateHandler(passes)),
        Map.entry(FrameTypes.AGENT_DEFINE, new AgentDefineHandler(definitions)),
        Map.entry(
            FrameTypes.AGENT_LIST,
            new AgentListHandler(resolver, projects, callers).withCatalog(skills, orchestrations)),
        Map.entry(FrameTypes.JOB_LIST, new JobListHandler(jobs)),
        Map.entry(FrameTypes.JOB_STATUS, new JobStatusHandler(jobs)),
        Map.entry(FrameTypes.JOB_CANCEL, new JobCancelHandler(jobs)),
        Map.entry(FrameTypes.JOB_STREAM, new JobStreamHandler(watchers)),
        Map.entry(FrameTypes.CONVERSATION_FOLLOW, new ConversationFollowHandler(watchers, rules)),
        Map.entry(FrameTypes.JOB_LIMITS, new JobLimitsHandler(limits)));
  }
}
