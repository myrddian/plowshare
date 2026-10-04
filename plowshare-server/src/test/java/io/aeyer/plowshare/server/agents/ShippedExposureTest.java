package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.curator.Curator;
import io.aeyer.plowshare.server.agents.learner.Learner;
import io.aeyer.plowshare.server.agents.scribe.Scribe;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Who may reach each shipped agent, from outside and from inside.
 *
 * <h2>Why this is one file and not five assertions scattered through five</h2>
 *
 * <p>{@code exported} and {@code delegable} are the only two declarations in an agent file whose
 * meaning is a statement about the <em>set</em>: every other key says what one agent may do, and
 * these two say who may reach it. A table spread over the five definition tests would be five
 * half-answers to one question, and the interesting property — that all four corners of the table
 * are occupied by something shipped — could not be asserted anywhere at all.
 *
 * <p>Read against the real directory through the real loader, like {@code
 * InterlocutorDefinitionTest} and for its reason: what is pinned has to be what a boot would read,
 * not a fixture that resembles it.
 */
class ShippedExposureTest {

  /**
   * By path and not through the classpath: both source sets publish an {@code agents} directory, so
   * {@code getResource("/agents")} on a test classpath resolves to the fixtures. {@code
   * InterlocutorDefinitionTest} measured that and owns the note.
   */
  private static final Path SHIPPED = Path.of("src/main/resources/agents");

  private static Map<String, AgentDefinition> shipped() {
    return AgentRegistry.load(SHIPPED, BoundTools.boundByThisServer());
  }

  /**
   * The five front doors, and no others.
   *
   * <p>An operator's whole way in is a turn taken by {@code interlocutor}; {@code code_reviewer},
   * {@code librarian} and {@code close_reader} are the three other agents it is safe to hand
   * somebody directly, each being read-only, delegating to nobody, and ending in a report that is
   * the whole of its output.
   *
   * <p><b>It reads five, and the readers are now three: the corpus, one document, and one
   * picture.</b> {@code image_reader} joined on 2026-09-07 and meets the bar above exactly — {@code
   * tools: []} so it is read-only by construction, {@code calls: []} so it delegates to nobody, and
   * a schema'd object that is the whole of its output. It is the first front door whose subject is
   * not text, and the first whose answer is a shape rather than prose; neither changes what makes a
   * door safe to hand somebody.
   *
   * <p><b>It read three and then four, and the one that joined answers about a document rather than
   * about the corpus.</b> The pair is the shape Anchor draws too and drew first — a ranking across
   * the papers, and a question put to one of them — and the argument for a second file rather than
   * a fourth tool on {@code librarian} is in {@code close_reader.md}: {@code librarian}'s body is
   * text that works, and {@code implementation rationale} measured a change of that size moving
   * behaviour 5/5 to 0/5 with the suite green either way.
   *
   * <p><b>{@code coder} joined on 2026-09-15, and it is the interlocutor's case, not the
   * readers'.</b> It writes and it runs commands, so it is not read-only by construction; it is
   * handed to a person for the reason the interlocutor is — a person asks it for a change — and
   * what bounds it is its {@code workspace:write} grant, the project's {@code environment.yml} (off
   * by default on both sides) and, where the project says so, a person's approval.
   *
   * <p><b>Asserted as the exact set and not as memberships</b>, because the failure worth catching
   * is a <em>new</em> agent quietly arriving on the public surface. Four {@code assertTrue}s would
   * pass for a directory in which everything was exported.
   */
  @Test
  void the_shipped_front_doors_are_the_interlocutor_the_coder_the_reviewer_and_the_three_readers() {
    assertEquals(
        Set.of(
            "interlocutor", "coder", "code_reviewer", "librarian", "close_reader", "image_reader"),
        new AgentRegistry(shipped()).exportedNames());
  }

  /**
   * The three the system calls itself are not front doors, and the reason is the same for all
   * three: their caller composes their input.
   *
   * <p>{@code Scribe} and {@code Learner} go further than that — neither has a turn loop at all, so
   * a run started from outside would put them through {@link JobRuntime}, machinery they never
   * touch in production. {@code promotion_judge} does run through it, and is unexported for the
   * narrower reason that its opening message is a rendering {@code Curator} builds out of the index
   * and {@code POST /v1/curate} is the door that starts it.
   */
  @Test
  void the_agents_the_system_calls_itself_are_not_exported() {
    Map<String, AgentDefinition> agents = shipped();
    assertFalse(agents.get(Scribe.AGENT).exported());
    assertFalse(agents.get(Learner.AGENT).exported());
    assertFalse(agents.get(Curator.AGENT).exported());
  }

