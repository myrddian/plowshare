package io.aeyer.plowshare.server.agents.digests;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.requests.RequestedHome;
import io.aeyer.plowshare.server.requests.RequestedMemoryQuestion;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Asking the archive's digests a question, and starting the pass that builds
 * them: the whole of both decisions, above {@link Navigator}, {@link Digester}
 * and {@link JobStore} and below whatever surface was asked.
 *
 * <h2>Why this is a class and not eight lines in a controller</h2>
 *
 * <p>{@code agents.curator.Passes}' argument, met a second time and with more
 * in the way: {@code api.DigestController} held both of these inline, a
 * WebSocket frame reaching the same two capabilities calls the service and
 * never the controller, and <b>most of what was in that controller refuses
 * nothing</b> — so a handler author looking for the checks to copy would have
 * found one blank-question throw and missed everything below.
 *
 * <h2>The three silent rules, which are the reason this file exists</h2>
 *
 * <p><b>Two budgets nobody sends.</b> A navigation spends {@link
 * MemoryProperties#getNavigationBudget()} and a digest pass spends {@link
 * MemoryProperties#getDigestBudget()}; neither is in the request, neither is in
 * the answer, and a second surface that reached for a number of its own would
 * produce a differently-billed call with nothing to notice it by. Both are read
 * here, where the decision is.
 *
 * <p><b>An ending nobody chose.</b> A pass that stops classifies itself from
 * what the digester said and what the budget has left — {@code ANSWERED} when
 * something was built, {@code CALL_BUDGET} when the allowance is gone, {@code
 * STUCK} otherwise, and {@code CANCELLED} over all of it. That classification
 * is what an operator reads to decide whether to raise a budget or go and look
 * at the archive, and it appears nowhere in the request: a surface that filed an
 * exhausted pass as merely stuck would disagree with its endpoint about the one
 * field a caller uses, in an answer that arrives minutes later on a poll.
 *
 * <p><b>An ordering that is a status.</b> The tier is resolved <em>here</em>,
 * on the calling thread, before {@link JobStore#submit} ever sees the closure —
 * so a blank project is a refusal the caller gets, rather than a 202 and a job
 * that fails quietly on a worker thread. {@code DigestControllerTest} has
 * pinned that as a status since before this class existed — its third test
 * says so in its name — and {@code DigestsTest} pins it here as the ordering
 * it actually is.
 *
 * <h2>What did not move, and is not an oversight</h2>
 *
 * <p>{@link #start} takes no question. The endpoint's body has one — the two
 * verbs share a request record — and {@code digest} has never read it: a pass
 * folds a whole tier rather than answering anything. Passing it here would
 * invent a parameter for a value nothing consumes, and dropping it silently is
 * what the endpoint already does.
 */
@Service
public final class Digests {

    /**
     * The name a digest pass is filed under, for the same reason {@code
     * Curator.BY} exists: there is no {@code memory_digester.md}, so there is no
     * definition whose spelling could differ from the caller's.
     */
    public static final String AGENT = "memory_digester";

    /** What a digester says when it built something, and the one thing that
     *  tells {@code ANSWERED} from {@code STUCK}. */
    private static final String BUILT = "Built";

    private final Navigator navigator;
    private final Digester digester;
    private final MemoryProperties properties;
    private final JobStore jobs;

    /**
     * @param navigator what answers a question out of the digests already built
     * @param digester what builds them
     * @param properties where both allowances come from — see this class's own
     *     javadoc for why they are read here and not at a surface
     * @param jobs where a pass is submitted, which is what makes it pollable
     *     and cancellable through the ordinary job verbs
     */
    public Digests(Navigator navigator, Digester digester, MemoryProperties properties,
            JobStore jobs) {
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.digester = Objects.requireNonNull(digester, "digester");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
    }

    /**
     * A pass that started: the id it was given, and the name it runs under.
     *
     * <p>{@code Passes.Started}'s shape, for the same reason — both surfaces
     * answer the same two fields, and a pass is a job in every way a caller can
     * see.
     *
     * @param id the job id, pollable at once
     * @param agent who the work is filed under, always {@link #AGENT}
     */
    public record Started(String id, String agent) {
    }

    /**
     * Answer one question out of one tier's digests, now, on the calling
     * thread.
     *
     * <p>Synchronous although it calls a model, which is the endpoint's own
     * shape and not this class's decision: a navigation is a handful of calls
     * bounded by the configured allowance, and a caller asking a question wants
     * the answer rather than a handle. There is no cancellation to offer, so
     * the navigator is handed one that never fires.
     *
     * <p><b>The question is read before the tier</b>, which is the endpoint's
     * own order kept: a body naming neither is told the thing it has to add.
     *
     * @param project the tier to answer from, or null for {@link Home#global()}
     * @param question what to ask of it
     * @throws CallerFault if no question is named, or the tier is named blank
     */
    public Navigator.Result navigate(String project, String question) {
        String asked = RequestedMemoryQuestion.navigated(question);
        Home home = RequestedHome.in(project);
        return navigator.navigate(
                home, asked, Budget.of(properties.getNavigationBudget()), () -> false);
    }

    /**
     * Start a digest pass over one tier, and answer with the handle at once.
     *
     * <p>Submitted through {@link JobStore#submit(String,
     * java.util.function.Function)} — the overload that files no project on the
     * job's own row, which is the endpoint's own call and is deliberately
     * unlike {@code Passes}': the tier is inside the closure either way, and
     * changing which row is written would change what a job listing shows.
     *
     * @param project the tier to fold, or null for {@link Home#global()}
     * @throws CallerFault if the tier is named blank — raised here, before the
     *     closure is handed over, which is the ordering this class's javadoc
     *     argues
     */
    public Started start(String project) {
        Home home = RequestedHome.in(project);
        String id = jobs.submit(AGENT, cancelled -> {
            Budget budget = Budget.of(properties.getDigestBudget());
            String text = digester.pass(home, budget, cancelled);
            return new Outcome(ending(text, budget, cancelled.getAsBoolean()),
                    text, budget.spent(), budget.spent(), "");
        });
        return new Started(id, AGENT);
    }

    /**
     * How a pass ended, from what it said and what it had left.
     *
     * <p>Cancellation first, because a pass asked to stop stopped for that
     * reason whatever it managed to build on the way; then what was built; then
     * the allowance, which is the difference between "raise the budget" and
     * "there was nothing to fold".
     */
    private static Outcome.Ending ending(String text, Budget budget, boolean cancelled) {
        if (cancelled) {
            return Outcome.Ending.CANCELLED;
        }
        if (text.startsWith(BUILT)) {
            return Outcome.Ending.ANSWERED;
        }
        return budget.remaining() == 0 ? Outcome.Ending.CALL_BUDGET : Outcome.Ending.STUCK;
    }
}
