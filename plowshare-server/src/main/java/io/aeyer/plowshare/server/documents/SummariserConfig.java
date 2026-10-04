package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.JobRuntime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the summariser cascade, and <b>it is its own configuration class for a reason worth
 * stating.</b>
 *
 * <p>{@link Summariser} is the one component on this server that needs both halves: the corpus's
 * rows from {@link DocumentsConfig}, and the agent runtime, the registry and the compaction from
 * {@code AgentsConfig}. Put in either of those it would make that class impossible to stand up on
 * its own — and both are stood up on their own, by {@code DocumentsConfigTest} and {@code
 * AgentsConfigTest}, which are how this repository checks that a boot refuses the configurations it
 * should and starts on the ones it should.
 *
 * <p>That is not a testing convenience. A configuration class that can only be built with the whole
 * application behind it is one whose boot checks are only ever exercised by the whole application,
 * and those checks are the difference between an operator reading a message that names a key and an
 * operator reading a stack trace. So the class that spans two packages is a third class, and
 * neither of the two it spans learns about the other.
 *
 * <p><b>Nothing here is conditional and the absent case is handled one layer down.</b> A deployment
 * with no agent directory is a legal running server — {@code AgentsConfig} says so at boot and
 * warns — so this bean is built, its registry supplier answers null, and {@link
 * Summariser#summarise} reports that this server defines no summarisers. {@code
 * DocumentsConfig.ingestService} takes the cascade through an {@code ObjectProvider} for the
 * narrower case where this class itself is not on the context at all.
 */
@Configuration
public class SummariserConfig {

  /**
   * The cascade.
   *
   * <p>The registry arrives as a supplier for {@code Scribe}'s and {@code Curator}'s reason
   * exactly: it validates every declared tool name against the tool layer, so one of the two has to
   * be built after the other, and a resolution at bean-creation time would fix the answer for the
   * life of the context — the point being that a registry defined later is picked up.
   *
   * <p>The span size is refused here as well as in the constructor, on {@code DocumentsConfig}'s
   * standing rule: a number that cannot work should be refused before a document has been uploaded,
   * naming the key an operator would have to edit, rather than at the first ingest.
   */
  @Bean
  public Summariser summariser(
      DocumentStore store,
      JobRuntime runtime,
      ObjectProvider<AgentRegistry> agents,
      Compaction compaction,
      DocumentsProperties props,
      SummaryEmbeddings summaryVectors) {
    if (props.getSpanSize() < 2) {
      throw new IllegalStateException(
          "plowshare.documents.span-size is "
              + props.getSpanSize()
              + "; it must be at least 2. A fold reads that many summaries and"
              + " writes one, so a size of one would fold a level into a level of"
              + " the same length and never finish, and a fold of a single summary"
              + " is a paraphrase rather than a compression");
    }
    return new Summariser(
        store, runtime, agents::getIfAvailable, props.getSpanSize(), compaction, summaryVectors);
  }
}
