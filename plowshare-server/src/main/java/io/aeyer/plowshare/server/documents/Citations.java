package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Citing;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Citing}, over the corpus: the paragraph ids an answer named, written into {@code
 * citations}.
 *
 * <p>{@code Citing}'s javadoc carries the argument for reading citations out of the answer rather
 * than asking a model to file them, and {@link CitationStore} carries the argument for validating
 * them against the corpus rather than against the log. This class is the twenty lines those two
 * decisions leave.
 *
 * <h2>The extraction is a uuid match, and that is why it is not fragile</h2>
 *
 * <p>A citation in an answer is prose — <em>"the retry budget is refilled per run (paragraph
 * 4f1c…)"</em>, or a bracket, or a footnote, or a list at the bottom — and no two models phrase it
 * alike. <b>What does not vary is the id.</b> {@code DocumentTools.render} writes it on its own
 * line as {@code cite paragraph &lt;uuid&gt;}, a uuid has one canonical spelling, and matching that
 * shape reads every phrasing above without knowing about any of them. A parse of the sentence
 * around it would be the fragile thing, and there is no reason to write one.
 *
 * <p><b>What else in an answer looks like a uuid</b>, since the pattern cannot tell: a
 * stored-result handle, which an agent may quote when it says what it read back. Nothing is done
 * about it and nothing needs to be — a handle is not a paragraph id, so {@link
 * CitationStore#record} finds no row to join to and writes nothing. The corpus is the filter,
 * exactly as it is for a uuid a model made up.
 *
 * <h2>Deduplicated in order of first mention</h2>
 *
 * <p>An answer citing one paragraph for three claims is one citation. V25 declines a unique
 * constraint that would have said so in the schema, because an answer citing one paragraph for two
 * <em>different</em> claims is a shape somebody may want two rows for later; the deduplication is
 * here, where it can be revised, on V18's own split between a rule and the columns it is written
 * over. {@link LinkedHashSet} rather than a plain set so the rows land in the order the answer made
 * them, which is the order a reader reads them in.
 *
 * <h2>It never throws</h2>
 *
 * <p>{@link Citing}'s rule. A corpus that could not be written to costs a record of a citation and
 * must not cost the answer, so a failure is one warning naming the conversation and the paragraph,
 * and the run goes on. The paragraph id is safe to log: it is a uuid this server minted and it is
 * already on the agent surface, in every search result, by design.
 */
public final class Citations implements Citing {

  private static final Logger log = LoggerFactory.getLogger(Citations.class);

  /**
   * A uuid in its one canonical spelling.
   *
   * <p>Case-insensitive hex, because a model retyping an id rather than copying it may upper-case
   * it and the corpus does not care; {@link UUID#fromString} accepts either and {@code
   * paragraphs.id} is a Postgres {@code uuid}, which has no case at all.
   *
   * <p>The boundaries are {@code (?&lt;![0-9a-fA-F-])} and {@code (?![0-9a-fA-F-])} rather than
   * {@code \b}: {@code \b} sits happily inside a longer hex-and-hyphen run, so a malformed id with
   * an extra group would yield a valid-looking prefix. A citation must be the whole id or nothing.
   */
  private static final Pattern UUID_SHAPED =
      Pattern.compile(
          "(?<![0-9a-fA-F-])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
              + "-[0-9a-fA-F]{12}(?![0-9a-fA-F-])");

  private final CitationStore citations;
  private final Clock clock;
  private io.aeyer.plowshare.server.information.InformationAccess access;
  private io.aeyer.plowshare.server.information.InformationJobs inputs;
  private io.aeyer.plowshare.server.archive.ConversationStore conversations;

  public void useInformationInputs(
      io.aeyer.plowshare.server.information.InformationAccess access,
      io.aeyer.plowshare.server.information.InformationJobs inputs,
      io.aeyer.plowshare.server.archive.ConversationStore conversations) {
    this.access = access;
    this.inputs = inputs;
    this.conversations = conversations;
  }

  public Citations(CitationStore citations, Clock clock) {
    this.citations = Objects.requireNonNull(citations, "citations");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void whatTheAnswerCited(
      AgentDefinition definition, String conversationId, Integer turnOrdinal, String answer) {
    Objects.requireNonNull(definition, "definition");
    // The guardrail, and it is the loader's answer rather than this class's.
    // An agent that was never granted the corpus cannot have been handed a
    // paragraph id by it, so a uuid in its answer is a uuid about something
    // else.
    if (!definition.canCite() || answer == null || answer.isBlank()) {
      return;
    }
    for (UUID paragraph : named(answer)) {
      try {
        CitationStore writer = citations;
        io.aeyer.plowshare.server.information.InformationContext context = null;
        if (access != null) {
          var conversation = conversations.find(conversationId).orElseThrow();
          String owner = conversations.ownerOf(conversationId).orElseThrow();
          context = access.forRun(owner, conversation.home());
          inputs.requireLog(conversationId, owner);
          writer = citations.scoped(access, context);
        }
        if (context != null) {
          var revision = citations.documentOf(paragraph);
          if (revision != null) inputs.reads(conversationId, context).accept(revision);
        }
        writer.record(paragraph, conversationId, turnOrdinal, definition.name(), clock.instant());
      } catch (RuntimeException notWritten) {
        log.warn(
            "conversation {}: the citation of paragraph {} in an answer by '{}'"
                + " could not be written down. Reason: {}",
            conversationId,
            paragraph,
            definition.name(),
            notWritten.getClass().getSimpleName() + ": " + notWritten.getMessage());
      }
    }
  }

  /** Every uuid the answer names, once each, in order of first mention. */
  private static Set<UUID> named(String answer) {
    Set<UUID> found = new LinkedHashSet<>();
    Matcher shaped = UUID_SHAPED.matcher(answer);
    while (shaped.find()) {
      // Cannot throw: the pattern is narrower than what fromString takes.
      found.add(UUID.fromString(shaped.group()));
    }
    return found;
  }
}