  /** Skills delegate to the Interlocutor; capability access remains explicit. */
  @Test
  void the_interlocutor_is_a_delegable_agent_with_explicit_skill_access() {
    AgentDefinition agent = shipped().get("interlocutor");
    assertTrue(agent.exported());
    assertTrue(agent.delegable());
    assertFalse(agent.bot());
    assertEquals(java.util.List.of("*"), agent.skills());
    assertFalse(agent.calls().isEmpty());
  }

  /**
   * <b>The Documents set has exactly one front door, and it is the librarian.</b>
   *
   * <p>The set is nine files — five summarisers, the deliberation's three, and this — and the shape
   * the owner asked for is private machinery with one exported query agent. That is a claim about
   * the <em>set</em> in exactly the sense this file exists for, and it is the half {@code
   * the_summarisers_are_private_and_there_are_five_of_them} could not make: a directory in which
   * the summarisers were private and nothing else in Documents existed at all satisfies that test
   * completely, and was the state of this repository until this agent was written.
   *
   * <p><b>It read six and now reads nine, and the three that joined are the per-document ask's.</b>
   * They are private for the summarisers' reason taken one step further: {@code Deliberation}
   * composes each stage's input out of rows, out of the retrieval, and out of the two runs before
   * it — and for the critic, what makes it a critic is precisely which of those it is <em>not</em>
   * given. A task string typed at {@code POST /v1/agents/&#123;name&#125;/runs} could not withhold
   * anything.
   *
   * <p><b>Asserted by naming both halves</b>, so that a further exported member arriving later
   * fails here and has to argue for itself — which is exactly what happened to the version of this
   * test that read "exactly one of its nine members".
   *
   * <h2>The second door, and why the deliberation's three did not become three</h2>
   *
   * <p><b>{@code close_reader} is exported and the three {@code ask_*} agents stay private, and the
   * two facts are the same fact.</b> The stages are not things to converse with: what makes a
   * critic a critic is precisely which evidence it is <em>not</em> given, and a task string typed
   * at {@code POST /v1/agents/&#123;name&#125;/runs} could not withhold anything. What was missing
   * was not a door onto a stage; it was a door onto the <em>pass</em>, and onto the pass named the
   * way a person names a document. This is that door, and it holds one tool that runs all three
   * stages behind it.
   *
   * <p>So the set reads ten and exports two, and the two divide by scope rather than by machinery:
   * {@code librarian} is asked what the papers say, {@code close_reader} is asked what a paper
   * says.
   */
  @Test
  void the_documents_set_exports_the_librarian_and_the_close_reader_and_nothing_else() {
    Map<String, AgentDefinition> agents = shipped();
    Set<String> documents =
        Set.of(
            "paragraph_summariser",
            "span_summariser",
            "section_summariser",
            "chapter_summariser",
            "document_summariser",
            "ask_proposer",
            "ask_critic",
            "ask_reviewer",
            "ask_synthesiser",
            "librarian",
            "close_reader");
    assertTrue(agents.keySet().containsAll(documents), agents.keySet().toString());

    Set<String> exported =
        documents.stream()
            .filter(name -> agents.get(name).exported())
            .collect(java.util.stream.Collectors.toSet());
    assertEquals(Set.of("librarian", "close_reader"), exported);
  }

  /**
   * The per-document reader holds one tool, and it is not the corpus search.
   *
   * <p><b>The property that stops there being two librarians.</b> The brief this agent was written
   * against named the cost of a second file as "two agents that both search the corpus", and this
   * is what makes that cost zero: the resolution from a person's words to a document id happens
   * <em>inside</em> {@code document_ask}, so the second agent never holds {@code document_search}
   * at all and has no corpus-wide capability to overlap with.
   *
   * <p>Asserted as the exact list rather than as an absence, because the failure worth catching is
   * the search being added later for convenience — at which point the two agents' descriptions
   * start competing for the same question and nothing else in this suite would notice.
   */
  @Test
  void the_close_reader_holds_deliberation_and_document_evidence_without_corpus_search() {
    AgentDefinition reader = shipped().get("close_reader");
    assertEquals(
        List.of(
            AskTool.NAME,
            RetrievalTools.RETRIEVE,
            RetrievalTools.RANK,
            RetrievalTools.OUTLINE,
            RetrievalTools.CITATIONS),
        reader.tools());
    assertFalse(reader.tools().contains(DocumentTools.SEARCH_NAME));
    assertTrue(reader.calls().isEmpty(), "a leaf: calls: is empty and agent_run is not held");
    assertTrue(
        reader.scopes().isEmpty(),
        "a callee holding a grant is the thing the non-escalation check exists for");
    assertTrue(
        reader.delegable(),
        "a bounded job ending in a report, in code_reviewer's and librarian's corner");
  }

