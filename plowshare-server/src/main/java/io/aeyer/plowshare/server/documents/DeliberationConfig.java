package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.JobRuntime;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the per-document deliberation.
 *
 * <p><b>A third configuration class beside {@link SummariserConfig}, on that
 * class's own argument rather than in spite of it.</b> {@link Deliberation} is
 * the second component that needs both halves — the corpus's rows from {@link
 * DocumentsConfig}, and the runtime, the registry and the compaction from {@code
 * AgentsConfig} — so it cannot live in either without making that one impossible
 * to stand up alone, and both are stood up alone by {@code DocumentsConfigTest}
 * and {@code AgentsConfigTest}.
 *
 * <p><b>Not folded into {@link SummariserConfig} although the dependency set is
 * nearly the same.</b> That class's whole content is one bean and the boot check
 * that keeps {@code span-size} usable, and its name says which. A second bean
 * with a different check under that name would make the file's title a lie about
 * half of what it refuses — and the two refusals are about numbers an operator
 * sets independently, so an operator reading a failure needs to be told which
 * one they broke.
 *
 * <p>Nothing here is conditional and the absent case is handled one layer down:
 * a deployment with no agent directory is a legal running server, so this bean
 * is built, its registry supplier answers null, and {@link Deliberation#ask}
 * reports that this server has nothing to deliberate with.
 */
@Configuration
// Declared here as well as on DocumentsConfig, and the duplication is what makes
// this class what its javadoc claims: a configuration that can be stood up on
// its own. Spring binds one DocumentsProperties either way -- the annotation is
// a registration and not an instantiation -- so a boot with both classes on the
// context is unchanged, and a context with only this one has the numbers it
// reads.
@EnableConfigurationProperties(DocumentsProperties.class)
public class DeliberationConfig {

    /**
     * The ask.
     *
     * <p>The registry arrives as a supplier for {@code Scribe}'s, {@code
     * Curator}'s and {@link SummariserConfig}'s reason exactly: it validates
     * every declared tool name against the tool layer, so one of the two has to
     * be built after the other, and a resolution at bean-creation time would fix
     * the answer for the life of the context — the point being that a registry
     * defined later is picked up.
     *
     * <p>{@code Clock.systemUTC()} and not a {@code Clock} bean, matching {@link
     * DocumentsConfig#ingestService}: the corpus stamps its own rows and nothing
     * on this server injects a clock into a bean. What it stamps here is {@code
     * citations.cited_at}.
     *
     * <p>The allowance is refused here rather than at the first ask, on {@link
     * DocumentsConfig}'s standing rule: a number that cannot work should be
     * refused before anybody has asked a question, naming the key an operator
     * would have to edit.
     */
    @Bean
    public Deliberation deliberation(
            DocumentStore store, RetrievalService retrieval, CitationStore citations,
            JobRuntime runtime, ObjectProvider<AgentRegistry> agents, Compaction compaction,
            DocumentsProperties props) {

        if (props.getAskBudget() < Deliberation.A_PASS) {
            throw new IllegalStateException(
                    "plowshare.documents.ask-budget is " + props.getAskBudget()
                            + "; it must be at least " + Deliberation.A_PASS + ". A pass is a"
                            + " proposer, a critic, a reviewer and a synthesiser, plus one retry"
                            + " of a critic whose JSON did not parse — and that retry is the"
                            + " ordinary failure it exists for rather than an exceptional one,"
                            + " so an allowance below it is an ask that reports CALL_BUDGET the"
                            + " first time a small model answers a JSON question in prose");
        }
        return new Deliberation(store, retrieval, citations, runtime, agents::getIfAvailable,
                Clock.systemUTC(), compaction);
    }
}
