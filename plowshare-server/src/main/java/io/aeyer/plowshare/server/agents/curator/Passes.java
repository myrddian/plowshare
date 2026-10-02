package io.aeyer.plowshare.server.agents.curator;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentsProperties;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import org.springframework.stereotype.Service;

/**
 * Starting one curator pass over one project: the whole decision, in the order
 * it has to be made in, above {@link Curator} and {@link JobStore} and below
 * whatever surface was asked.
 *
 * <h2>Why this is a class and not six lines in a controller</h2>
 *
 * <p>It was six lines in {@code api.AgentController.curate}, and a WebSocket
 * frame reaching the same capability calls the service and never the
 * controller — so every rule left in the handler is a rule the second surface
 * silently does not have. {@code Runs}' own javadoc makes the general
 * argument; what makes this one urgent is that <b>most of what was in the
 * handler refuses nothing</b>, and so would not be found by anyone looking for
 * the checks to copy.
 *
 * <h2>The silent default is the reason this file exists</h2>
 *
 * <p>A pass with no {@code maxModelCalls} takes {@link
 * AgentsProperties#getCuratorBudget()}, and nothing refuses, nothing warns and
 * nothing in the response says which number was used. A second surface that
 * reimplemented this call without reaching <em>that exact source</em> would
 * produce a differently-budgeted pass with no error to notice it by — a pass
 * that stops early, or one that spends what an operator had bounded. So the
 * default is read here, where the decision is, and {@code
 * PassesTest.a_pass_with_no_budget_takes_the_configured_curator_budget} pins
 * it against a property value that is deliberately not the shipped default.
 *
 * <h2>The project refusal stayed a domain rule, and did not go to {@code
 * requests}</h2>
 *
 * <p>Its <em>shape</em> is a null-or-blank check on one field, which is
 * exactly what a {@code Requested*} type is for. Its <em>content</em> is not:
 * the sentence a caller reads is curation's own reasoning — promotion is what
 * puts a project's memory into global, so a pass over global would have
 * nowhere to promote to — and a {@code requests} type could only carry it by
 * taking the explanation as a parameter, which makes the shape type a courier
 * for a domain sentence it cannot justify or maintain. The survey flagged this
 * one as arguable and asked for the choice to be made out loud: it is made
 * here, on the grounds that the message is the whole value of the refusal. A
 * caller told only that a field was missing would reasonably try to guess a
 * value for it.
 *
 * <p><b>{@link Curator#pass} already refuses this too</b>, one layer down,
 * through {@code Home.of} — with a generic sentence about project names and as
 * a plain {@code IllegalArgumentException}, which no surface maps to a status.
 * That is why "delete the check, the callee guards it" was not the move: it
 * would trade a 400 explaining curation for a 500 explaining nothing.
 */
@Service
public final class Passes {

    private final JobStore jobs;
    private final Curator curator;
    private final AgentsProperties props;

    public Passes(JobStore jobs, Curator curator, AgentsProperties props) {
        this.jobs = jobs;
        this.curator = curator;
        this.props = props;
    }

    /**
     * A pass that started: the id it was given, and the name it runs under.
     *
     * <p>{@code Runs.Started}'s shape rather than a bare {@code String},
     * because both surfaces answer the same two fields and a pass is a job in
     * every way a caller can see. The name is always {@link Curator#BY} —
     * there is deliberately no {@code curator.md}, so there is no definition
     * whose spelling could differ from the caller's.
     *
     * @param id the job id, pollable at once
     * @param agent who the work is filed under
     */
    public record Started(String id, String agent) {
    }

    /**
     * Start it, or refuse it.
     *
     * <p><b>The order below is behaviour and not style.</b> The project is
     * read before the allowance is, so a body that names neither is told the
     * thing it has to add rather than corrected about a number it never sent.
     *
     * @param project the project to curate; there is no global pass
     * @param maxModelCalls what the whole pass may spend, or {@code null} to
     *     take the configured curator budget — see this class's own javadoc for
     *     why that default lives here
     * @return the id and the name the pass runs under
     * @throws CallerFault if no project is named, or if the allowance is one
     *     {@link Budget#of} refuses
     */
    public Started start(String project, Integer maxModelCalls) {
        if (project == null || project.isBlank()) {
            throw new CallerFault(
                    "curating needs a project. There is no global pass: promotion is what puts a"
                            + " project's memory into global, so a pass over global would have"
                            + " nowhere to promote to");
        }
        int allowance = maxModelCalls == null ? props.getCuratorBudget() : maxModelCalls;
        // Budget.of raises faults.CallerFault itself for a non-positive
        // allowance -- there used to be a catch at the surface restating it as
        // that surface's own BadRequestException, and it is gone rather than
        // moved: the domain check already happened inside Budget.of.
        Budget budget = Budget.of(allowance);

        // The project on the job's own row as well as inside the pass: a curator
        // pass is a job with no conversation of its own, so the row written by
        // JobStore is the only durable thing that can say which project it went
        // over. See V24__jobs.sql on why that is a reference and not part of the
        // id.
        String id = jobs.submit(Curator.BY, Home.of(project),
                cancelled -> curator.pass(project, budget, cancelled));
        return new Started(id, Curator.BY);
    }
}