  /**
   * The librarian occupies the reviewer's corner and not the interlocutor's, and the pair is the
   * decision.
   *
   * <p>Exported <em>and</em> delegable-to. The two exported agents that take an open question look
   * alike from outside, and what separates {@code interlocutor} is not that it converses: it is
   * that a person's chat agent becoming somebody's sub-agent is a category error. Nothing of the
   * sort is true of an agent that answers one question about some papers and ends in a report, so
   * the refusal is not written and the edge stays available.
   *
   * <p><b>And nothing names it, which is the same state {@code interlocutor} was in before either
   * key existed.</b> Asserted rather than left implied, because the day something does name it the
   * non-escalation check has to have something to check: this agent declares no grant at all, so it
   * can never be the escalating end of an edge.
   *
   * <p><b>The day came on 2026-09-10 and this test did its job.</b> {@code interlocutor} named it,
   * this failed, the edge was argued, and the edge lost: it was the only candidate callee holding
   * tools its caller already had — {@code document_search} and {@code document_list} — so it bought
   * turns rather than reach, and {@code TODO.md}'s "The librarian loops by rephrasing — REAL" had
   * observed 3 of 7 runs spending the whole allowance without answering. Restored deliberately, and
   * {@code close_reader} went in on the same day and stayed, which is the distinction worth
   * keeping: this assertion is not against delegation to the Documents set, it is against an edge
   * that adds nothing the caller could not already do.
   */
  @Test
  void the_librarian_is_exported_and_delegable_to_and_nothing_names_it_yet() {
    Map<String, AgentDefinition> agents = shipped();
    AgentDefinition librarian = agents.get("librarian");
    assertTrue(librarian.exported());
    assertTrue(librarian.delegable());
    assertTrue(
        librarian.scopes().isEmpty(),
        "a callee holding a grant is the thing the non-escalation check exists for");

    assertTrue(
        agents.values().stream().noneMatch(a -> a.calls().contains("librarian")),
        "something delegates to the librarian, and interlocutor.md's argument for why"
            + " nothing does yet is now describing a directory it does not match");
  }

  /**
   * A third axis arrived and this directory is untouched by it.
   *
   * <p>{@code bot} joins {@code exported} and {@code delegable} in frontmatter, and it defaults
   * false so that every definition here keeps its meaning without being edited. That default is
   * only a claim until something reads it back off the real files: this is that reading, and it is
   * asserted as the empty set rather than per name so that a definition acquiring the flag has to
   * argue for itself here.
   *
   * <p><b>A shipped bot is not in this directory</b>, which is a filing convenience and not what
   * the loader reads — {@code AgentRegistryTest}'s {@code
   * the_flag_and_not_the_directory_is_what_makes_a_definition_a_bot} holds the other half.
   */
  @Test
  void nothing_in_the_agents_directory_declares_itself_a_bot() {
    assertEquals(
        Set.of(),
        shipped().values().stream()
            .filter(AgentDefinition::bot)
            .map(AgentDefinition::name)
            .collect(java.util.stream.Collectors.toSet()),
        "an agent became a bot without anybody deciding it was a character");
  }

  /**
   * The fourth corner, and the reason the two keys are two keys.
   *
   * <p>{@code code_reviewer} is exported <em>and</em> delegable-to: a person may start it and
   * {@code interlocutor} may hand work to it. A single switch — "public" against "internal" — could
   * not express that, and the edge {@code interlocutor} depends on would have had to be severed to
   * put the reviewer on the agents list.
   */
  @Test
  void the_reviewer_is_exported_and_still_delegable_to() {
    AgentDefinition reviewer = shipped().get("code_reviewer");
    assertTrue(reviewer.exported());
    assertTrue(reviewer.delegable());
    assertTrue(shipped().get("interlocutor").calls().contains("code_reviewer"));
  }

  /**
   * The corner nobody reaches from outside and a caller may: unexported, and delegable-to.
   *
   * <p>Every member is a real {@link JobRuntime} run whose input a caller composes — the five
   * summarisers {@code Summariser} feeds, {@code promotion_judge} {@code Curator} feeds, {@code
   * diagnosis_verifier} Daedalus hands claims, {@code research_critic}, which the {@code
   * deep_research} conductor hands its numbered findings and their sources, and {@code
   * test_designer}, which a phase conductor hands a phase's slice of a spec and that phase's plan.
   * A task string typed at {@code POST /v1/agents/&#123;name&#125;/runs} would be doing that
   * caller's job by hand.
   *
   * <p><b>Asserted as the exact set</b>, for this file's reason: an agent arriving in the corner
   * has to argue for itself here, and so does one leaving it for the public list.
   */
  @Test
  void the_agents_only_a_caller_reaches_are_exactly_these() {
    assertEquals(
        Set.of(
            "paragraph_summariser",
            "span_summariser",
            "section_summariser",
            "chapter_summariser",
            "document_summariser",
            "information_tagger",
            "information_tag_grouper",
            "promotion_judge",
            "diagnosis_verifier",
            "research_analyst",
            "research_critic",
            "test_designer"),
        shipped().values().stream()
            .filter(a -> !a.exported() && a.delegable())
            .map(AgentDefinition::name)
            .collect(java.util.stream.Collectors.toSet()));
  }

  /**
   * {@code scribe} and {@code learner} refuse delegation as well as export, and that is one
   * decision made twice rather than two.
   *
   * <p>{@code AgentRunTool} runs a callee through {@link JobRuntime}, which is the same machinery
   * {@code POST /v1/agents/&#123;name&#125;/runs} uses. So an agent with no turn loop that closed
   * only the outside door would have left the identical hazard open on the side nothing was
   * watching.
   *
   * <p>{@code promotion_judge} is deliberately not here. It is a real job with a turn loop and a
   * tool, so nothing structural is wrong with delegating to it, and no definition names it —
   * writing the refusal would be closing a door on speculation.
   */
  @Test
  void the_two_agents_with_no_turn_loop_refuse_delegation_too() {
    Map<String, AgentDefinition> agents = shipped();
    assertFalse(agents.get(Scribe.AGENT).delegable());
    assertFalse(agents.get(Learner.AGENT).delegable());
    assertTrue(agents.get(Curator.AGENT).delegable());
  }

  /**
   * <b>The five summarisers are private, and that is the owner's shape for the documents set rather
   * than a default they fell into.</b>
   *
   * <p>The shape is private summarisers and one exported query agent. Each of the five is
   * unexported for {@code promotion_judge}'s reason and not {@code scribe}'s: all five are real
   * {@link JobRuntime} runs with turn loops, and what disqualifies them from the front door is that
   * {@code Summariser} composes their input — one paragraph off a row, or the summaries of one unit
   * in the order a derivation decided, under a title the derivation decided whether to show. A task
   * string typed at {@code POST /v1/agents/&#123;name&#125;/runs} would produce a summary of prose
   * nobody stored, written into no document.
   *
   * <p><b>Asserted as the exact set of names, and the set changed.</b> It read three, and the
   * argument for three was that Anchor's section and chapter levels each summarise a single input
   * on a server with no detectors — true then, and answered by V26: the detectors and the two
   * tables landed, so a section and a chapter are rows with ids that the per-document ask reads
   * directly. What the naming still buys is the same thing: a sixth summariser has to argue for
   * itself here.
   */
  @Test
  void the_summarisers_are_private_and_there_are_five_of_them() {
    Map<String, AgentDefinition> agents = shipped();
    Set<String> summarisers =
        agents.keySet().stream()
            .filter(name -> name.endsWith("_summariser"))
            .collect(java.util.stream.Collectors.toSet());

    assertEquals(
        Set.of(
            "paragraph_summariser",
            "span_summariser",
            "section_summariser",
            "chapter_summariser",
            "document_summariser"),
        summarisers);
    for (String name : summarisers) {
      assertFalse(agents.get(name).exported(), name + " is on the public agent list");
    }
  }

  /**
   * No summariser holds a tool, and the reason is the cascade's one guarantee.
   *
   * <p>Raw text enters at the bottom and only summaries travel upward, which is what gives the
   * levels independent compression. That invariant is held by what the caller puts in an opening
   * message — so an agent above the bottom that could reach the corpus could read the text it is
   * deliberately not shown, and the invariant would become a convention nothing enforces. At the
   * bottom the same emptiness stops a summary being written about a paragraph other than the one
   * that was handed over.
   */
  @Test
  void a_summariser_is_handed_its_material_and_can_reach_nothing_else() {
    Map<String, AgentDefinition> agents = shipped();
    for (String name :
        List.of(
            "paragraph_summariser",
            "span_summariser",
            "section_summariser",
            "chapter_summariser",
            "document_summariser")) {
      AgentDefinition summariser = agents.get(name);
      assertTrue(summariser.tools().isEmpty(), name + " declares a tool");
      assertTrue(summariser.calls().isEmpty(), name + " declares a callee");
      assertTrue(summariser.scopes().isEmpty(), name + " declares a scope");
    }
  }
}
