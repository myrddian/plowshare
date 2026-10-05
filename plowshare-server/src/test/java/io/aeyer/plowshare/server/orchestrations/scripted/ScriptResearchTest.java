package io.aeyer.plowshare.server.orchestrations.scripted;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Executes the actual shipped ESM, with conflicting retained evidence and semantic-task fixtures.
 */
class ScriptResearchTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String SOURCE = source();

  static String source() {
    try {
      return Files.readString(Path.of("src/main/resources/orchestrations/deep_research.js"));
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  static final String ORIGINAL = "How does Example's adoption differ from its claimed efficacy?";
  static final String BLUE = "00000000-0000-0000-0000-000000000001";
  static final String RED = "00000000-0000-0000-0000-000000000002";
  static final String UNUSED = "00000000-0000-0000-0000-000000000003";
  static final String AUDIT = "00000000-0000-0000-0000-000000000098";
  static final String REPORT = "00000000-0000-0000-0000-000000000099";
  static final String QUOTE =
      "  Example's independent measurements contradict the claimed improvement.\n";

  static final class Fixture {
    JsonNode state = JSON.nullNode();
    String result;
    final List<ObjectNode> todos = new ArrayList<>();
    int commands, modelCalls, authors, editors;
    int waits, pendingObservations, fenceCalls;
    long waitedMs;
    String pendingSourceStage;
    int pendingSourceResponses;
    int badDecompositions,
        decompositionCalls,
        initialSearches,
        queriesPerObjective = 12,
        findingsPerObjective = 1,
        counterQueryCount = 8,
        questions,
        planCalls;
    ObjectNode reportArguments, auditArguments;
    String finishResult;
    boolean badEditor,
        unknownCitation,
        feedback,
        repeatedCounterQueries,
        auditMode,
        malformedOnce,
        malformedAlways;
    boolean malformedDecomposition, alwaysMalformedDecomposition, mirrorDecompositionRepair;
    boolean shortEditor, singleParagraph, malformedEditor, malformedAuthor, unknownProseCitation;
    boolean multiplePassages, wrongPassageRevision, supplementalMode, genericQuery;
    boolean stopOnQuestion, planSupplement, repeatPlanSupplement, rankingOrder;
    boolean incompleteRanking, emptyRanking, malformedRanking;
    boolean complementaryQueryReview, dropAllQueries;
    int selectionCopies = 1;
    boolean repeatInspectedSelection;
    boolean unmatchedSelection, allSelectionsUnmatched;
    String omittedQueryType;
    String continuationMessage;
    String preflightFailure;
    String localJsonKind;
    String malformedAssessmentKind;
    boolean partialReviews;
    final java.util.Deque<String> objectiveAnswers = new java.util.ArrayDeque<>();
    final List<JsonNode> askedQuestions = new ArrayList<>();
    final List<String> retrievalQueries = new ArrayList<>();
    String scriptSource = SOURCE;
    final java.util.Map<String, String> acquisitions = new java.util.HashMap<>();
    final List<String> semanticTasks = new ArrayList<>();

    Fixture() {
      for (JsonNode stage : ScriptProgram.manifest(SOURCE).path("stages"))
        todos.add(
            JSON.createObjectNode()
                .put("id", stage.path("id").asText())
                .put("stageId", stage.path("id").asText())
                .put("status", "PENDING"));
    }

    void run() {
      for (int sequence = commands; sequence < 2000; sequence++) {
        var input =
            JSON.createObjectNode()
                .put("run", "cnv_fixture")
                .put("sequence", sequence)
                .put("requestId", UUID.nameUUIDFromBytes(("step" + sequence).getBytes()).toString())
                .put(
                    "message",
                    continuationMessage != null
                        ? continuationMessage
                        : JSON.createObjectNode()
                            .put(
                                "request",
                                feedback
                                    ? JSON.createObjectNode()
                                        .put("question", ORIGINAL)
                                        .put("feedback_revision", REPORT)
                                        .put("feedback", "Recheck the efficacy conclusion")
                                        .toString()
                                    : ORIGINAL)
                            .put("context", "Compare the two topics independently.")
                            .toString());
        input.set("state", state);
        input.put("result", result);
        input.set("todos", JSON.valueToTree(todos));
        // Every invocation creates a fresh sandbox. Only serialized journal state survives.
        var output = ScriptProgram.step(scriptSource, parse(input.toString()));
        state = output.path("state");
        JsonNode command = output.path("command");
        commands++;
        if (command.has("waitMs")) {
          waits++;
          waitedMs += command.path("waitMs").asLong();
          result = "{}";
          continue;
        }
        String tool = command.path("tool").asText();
        JsonNode args = command.path("arguments");
        String kind = state.path("pending").path("kind").asText();
        if (tool.equals("orchestration_finish")) {
          finishResult = args.path("result").asText();
          assertTrue(finishResult.contains(REPORT));
          return;
        }
        if (tool.equals("orchestration_ask")) {
          io.aeyer.plowshare.server.agents.StructuredQuestions.read(args.path("questions"));
          questions++;
          askedQuestions.add(args.deepCopy());
          result =
              "Asked. Your turn ends after this step; the answer arrives as your next message.";
          if (stopOnQuestion) return;
          continuationMessage =
              deliveredAnswer(
                  objectiveAnswers.isEmpty() ? "Approve objectives" : objectiveAnswers.remove());
          continue;
        }
        result = response(tool, args, kind, sequence).toString();
        if (tool.equals("agent_run") && kind.equals(localJsonKind))
          result = "```json\n" + result.replace("\"queries\":", "\\\"queries\\\":") + "\n```";
        if (preflightFailure != null
            && tool.equals("information_read")
            && kind.equals("preflight_rank")) result = preflightFailure;
        if (tool.equals("agent_run")
            && kind.equals("objectives")
            && (malformedAlways || malformedOnce && modelCalls == 1)) result = "{broken JSON";
        if (tool.equals("agent_run")
            && kind.equals("decompose")
            && (alwaysMalformedDecomposition || malformedDecomposition && decompositionCalls == 1))
          result = "{broken JSON";
        if (tool.equals("agent_run")
            && kind.equals("decompose")
            && mirrorDecompositionRepair
            && decompositionCalls == 2) {
          String task = args.path("task").asText();
          result = task.substring(task.lastIndexOf('\n') + 1);
        }
        if (tool.equals("agent_run")
            && (kind.equals("editor") && malformedEditor
                || kind.equals("author") && malformedAuthor)) result = "{broken JSON";
        if (tool.equals("agent_run") && kind.equals("ranking") && malformedRanking)
          result = "{broken JSON";
        if (tool.equals("agent_run") && kind.equals(malformedAssessmentKind))
          result = "{broken JSON";
      }
      fail("script did not terminate");
    }

    JsonNode response(String tool, JsonNode args, String kind, int sequence) {
      if (tool.equals("todo_write")) {
        for (JsonNode op : args.path("ops"))
          todos.stream()
              .filter(t -> t.path("id").equals(op.path("id")))
              .forEach(t -> t.put("status", op.path("status").asText().toUpperCase()));
        return JSON.createObjectNode().put("updated", true);
      }
      if (tool.equals("search")) {
        retrievalQueries.add(args.path("query").asText());
        if (state.path("pending").path("wave").asInt() == 0) initialSearches++;
        var hits =
            JSON.createArrayNode()
                .add(
                    JSON.createObjectNode()
                        .put(
                            "url",
                            planSupplement
                                    && args.path("query")
                                        .asText()
                                        .contains("controlled trial supplement")
                                ? "https://example.test/supplement"
                                : state.path("stage").asInt() < 8
                                    ? "https://example.test/primary"
                                    : "https://example.test/counter")
                        .put("title", "Independent source")
                        .put("snippet", "Discovery metadata, never a citation"));
        if ((auditMode || supplementalMode) && state.path("stage").asInt() < 8) {
          hits.add(
              JSON.createObjectNode()
                  .put("url", "https://example.test/unused")
                  .put("title", "Retained but unused"));
          hits.add(
              JSON.createObjectNode()
                  .put("url", "https://example.test/failed")
                  .put("title", "Unavailable source"));
        }
        return JSON.createObjectNode().set("hits", hits);
      }
      if (tool.equals("information_read"))
        switch (args.path("operation").asText()) {
          case "rank":
            return JSON.valueToTree(
                java.util.Map.of(
                    "documents",
                    List.of(
                        java.util.Map.of(
                            "document", java.util.Map.of("id", BLUE, "title", "Prior corpus")))));
          case "list":
            return JSON.createArrayNode();
          case "search":
            {
              if (!args.has("revision")) retrievalQueries.add(args.path("query").asText());
              String revision =
                  args.has("revision")
                      ? args.path("revision").asText()
                      : planSupplement
                              && args.path("query").asText().contains("controlled trial supplement")
                          ? UNUSED
                          : state.path("pending").path("wave").asInt() == 1 ? RED : BLUE;
              var passages =
                  JSON.createArrayNode()
                      .add(
                          JSON.createObjectNode()
                              .put(
                                  "revision",
                                  wrongPassageRevision && args.has("revision") ? UNUSED : revision)
                              .put("matched", true)
                              .put("start", 9000)
                              .put("end", 9000 + QUOTE.length())
                              .put("text", QUOTE)
                              .put("title", "Retained body passage"));
              if (multiplePassages && args.has("revision")) {
                passages.add(passages.get(0).deepCopy());
                passages.add(
                    JSON.createObjectNode()
                        .put("revision", revision)
                        .put("matched", true)
                        .put("start", 12000)
                        .put("end", 12000 + QUOTE.length())
                        .put("text", QUOTE));
              }
              return passages;
            }
          case "await":
            {
              fenceCalls++;
              var outcomes = JSON.createArrayNode();
              int settled = 0, ready = 0;
              for (JsonNode ref : args.path("sources")) {
                var row = (ObjectNode) ref.deepCopy();
                String revision = ref.path("revision").asText();
                boolean failed = false, pending = false;
                if (ref.has("acquisition")) {
                  String url = acquisitions.get(ref.path("acquisition").asText());
                  failed = url.endsWith("/failed");
                  pending = url.endsWith("/unused") && pendingSource("acquisition");
                  row.put(
                      "acquisition_state", failed ? "failed" : pending ? "queued" : "succeeded");
                  if (!failed && !pending)
                    revision =
                        url.endsWith("/unused") || url.endsWith("/supplement")
                            ? UNUSED
                            : state.path("stage").asInt() < 8 ? BLUE : RED;
                }
                if (!revision.isEmpty()) {
                  row.put("revision", revision);
                  pending = revision.equals(UNUSED) && pendingSource("extraction");
                  row.put("extraction_state", pending ? "pending" : "ready");
                }
                row.put("state", failed ? "failed" : pending ? "pending" : "ready");
                if (failed) row.put("error", "fixture fetch failure");
                if (!pending) settled++;
                if (!pending && !failed) ready++;
                outcomes.add(row);
              }
              return JSON.createObjectNode()
                  .put("expected", outcomes.size())
                  .put("settled", settled)
                  .put("ready", ready)
                  .put("pending", outcomes.size() - settled)
                  .put("complete", settled == outcomes.size())
                  .set("outcomes", outcomes);
            }
          case "read":
            return JSON.createObjectNode()
                .put("text", "Prior report assessment")
                .put("start", 0)
                .put("end", 23)
                .put("total", 23);
          case "status":
            return JSON.createObjectNode()
                .put("kind", "report")
                .put("source_name", "previous-research.md")
                .put("generation", 1)
                .set(
                    "steps",
                    JSON.createArrayNode()
                        .add(
                            JSON.createObjectNode()
                                .put("stage", "extract")
                                .put(
                                    "state",
                                    args.path("revision").asText().equals(UNUSED)
                                            && pendingSource("extraction")
                                        ? "pending"
                                        : "ready")
                                .put("generation", 1)));
          default:
            throw new AssertionError("unexpected read " + args);
        }
      if (tool.equals("information_write"))
        switch (args.path("operation").asText()) {
          case "acquire":
            acquisitions.put(args.path("requestId").asText(), args.path("url").asText());
            return JSON.createObjectNode().put("id", args.path("requestId").asText());
          case "evidence":
            assertTrue(
                state.path("sourceFence").path("complete").asBoolean(),
                "no evidence before every source has a readiness outcome");
            assertEquals(
                QUOTE, args.path("quote").asText(), "source whitespace and exact offsets survive");
            assertTrue(
                args.path("start").asInt() == 9000
                    || multiplePassages && args.path("start").asInt() == 12000);
            return JSON.createObjectNode().put("evidence", args.path("requestId").asText());
          case "report":
            // Use the actual information-domain contract, not only a permissive fake write.
            io.aeyer.plowshare.server.information.InformationReportDetailsDecoder.from(
                JSON.convertValue(
                    args,
                    new com.fasterxml.jackson.core.type.TypeReference<
                        java.util.Map<String, Object>>() {}));
            if (state.path("pending").path("kind").asText().equals("report_audit")) {
              auditArguments = args.deepCopy();
              return JSON.createObjectNode().put("revision", AUDIT);
            }
            reportArguments = args.deepCopy();
            return JSON.createObjectNode().put("revision", REPORT);
          default:
            throw new AssertionError("unexpected write " + args);
        }
      if (!tool.equals("agent_run")) throw new AssertionError("unexpected granted tool " + tool);
      assertEquals("research_analyst", args.path("agent").asText());
      String task = args.path("task").asText();
      semanticTasks.add(task);
      modelCalls++;
      assertTrue(task.contains("ORIGINAL QUESTION: " + ORIGINAL));
      JsonNode data;
      // Parse using the final newline before the JSON rather than depending on task prose.
      data = parse(task.substring(task.lastIndexOf('\n') + 1));
      String evidence =
          state.path("sources").isEmpty()
              ? BLUE
              : state.path("sources").get(0).path("evidence").asText();
      return switch (kind) {
        case "objectives", "objective_revision" ->
            JSON.valueToTree(
                java.util.Map.of(
                    "topic_anchors",
                    List.of("Example"),
                    "scope",
                    kind.equals("objective_revision")
                        ? "Example, controlled trials only"
                        : "Example in the stated period",
                    "objectives",
                    List.of(
                        java.util.Map.of(
                            "objective",
                            "Adoption",
                            "intent",
                            "actual deployment",
                            "anchors",
                            List.of("Example"),
                            "expected_evidence",
                            "independent usage measurements"),
                        java.util.Map.of(
                            "objective",
                            "Efficacy",
                            "intent",
                            "actual measured effect",
                            "anchors",
                            List.of("Example"),
                            "expected_evidence",
                            "controlled evaluations"))));
        case "decompose" -> {
          decompositionCalls++;
          var queries = JSON.createArrayNode();
          int count = queriesPerObjective;
          for (int i = 0; i < count; i++)
            queries.add(
                JSON.createObjectNode()
                    .put("query", "Example query " + state.path("cursor").asInt() + " " + i)
                    .put(
                        "type",
                        i >= 9
                            ? "ADVERSARIAL"
                            : i % 3 == 0
                                ? "ENTITY_CENTRIC"
                                : i % 3 == 1 ? "TOPIC_CENTRIC" : "TERMINOLOGICAL")
                    .put("sub_question", "Expected evidence " + i)
                    .put("expected_evidence", "Independent observation"));
          if (decompositionCalls <= badDecompositions)
            ((ObjectNode) queries.get(0)).put("type", "INVALID");
          if (genericQuery)
            ((ObjectNode) queries.get(queries.size() - 1))
                .put("query", "credibility assessment " + state.path("cursor").asInt());
          if (omittedQueryType != null)
            for (int i = queries.size() - 1; i >= 0; i--)
              if (queries.get(i).path("type").asText().equals(omittedQueryType)) queries.remove(i);
          yield JSON.createObjectNode()
              .put("rationale", "Each topic has independent retrieval angles")
              .set("queries", queries);
        }
        case "query_review" -> {
          var reviews = JSON.createArrayNode();
          for (JsonNode query : state.path("queries")) {
            boolean drop =
                dropAllQueries
                    || complementaryQueryReview
                        && (query.path("objective").asText().equals("o1")
                                && query.path("type").asText().equals("TERMINOLOGICAL")
                            || query.path("objective").asText().equals("o2")
                                && query.path("type").asText().equals("TOPIC_CENTRIC"));
            reviews.add(
                JSON.createObjectNode()
                    .put("id", query.path("id").asText())
                    .put("decision", drop ? "DROP" : "KEEP")
                    .put(
                        "rationale",
                        drop
                            ? "Covered by the complementary objective"
                            : "Retains original-intent coverage"));
          }
          if (partialReviews) {
            reviews.remove(reviews.size() - 1);
            reviews.add(reviews.get(0).deepCopy());
            reviews.add(JSON.createObjectNode().put("id", "unknown").put("decision", "KEEP"));
          }
          yield JSON.createObjectNode().set("queries", reviews);
        }
        case "selection" -> {
          var sources = JSON.createArrayNode();
          for (JsonNode candidate : data.path("candidates"))
            sources.add(
                JSON.createObjectNode()
                    .put("key", candidate.path("key").asText())
                    .put("rationale", "Original intent relevance, not query wording")
                    .set("objectives", JSON.createArrayNode().add("o1").add("o2")));
          if (supplementalMode
              && state.path("pending").path("wave").asInt() == 0
              && state.path("selectionRounds").path("0").asInt() == 0) {
            var failed =
                java.util.stream.StreamSupport.stream(sources.spliterator(), false)
                    .filter(row -> row.path("key").asText().endsWith("/failed"))
                    .findFirst()
                    .orElseThrow();
            sources = JSON.createArrayNode().add(failed);
          }
          if (selectionCopies > 1) {
            var originals = sources.deepCopy();
            sources.removeAll();
            for (int copy = 0; copy < selectionCopies; copy++)
              for (JsonNode row : originals) {
                var selected = (ObjectNode) row.deepCopy();
                selected.set("objectives", JSON.createArrayNode().add(copy == 0 ? "o1" : "o2"));
                selected.put(
                    "rationale", copy == 0 ? "Relevant to adoption" : "Also relevant to efficacy");
                sources.add(selected);
              }
          }
          if (repeatInspectedSelection
              && state.path("pending").path("wave").asInt() == 0
              && state.path("selectionRounds").path("0").asInt() > 0)
            sources.add(
                JSON.createObjectNode()
                    .put("key", state.path("fetchAudit").get(0).path("key").asText())
                    .put("rationale", "This document also supports efficacy")
                    .set("objectives", JSON.createArrayNode().add("o2")));
          if (unmatchedSelection || allSelectionsUnmatched) {
            if (allSelectionsUnmatched) sources.removeAll();
            sources.add(
                JSON.createObjectNode()
                    .put("key", "https://unlisted.test/source")
                    .put("rationale", "Model selected an unlisted key")
                    .set("objectives", JSON.createArrayNode().add("o1")));
          }
          yield JSON.createObjectNode().set("sources", sources);
        }
        case "ranking" -> {
          var ranking = JSON.createArrayNode();
          int at = 0;
          for (JsonNode source : data.path("evidence"))
            ranking.add(
                JSON.createObjectNode()
                    .put("evidence", source.path("id").asText())
                    .put("objective", "o1")
                    .put("score", rankingOrder ? 0.1 + 0.1 * at++ : 0.8)
                    .put("rationale", "Directly addresses the original question"));
          if (incompleteRanking && !ranking.isEmpty()) ranking.remove(ranking.size() - 1);
          if (emptyRanking) ranking.removeAll();
          yield JSON.createObjectNode().set("ranking", ranking);
        }
        case "plan" -> {
          planCalls++;
          var plan =
              JSON.createObjectNode()
                  .put("assessment", "Both topics need independent evidence")
                  .put(
                      "needs_supplemental_retrieval",
                      planSupplement && (repeatPlanSupplement || planCalls == 1));
          plan.set("scope_changes", JSON.createArrayNode().add("Long-term effect remains unknown"));
          if (planSupplement) {
            var revised = (ObjectNode) data.path("objectives").get(0).deepCopy();
            revised.put("objective", "Adoption and controlled trials");
            plan.set("revised_objectives", JSON.createArrayNode().add(revised));
            plan.set(
                "supplemental_queries",
                JSON.createArrayNode()
                    .add(
                        JSON.createObjectNode()
                            .put("objective", "o1")
                            .put("query", "Example controlled trial supplement " + planCalls)
                            .put("type", "TOPIC_CENTRIC")
                            .put("sub_question", "Controlled effects")
                            .put("expected_evidence", "Independent controlled trial")));
          }
          yield plan;
        }
        case "blue" -> {
          var findings = JSON.createArrayNode();
          for (int i = 0; i < findingsPerObjective; i++)
            findings.add(
                JSON.createObjectNode()
                    .put("claim", "Example has proven improved outcome " + i)
                    .put("rationale", "Initial assessment to be challenged")
                    .put("confidence", "MODERATE")
                    .set(
                        "support",
                        JSON.createArrayNode().add(unknownCitation ? "invented" : evidence)));
          yield JSON.createObjectNode()
              .put("coverage", "Partially addressed; longitudinal results remain missing")
              .set("findings", findings);
        }
        case "author" -> {
          authors++;
          yield prose(unknownProseCitation ? UNUSED : evidence);
        }
        case "editor" -> {
          editors++;
          var out = prose(evidence);
          if (!badEditor) out.put("verdict", "REVISED");
          if (shortEditor)
            out.set(
                "paragraphs",
                JSON.createArrayNode()
                    .add(
                        "The evidence establishes a limited observation rather than a general result. The source does not establish causation or persistence. [evidence:"
                            + evidence
                            + "]")
                    .add(prose(evidence).path("paragraphs").get(1))
                    .add(
                        "The conclusion must remain qualified until independent measurements resolve the identified gaps. [evidence:"
                            + evidence
                            + "]"));
          if (singleParagraph)
            out.set(
                "paragraphs",
                JSON.createArrayNode()
                    .add(
                        "Independent evidence contradicts the original improvement claim. [evidence:"
                            + evidence
                            + "]"));
          yield out;
        }
        case "counter_queries" -> {
          var queries = JSON.createArrayNode();
          for (int i = 0; i < counterQueryCount; i++)
            queries.add(
                JSON.createObjectNode()
                    .put(
                        "query",
                        repeatedCounterQueries
                            ? "Example query 0 0"
                            : "Example independent failed evaluation " + i)
                    .put("objective", i % 2 == 0 ? "o1" : "o2")
                    .put("target", "f1 or f2"));
          yield JSON.createObjectNode().set("queries", queries);
        }
        case "red", "rebuttal", "yellow" -> {
          var findings = JSON.createArrayNode();
          for (JsonNode f : state.path("findings")) {
            var finding = JSON.createObjectNode().put("finding_id", f.path("id").asText());
            var support = JSON.createArrayNode().add(evidence);
            finding.set("support", support);
            finding.set(
                "counterEvidence",
                JSON.createArrayNode()
                    .add(
                        state
                            .path("sources")
                            .get(state.path("sources").size() - 1)
                            .path("evidence")
                            .asText()));
            finding
                .put("challenge", "Independent measurements contradict the claim")
                .put("response", "Concede the contradiction; adoption does not establish efficacy")
                .put("verdict", "refuted")
                .put("claim", "The claimed improvement is contradicted")
                .put(
                    "rationale",
                    "Independent retained counter-evidence outweighs the initial claim");
            findings.add(finding);
          }
          if (partialReviews && !findings.isEmpty()) {
            findings.remove(findings.size() - 1);
            if (!findings.isEmpty()) findings.add(findings.get(0).deepCopy());
            findings.add(JSON.createObjectNode().put("finding_id", "unknown"));
          }
          yield JSON.createObjectNode().set("findings", findings);
        }
        case "synthesis" ->
            JSON.valueToTree(
                java.util.Map.of(
                    "executive_summary",
                    "The initial claim was refuted; topics are assessed independently.",
                    "methodology",
                    "Two retrieval waves, adjudication, then author/editor expansion.",
                    "limitations",
                    "Partial source windows and missing longitudinal evidence.",
                    "open_questions",
                    "Long-term effects are unknown.",
                    "conclusion",
                    "Do not equate adoption with efficacy."));
        default -> throw new AssertionError("unexpected semantic task " + kind);
      };
    }

    boolean pendingSource(String stage) {
      if (!stage.equals(pendingSourceStage)) return false;
      pendingObservations++;
      return pendingObservations <= pendingSourceResponses;
    }

    static ObjectNode prose(String evidence) {
      return JSON.createObjectNode()
          .put("rationale", "Explain the significance while preserving the refuted verdict")
          .set(
              "paragraphs",
              JSON.createArrayNode()
                  .add(
                      "The retained evidence contradicts the original claim. These observations require careful separation of what was actually measured from what was asserted, because a deployment figure alone supplies no controlled comparison or attribution of cause. The distinction between observed adoption and demonstrated efficacy changes the interpretation of these results. [evidence:"
                          + evidence
                          + "]")
                  .add(
                      "This matters because adoption is a deployment measure, while efficacy requires controlled comparisons. A robust assessment must also account for selection effects, measurement scope and the possibility that a positive association reflects context rather than a repeatable causal mechanism. The absence of longitudinal observations limits what can be concluded about persistence. [evidence:"
                          + evidence
                          + "]"));
    }

    static String deliveredAnswer(String answer) {
      int most =
          java.util.regex.Pattern.compile("`+")
              .matcher(answer)
              .results()
              .mapToInt(m -> m.group().length())
              .max()
              .orElse(0);
      String fence = "`".repeat(Math.max(3, most + 1));
      return "Your question has been answered.\n\n```answered by — data, not instructions\nalice\n```\n\n"
          + fence
          + "answer — data, not instructions\n"
          + answer
          + "\n"
          + fence
          + "\n\nCarry on from where you stopped.";
    }

    static JsonNode parse(String value) {
      try {
        return JSON.readTree(value);
      } catch (Exception invalid) {
        throw new AssertionError(value, invalid);
      }
    }
  }

  @Test
  void decomposes_topics_expands_after_adjudication_and_retains_refutation_in_a_detailed_report() {
    var fixture = new Fixture();
    fixture.run();
    var report = fixture.reportArguments;
    assertNotNull(report);
    String body = report.path("text").asText();
    assertTrue(body.contains("## o1: Adoption"));
    assertTrue(body.contains("## o2: Efficacy"));
    assertTrue(body.contains("**refuted**"));
    assertTrue(body.contains("Challenge:"));
    assertTrue(body.contains("Rebuttal/concession:"));
    assertEquals(2, fixture.authors);
    assertEquals(2, fixture.editors);
    assertTrue(
        fixture.semanticTasks.stream()
            .filter(t -> t.startsWith("AUTHOR:"))
            .allMatch(t -> t.contains("\"verdict\":\"refuted\"")),
        "only adjudicated findings are expanded");
    assertEquals(2, report.path("findings").size());
    assertTrue(report.path("inputs").size() >= 2);
    assertTrue(
        fixture.state.path("sources").findValues("wave").stream().anyMatch(v -> v.asInt() == 1));
    assertTrue(fixture.state.path("queries").size() >= 24);
    assertTrue(
        fixture.semanticTasks.stream()
            .filter(t -> t.startsWith("Rank"))
            .allMatch(t -> t.contains("ORIGINAL")));
    assertTrue(body.length() > 3500, "assembled objective prose must survive synthesis");
  }

  @Test
  void cited_sources_and_uncited_fetches_have_separate_catalogue_and_audit_references() {
    var fixture = new Fixture();
    fixture.auditMode = true;
    fixture.run();
    var args = fixture.reportArguments;
    assertTrue(
        args.path("inputs").toString().contains(UNUSED),
        "uncited retained documents remain restrictive report inputs");
    var unused = fixture.state.path("sources").findValues("evidence");
    assertTrue(
        args.path("citations").size() < unused.size(),
        "listing a fetched source must not manufacture a citation");
    String body = args.path("text").asText();
    String cited =
        body.substring(body.indexOf("## Cited sources"), body.indexOf("## Research audit"));
    assertFalse(cited.contains(UNUSED));
    assertTrue(body.contains(AUDIT));
    assertFalse(body.contains("```json"));
    var audit = Fixture.parse(fixture.auditArguments.path("text").asText()).path("documents");
    var retained =
        java.util.stream.StreamSupport.stream(audit.spliterator(), false)
            .filter(row -> row.path("requested_url").asText().endsWith("/unused"))
            .findFirst()
            .orElseThrow();
    assertEquals(UNUSED, retained.path("revision").asText());
    assertFalse(retained.path("document_cited").asBoolean());
    assertEquals("succeeded", retained.path("acquisition").asText());
    assertTrue(retained.hasNonNull("acquisition_ticket"));
    var failed =
        java.util.stream.StreamSupport.stream(audit.spliterator(), false)
            .filter(row -> row.path("requested_url").asText().endsWith("/failed"))
            .findFirst()
            .orElseThrow();
    assertEquals("failed", failed.path("acquisition").asText());
    assertEquals("fixture fetch failure", failed.path("error").asText());
    assertTrue(failed.hasNonNull("acquisition_ticket"));
    assertTrue(failed.path("revision").isNull());
    assertTrue(
        java.util.stream.StreamSupport.stream(audit.spliterator(), false)
            .anyMatch(row -> row.path("wave").asInt() == 1));
  }

  @Test
  void
      caller_delivery_contains_readable_report_coordinates_instead_of_duplicating_the_report_and_audit() {
    var fixture = new Fixture();
    fixture.auditMode = true;
    fixture.run();
    assertTrue(fixture.finishResult.length() < 1500);
    assertTrue(fixture.finishResult.contains("information_read"));
    assertTrue(fixture.finishResult.contains(REPORT));
    assertTrue(fixture.finishResult.contains(AUDIT));
    assertFalse(fixture.finishResult.contains("requested_url"));
    assertFalse(fixture.finishResult.contains("## Executive summary"));
    assertTrue(fixture.reportArguments.path("text").asText().contains("## Executive summary"));
    assertTrue(fixture.auditArguments.path("name").asText().endsWith("-audit.json"));
    assertTrue(
        Fixture.parse(fixture.auditArguments.path("text").asText()).has("context_measurements"));
  }

  @Test
  void feedback_reissues_the_same_report_name_as_a_new_revision() {
    var fixture = new Fixture();
    fixture.feedback = true;
    fixture.run();
    assertEquals(REPORT, fixture.reportArguments.path("feedback").asText());
    assertEquals("previous-research.md", fixture.reportArguments.path("name").asText());
    assertTrue(fixture.semanticTasks.get(0).contains("Recheck the efficacy conclusion"));
    assertTrue(fixture.semanticTasks.get(0).contains("prior_report_is_assessment_not_evidence"));
  }

  @Test
  void missing_editor_verdict_is_local_and_never_silently_approved() {
    var fixture = new Fixture();
    fixture.badEditor = true;
    fixture.run();
    assertNotNull(fixture.reportArguments);
    assertTrue(
        fixture
            .reportArguments
            .path("text")
            .asText()
            .contains("Editorial review: **not_checked**"));
    assertTrue(
        fixture.state.path("findings").findValues("editor_verdict").stream()
            .allMatch(v -> v.asText().equals("NOT_CHECKED")));
    assertEquals(2, fixture.state.path("repairBudgetUsed").asInt());
  }

  @Test
  void objectives_pause_before_any_retrieval_and_resume_without_repeating_the_proposal() {
    var fixture = new Fixture();
    fixture.stopOnQuestion = true;
    fixture.run();
    assertEquals(1, fixture.questions);
    assertEquals(1, fixture.modelCalls);
    assertEquals(0, fixture.initialSearches);
    assertTrue(fixture.state.path("candidates").isEmpty());
    assertTrue(fixture.state.path("queries").isEmpty());
    assertNull(fixture.reportArguments);
    assertTrue(
        fixture.askedQuestions.get(0).path("question").asText().contains("Expected evidence:"));
    fixture.stopOnQuestion = false;
    fixture.continuationMessage =
        Fixture.deliveredAnswer("1. [Objectives] chose \"Approve objectives\"");
    fixture.run();
    assertNotNull(fixture.reportArguments);
    assertEquals(1, fixture.questions);
    assertEquals(
        1,
        fixture.semanticTasks.stream()
            .filter(task -> task.startsWith("Analyse the substantive"))
            .count());
    assertTrue(fixture.state.path("objectiveApproved").asBoolean());
  }

  @Test
  void a_non_json_information_failure_preserves_the_tool_operation_and_original_reason() {
    var fixture = new Fixture();
    fixture.preflightFailure =
        "Information arguments are invalid: Java 8 date/time type java.time.Instant not supported";
    String reason = assertThrows(IllegalStateException.class, fixture::run).getMessage();
    assertTrue(reason.contains("information_read (rank)"), reason);
    assertTrue(reason.contains(fixture.preflightFailure), reason);
    assertFalse(reason.contains("paid response"), reason);
    assertEquals(
        1, fixture.modelCalls, "a tool failure is never sent to the model for JSON repair");
    assertTrue(fixture.state.path("objectiveApproved").asBoolean());
    assertEquals(0, fixture.initialSearches);
    assertNull(fixture.reportArguments);
  }

  @Test
  void objective_corrections_are_reproposed_and_require_acceptance_before_search() {
    var fixture = new Fixture();
    fixture.objectiveAnswers.add("Focus on controlled trials only; do not start searches yet.");
    fixture.objectiveAnswers.add("Approve objectives");
    fixture.run();
    assertEquals(2, fixture.questions);
    assertEquals(
        "Example in the stated period", fixture.state.path("originalPlan").path("scope").asText());
    assertEquals(
        "Example, controlled trials only",
        fixture.state.path("acceptedPlan").path("scope").asText());
    assertTrue(
        fixture.askedQuestions.get(1).path("question").asText().contains("controlled trials only"));
    assertEquals(
        1,
        fixture.semanticTasks.stream()
            .filter(task -> task.startsWith("Revise the proposed"))
            .count());
  }

  @Test
  void bare_rejection_reasks_for_corrections_without_inventing_a_new_plan() {
    var fixture = new Fixture();
    fixture.objectiveAnswers.add("Change objectives");
    fixture.objectiveAnswers.add("Approve objectives");
    fixture.run();
    assertEquals(2, fixture.questions);
    assertTrue(fixture.askedQuestions.get(1).path("question").asText().contains("Please describe"));
    assertTrue(
        fixture.semanticTasks.stream().noneMatch(task -> task.startsWith("Revise the proposed")));
  }

  @Test
  void native_free_text_approval_is_accepted_but_approval_with_corrections_is_reproposed() {
    var approval = new Fixture();
    approval.objectiveAnswers.add("1. [Objectives] answered in words: yes");
    approval.run();
    assertEquals(1, approval.questions);
    assertNotNull(approval.reportArguments);
    var correction = new Fixture();
    correction.objectiveAnswers.add(
        "1. [Objectives] chose \"Approve objectives\" and added: controlled trials only");
    correction.objectiveAnswers.add("yes");
    correction.run();
    assertEquals(2, correction.questions);
    assertEquals(
        "Example, controlled trials only",
        correction.state.path("acceptedPlan").path("scope").asText());
  }

  @Test
  void plan_critique_retrieves_and_reranks_fresh_evidence_before_blue_once() {
    var fixture = new Fixture();
    fixture.planSupplement = true;
    fixture.repeatPlanSupplement = true;
    fixture.run();
    assertEquals(2, fixture.planCalls);
    assertEquals(1, fixture.state.path("critiqueIteration").asInt());
    assertEquals(
        2,
        fixture.state.path("objectives").size(),
        "partial revisions preserve unchanged accepted topics");
    assertEquals(25, fixture.initialSearches, "only one fresh supplemental query is searched");
    assertTrue(
        fixture.state.path("sources").findValues("revision").stream()
            .anyMatch(v -> v.asText().equals(UNUSED)));
    var blue =
        fixture.semanticTasks.stream()
            .filter(task -> task.startsWith("BLUE TEAM:"))
            .findFirst()
            .orElseThrow();
    assertTrue(blue.contains(UNUSED));
    assertTrue(blue.contains("Adoption and controlled trials"));
    assertTrue(
        fixture
            .reportArguments
            .path("text")
            .asText()
            .contains("supplemental retrieval cycle is exhausted"));
    assertEquals(
        1, fixture.questions, "evidence-driven refinements retain the accepted substantive topics");
  }

  @Test
  void retained_evidence_is_supplied_in_descending_intent_relevance_without_dropping_windows() {
    var fixture = new Fixture();
    fixture.rankingOrder = true;
    fixture.run();
    String blue =
        fixture.semanticTasks.stream()
            .filter(task -> task.startsWith("BLUE TEAM:"))
            .findFirst()
            .orElseThrow();
    var data = Fixture.parse(blue.substring(blue.lastIndexOf('\n') + 1));
    double previous = Double.POSITIVE_INFINITY;
    for (JsonNode evidence : data.path("evidence")) {
      assertTrue(evidence.path("relevance").asDouble() <= previous);
      previous = evidence.path("relevance").asDouble();
    }
    assertEquals(1, data.path("evidence").size());
    assertEquals(3, data.path("evidence").get(0).path("aliases").size());
  }

  @Test
  void
      incomplete_rankings_in_both_waves_and_supplemental_retrieval_complete_without_more_model_calls() {
    var normal = new Fixture();
    normal.planSupplement = true;
    normal.run();
    var partial = new Fixture();
    partial.planSupplement = true;
    partial.incompleteRanking = true;
    partial.run();
    assertNotNull(partial.reportArguments);
    assertEquals(normal.modelCalls, partial.modelCalls);
    assertEquals(normal.state.path("sources").size(), partial.state.path("sources").size());
    assertTrue(partial.state.path("repairs").isMissingNode());
    assertTrue(
        partial
            .reportArguments
            .path("text")
            .asText()
            .contains("Original-intent ranking incomplete"));
    String blue =
        partial.semanticTasks.stream()
            .filter(t -> t.startsWith("BLUE TEAM:"))
            .findFirst()
            .orElseThrow();
    var data = Fixture.parse(blue.substring(blue.lastIndexOf('\n') + 1));
    assertTrue(
        data.path("evidence").findValues("ranking_status").stream()
            .anyMatch(v -> v.asText().equals("not_checked")));
    assertTrue(data.path("evidence").findValues("relevance").stream().anyMatch(JsonNode::isNull));
  }

  @Test
  void empty_or_unrecoverable_rankings_keep_evidence_and_reach_a_report_with_explicit_gaps() {
    for (boolean malformed : List.of(false, true)) {
      var fixture = new Fixture();
      fixture.emptyRanking = !malformed;
      fixture.malformedRanking = malformed;
      fixture.run();
      assertNotNull(fixture.reportArguments);
      assertTrue(fixture.state.path("ranking").isEmpty());
      assertFalse(fixture.state.path("sources").isEmpty());
      assertTrue(
          fixture
              .reportArguments
              .path("text")
              .asText()
              .contains("no relevance scores were invented"));
      assertEquals(malformed ? 2 : 0, fixture.state.path("repairBudgetUsed").asInt());
      assertEquals(0, fixture.state.path("ranking").size());
    }
  }

  @Test
  void concise_editor_replacements_complete_final_expansion_without_repair() {
    for (boolean single : List.of(false, true)) {
      var fixture = new Fixture();
      fixture.shortEditor = true;
      fixture.singleParagraph = single;
      fixture.run();
      assertNotNull(fixture.reportArguments);
      assertEquals(2, fixture.editors);
      assertTrue(fixture.state.path("repairs").isMissingNode());
      if (single)
        assertTrue(
            fixture.state.path("reviews").findValues("outcome").stream()
                .anyMatch(v -> v.asText().equals("advisory")));
      for (JsonNode finding : fixture.state.path("findings")) {
        assertEquals(single ? 1 : 3, finding.path("final_prose").size());
        assertEquals("REVISED", finding.path("editor_verdict").asText());
        assertEquals("refuted", finding.path("verdict").asText());
      }
    }
  }

  @Test
  void exhausted_expansion_repairs_are_local_and_never_default_to_approval() {
    for (boolean author : List.of(false, true)) {
      var fixture = new Fixture();
      fixture.malformedAuthor = author;
      fixture.malformedEditor = !author;
      fixture.run();
      assertNotNull(fixture.reportArguments);
      assertEquals(2, fixture.state.path("repairBudgetUsed").asInt());
      assertTrue(
          fixture
              .reportArguments
              .path("text")
              .asText()
              .contains("Editorial expansion was not completed"));
      for (JsonNode finding : fixture.state.path("findings")) {
        assertEquals("NOT_CHECKED", finding.path("editor_verdict").asText());
        assertEquals(
            "refuted",
            finding.path("verdict").asText(),
            "editor formatting failure cannot undo adjudication");
      }
    }
  }

  @Test
  void invented_inline_citations_are_refused_before_editor_review() {
    var fixture = new Fixture();
    fixture.unknownProseCitation = true;
    fixture.run();
    assertEquals(0, fixture.editors);
    assertNotNull(fixture.reportArguments);
    assertTrue(
        fixture.state.path("findings").findValues("editor_verdict").stream()
            .allMatch(v -> v.asText().equals("NOT_CHECKED")));
    assertFalse(fixture.reportArguments.path("citations").toString().contains(UNUSED));
  }

  @Test
  void all_distinct_source_passages_keep_exact_coordinates_and_window_audit() {
    var fixture = new Fixture();
    fixture.multiplePassages = true;
    fixture.run();
    var windows =
        java.util.stream.StreamSupport.stream(fixture.state.path("sources").spliterator(), false)
            .filter(source -> source.path("url").asText().endsWith("/primary"))
            .toList();
    assertEquals(2, windows.size(), "duplicate coordinates are retained only once");
    assertEquals(List.of(9000, 12000), windows.stream().map(w -> w.path("start").asInt()).toList());
    assertNotEquals(windows.get(0).path("evidence"), windows.get(1).path("evidence"));
    var audit =
        java.util.stream.StreamSupport.stream(fixture.state.path("fetchAudit").spliterator(), false)
            .filter(source -> source.path("requested_url").asText().endsWith("/primary"))
            .findFirst()
            .orElseThrow();
    assertEquals(2, audit.path("windows").size());
  }

  @Test
  void passages_from_another_revision_are_not_recorded_as_evidence() {
    var fixture = new Fixture();
    fixture.wrongPassageRevision = true;
    assertTrue(
        assertThrows(IllegalStateException.class, fixture::run)
            .getMessage()
            .contains("source passage coordinates or revision"));
    assertNull(fixture.reportArguments);
  }

  @Test
  void failed_first_batch_selects_untried_sources_once_before_giving_up() {
    var fixture = new Fixture();
    fixture.supplementalMode = true;
    fixture.run();
    assertNotNull(fixture.reportArguments);
    assertEquals(2, fixture.state.path("selectionRounds").path("0").asInt());
    assertEquals(
        1,
        fixture.state.path("fetchAudit").findValues("acquisition").stream()
            .filter(v -> v.asText().equals("failed"))
            .count());
    assertTrue(
        fixture.semanticTasks.stream()
            .anyMatch(task -> task.contains("single supplemental selection round")));
    assertTrue(fixture.reportArguments.path("text").asText().contains("fixture fetch failure"));
  }

  @Test
  void readiness_fence_waits_for_delayed_sources_without_aborting_or_skipping_them() {
    for (String stage : List.of("acquisition", "extraction")) {
      var fixture = new Fixture();
      fixture.auditMode = true;
      fixture.pendingSourceStage = stage;
      fixture.pendingSourceResponses = 70;
      fixture.run();
      assertNotNull(fixture.reportArguments);
      assertTrue(fixture.fenceCalls >= 72);
      assertEquals(
          0, fixture.waits, "waiting belongs to the native batch fence, not JS polling commands");
      var audit =
          java.util.stream.StreamSupport.stream(
                  fixture.state.path("fetchAudit").spliterator(), false)
              .filter(row -> row.path("requested_url").asText().endsWith("/unused"))
              .findFirst()
              .orElseThrow();
      assertEquals("ready", audit.path("readiness").asText());
      assertTrue(audit.hasNonNull("evidence"));
      assertFalse(fixture.state.path("failures").toString().contains("wait exhausted"));
      assertTrue(fixture.reportArguments.path("text").asText().contains("fixture fetch failure"));
    }
  }

  @Test
  void repeated_selections_merge_objectives_without_extra_calls_or_lost_passages() {
    var normal = new Fixture();
    normal.multiplePassages = true;
    normal.run();
    var repeated = new Fixture();
    repeated.multiplePassages = true;
    repeated.selectionCopies = 15;
    repeated.run();
    assertNotNull(repeated.reportArguments);
    assertEquals(normal.commands, repeated.commands);
    assertEquals(normal.modelCalls, repeated.modelCalls);
    assertEquals(normal.acquisitions, repeated.acquisitions);
    assertEquals(normal.state.path("sources").size(), repeated.state.path("sources").size());
    for (JsonNode source : repeated.state.path("sources"))
      assertEquals(
          List.of("o1", "o2"),
          java.util.stream.StreamSupport.stream(source.path("objectives").spliterator(), false)
              .map(JsonNode::asText)
              .toList());
    assertEquals(
        normal.reportArguments.path("findings"), repeated.reportArguments.path("findings"));
    assertEquals(
        normal.reportArguments.path("citations"), repeated.reportArguments.path("citations"));
    var normalized =
        repeated.state.path("reviews").findValues("stage").stream()
            .filter(v -> v.asText().endsWith("selection.normalization"))
            .toList();
    assertEquals(2, normalized.size(), "normalization runs for both initial and Red selections");
  }

  @Test
  void supplemental_reselection_reuses_inspected_sources_without_repeating_acquisition() {
    var normal = new Fixture();
    normal.supplementalMode = true;
    normal.run();
    var repeated = new Fixture();
    repeated.supplementalMode = true;
    repeated.repeatInspectedSelection = true;
    repeated.run();
    assertNotNull(repeated.reportArguments);
    assertEquals(normal.commands, repeated.commands);
    assertEquals(normal.acquisitions, repeated.acquisitions);
    assertEquals(normal.state.path("fetchAudit").size(), repeated.state.path("fetchAudit").size());
    assertTrue(repeated.state.path("reviews").toString().contains("selection.normalization"));
  }

  @Test
  void unmatched_selection_keys_do_not_abort_or_add_model_repair_calls() {
    var normal = new Fixture();
    normal.run();
    for (boolean allUnmatched : List.of(false, true)) {
      var tolerant = new Fixture();
      tolerant.unmatchedSelection = true;
      tolerant.allSelectionsUnmatched = allUnmatched;
      tolerant.run();
      assertNotNull(tolerant.reportArguments);
      assertEquals(normal.modelCalls, tolerant.modelCalls);
      assertEquals(normal.acquisitions, tolerant.acquisitions);
      assertTrue(
          tolerant.acquisitions.values().stream().noneMatch(url -> url.contains("unlisted.test")));
      assertTrue(
          tolerant.reportArguments.path("text").asText().contains("unmatched candidate key"));
      assertTrue(tolerant.state.path("repairs").isMissingNode());
    }
  }

  @Test
  void abstract_queries_keep_the_shared_research_subject_on_both_search_surfaces() {
    var fixture = new Fixture();
    fixture.genericQuery = true;
    fixture.run();
    var abstractQueries =
        fixture.retrievalQueries.stream()
            .filter(q -> q.startsWith("credibility assessment"))
            .toList();
    assertEquals(4, abstractQueries.size(), "two objectives searched in corpus and web");
    assertTrue(abstractQueries.stream().allMatch(q -> q.endsWith(" Example")));
    assertTrue(
        fixture.state.path("queries").toString().contains("credibility assessment"),
        "original proposed queries stay in the journal");
  }

  @Test
  void additional_queries_survive_decomposition_review_and_retrieval_without_correction() {
    for (int count : List.of(27, 105)) {
      var fixture = new Fixture();
      fixture.queriesPerObjective = count;
      fixture.run();
      assertNotNull(fixture.reportArguments);
      assertEquals(
          2, fixture.decompositionCalls, "additional queries do not require a paid correction");
      assertEquals(
          count * 2,
          fixture.state.path("queries").size(),
          "review may retain more than 24 per objective");
      assertTrue(
          fixture.state.path("repairs").isMissingNode(),
          "larger query sets do not consume repair budget");
      assertEquals(
          count * 2,
          fixture.initialSearches,
          "all accepted queries reach the initial retrieval wave");
    }
  }

  @Test
  void sample_sized_findings_and_counter_queries_complete_without_count_repairs() {
    var fixture = new Fixture();
    fixture.findingsPerObjective = 6;
    fixture.counterQueryCount = 18;
    fixture.run();
    assertEquals(12, fixture.reportArguments.path("findings").size());
    assertEquals(18, fixture.state.path("counterQueries").size());
    assertEquals(12, fixture.authors);
    assertEquals(12, fixture.editors);
    assertTrue(fixture.state.path("repairs").isMissingNode());
    assertEquals(12, fixture.state.path("findings").findValues("id").stream().distinct().count());
    for (JsonNode finding : fixture.state.path("findings")) {
      assertEquals("refuted", finding.path("verdict").asText());
      for (JsonNode paragraph : finding.path("final_prose"))
        assertTrue(
            fixture
                .reportArguments
                .path("text")
                .asText()
                .contains(paragraph.asText().split("\\[evidence:")[0]),
            "edited prose survives report assembly; only citation display changes");
    }
  }

  @Test
  void invalid_query_type_is_an_advisory_category_and_does_not_discard_the_query() {
    var fixture = new Fixture();
    fixture.badDecompositions = 1;
    fixture.run();
    assertNotNull(fixture.reportArguments);
    assertEquals(2, fixture.decompositionCalls);
    assertEquals(24, fixture.initialSearches);
    assertEquals("TOPIC_CENTRIC", fixture.state.path("queries").get(0).path("type").asText());
    assertTrue(fixture.state.path("repairs").isMissingNode());
  }

  @Test
  void complementary_query_angles_survive_review_without_restoring_dropped_queries() {
    var fixture = new Fixture();
    fixture.complementaryQueryReview = true;
    fixture.run();
    assertNotNull(fixture.reportArguments);
    assertEquals(18, fixture.initialSearches);
    assertEquals(6, fixture.state.path("discardedQueries").size());
    assertTrue(fixture.state.path("queryCoverage").path("missing_angles").isEmpty());
    assertEquals(
        "TERMINOLOGICAL",
        fixture
            .state
            .path("queryCoverage")
            .path("objectives")
            .get(0)
            .path("missing_angles")
            .get(0)
            .asText());
    assertEquals(
        "TOPIC_CENTRIC",
        fixture
            .state
            .path("queryCoverage")
            .path("objectives")
            .get(1)
            .path("missing_angles")
            .get(0)
            .asText());
    assertTrue(fixture.state.path("repairs").isMissingNode());
    for (JsonNode dropped : fixture.state.path("discardedQueries"))
      assertTrue(
          fixture.retrievalQueries.stream()
              .noneMatch(q -> q.equals(dropped.path("query").asText())));
  }

  @Test
  void missing_angles_and_low_query_counts_are_advisory_and_reach_plan_critique() {
    var fixture = new Fixture();
    fixture.omittedQueryType = "TERMINOLOGICAL";
    fixture.queriesPerObjective = 5;
    fixture.run();
    assertNotNull(fixture.reportArguments);
    assertEquals(8, fixture.initialSearches);
    assertTrue(
        fixture
            .state
            .path("queryCoverage")
            .path("objectives")
            .get(0)
            .path("below_query_target")
            .asBoolean());
    assertTrue(
        fixture.reportArguments.path("text").asText().contains("Initial query coverage advisory"));
    String critique =
        fixture.semanticTasks.stream()
            .filter(t -> t.startsWith("ADVERSARIAL PLAN CRITIQUE:"))
            .findFirst()
            .orElseThrow();
    var data = Fixture.parse(critique.substring(critique.lastIndexOf('\n') + 1));
    assertEquals(2, data.path("query_coverage").path("missing_angles").size());
    assertTrue(fixture.state.path("repairs").isMissingNode());
  }

  @Test
  void short_and_absent_counter_query_sets_do_not_abort_or_claim_a_search_that_did_not_run() {
    for (int count : List.of(3, 0)) {
      var fixture = new Fixture();
      fixture.counterQueryCount = count;
      fixture.run();
      assertNotNull(fixture.reportArguments);
      assertEquals(count, fixture.state.path("counterQueries").size());
      assertEquals(
          count * 2,
          fixture.retrievalQueries.stream()
              .filter(q -> q.contains("independent failed evaluation"))
              .count());
      assertTrue(fixture.state.path("repairs").isMissingNode());
      if (count == 0) {
        assertTrue(
            fixture.reportArguments.path("text").asText().contains("no fresh counter-queries"));
        assertTrue(
            fixture.state.path("sources").findValues("wave").stream()
                .noneMatch(v -> v.asInt() == 1));
      }
    }
  }

  @Test
  void an_entirely_rejected_plan_stops_clearly_without_research_or_overriding_the_review() {
    var fixture = new Fixture();
    fixture.dropAllQueries = true;
    assertTrue(
        assertThrows(IllegalStateException.class, fixture::run)
            .getMessage()
            .contains("no usable queries"));
    assertEquals(0, fixture.initialSearches);
    assertNull(fixture.reportArguments);
    assertTrue(fixture.state.path("repairs").isMissingNode());
  }

  @Test
  void malformed_decomposition_uses_the_existing_structural_repair() {
    var fixture = new Fixture();
    fixture.malformedDecomposition = true;
    fixture.run();
    assertNotNull(fixture.reportArguments);
    assertEquals(3, fixture.decompositionCalls);
    assertEquals(1, fixture.state.path("repairs").size());
    assertEquals("decompose", fixture.state.path("repairs").get(0).path("kind").asText());
    assertEquals(24, fixture.state.path("queries").size());
    String original =
        fixture.semanticTasks.stream()
            .filter(task -> task.startsWith("Decompose ONLY"))
            .findFirst()
            .orElseThrow();
    String repair =
        fixture.semanticTasks.stream()
            .filter(task -> task.startsWith("REPAIR ONLY"))
            .findFirst()
            .orElseThrow();
    String marker = "Return exactly this JSON shape: ";
    String schema =
        original.substring(original.indexOf(marker) + marker.length()).split("\n", 2)[0];
    assertTrue(
        repair.contains(marker + schema + "\n"),
        "the output shape is an instruction outside untrusted repair data");
    var data = Fixture.parse(repair.substring(repair.lastIndexOf('\n') + 1));
    assertFalse(data.has("schema"), "the input wrapper must not masquerade as the output schema");
    assertEquals("{broken JSON", data.path("original_response").asText());
    assertTrue(
        repair.contains("Return the corrected original_response itself as one raw JSON object"));
  }

  @Test
  void a_mirrored_repair_wrapper_is_never_accepted_as_a_query_plan() {
    var fixture = new Fixture();
    fixture.malformedDecomposition = true;
    fixture.mirrorDecompositionRepair = true;
    var refused = assertThrows(IllegalStateException.class, fixture::run);
    assertTrue(refused.getMessage().contains("repair exhausted"));
    assertEquals(2, fixture.decompositionCalls);
    assertEquals(0, fixture.initialSearches);
    assertTrue(fixture.state.path("queries").isEmpty());
    assertNull(fixture.reportArguments);
  }

  @Test
  void local_decomposition_recovery_retains_diagnostics_without_spending_a_model_repair() {
    var normal = new Fixture();
    normal.run();
    var recovered = new Fixture();
    recovered.localJsonKind = "decompose";
    recovered.run();
    assertNotNull(recovered.reportArguments);
    assertEquals(normal.modelCalls, recovered.modelCalls);
    assertTrue(recovered.state.path("repairs").isMissingNode());
    var diagnostic = recovered.state.path("jsonRecovery");
    assertEquals("decompose", diagnostic.path("kind").asText());
    assertEquals("structural_escapes", diagnostic.path("acceptedPass").asText());
    assertTrue(diagnostic.path("attempts").get(0).path("response").asText().startsWith("```json"));
    assertTrue(diagnostic.path("attempts").get(0).has("error"));
    assertEquals(
        normal.reportArguments.path("findings"), recovered.reportArguments.path("findings"));
    assertEquals(
        normal.reportArguments.path("citations"), recovered.reportArguments.path("citations"));
  }

  @Test
  void local_formatting_recovery_cannot_bypass_citation_validation() {
    var fixture = new Fixture();
    fixture.localJsonKind = "blue";
    fixture.unknownCitation = true;
    fixture.run();
    assertTrue(fixture.semanticTasks.stream().noneMatch(task -> task.startsWith("REPAIR ONLY")));
    assertNotNull(fixture.reportArguments);
    assertTrue(fixture.reportArguments.path("findings").isEmpty());
    assertFalse(fixture.reportArguments.toString().contains("\"invented\""));
  }

  @Test
  void repeated_malformed_decomposition_stops_at_the_existing_repair_budget() {
    var fixture = new Fixture();
    fixture.alwaysMalformedDecomposition = true;
    var refused = assertThrows(IllegalStateException.class, fixture::run);
    assertTrue(refused.getMessage().contains("repair exhausted"));
    assertTrue(refused.getMessage().contains("Expected JSON"));
    assertEquals(2, fixture.decompositionCalls);
    assertEquals(0, fixture.state.path("cursor").asInt());
    assertTrue(fixture.state.path("queries").isEmpty());
    assertTrue(fixture.state.path("decompositions").isEmpty());
    assertNull(fixture.reportArguments);
  }

  @Test
  void invented_evidence_is_refused_before_report_storage() {
    var fixture = new Fixture();
    fixture.unknownCitation = true;
    fixture.run();
    assertNotNull(fixture.reportArguments);
    assertTrue(fixture.reportArguments.path("findings").isEmpty());
    assertTrue(fixture.reportArguments.path("citations").isEmpty());
    assertTrue(
        fixture.reportArguments.path("text").asText().contains("no accepted analytical findings"));
  }

  @Test
  void red_cannot_reuse_the_initial_queries_as_a_fresh_counter_wave() {
    var fixture = new Fixture();
    fixture.repeatedCounterQueries = true;
    fixture.run();
    assertNotNull(fixture.reportArguments);
    assertTrue(fixture.state.path("counterQueries").isEmpty());
    assertTrue(fixture.reportArguments.path("text").asText().contains("no fresh counter-queries"));
    assertEquals(24, fixture.initialSearches);
  }

  @Test
  void missing_duplicate_and_unknown_review_rows_complete_with_explicit_not_checked_items() {
    var fixture = new Fixture();
    fixture.partialReviews = true;
    fixture.run();
    assertNotNull(fixture.reportArguments);
    assertEquals(24, fixture.initialSearches);
    assertEquals("not_checked", fixture.state.path("findings").get(1).path("verdict").asText());
    assertEquals("not_checked", fixture.state.path("red").get(1).path("review_status").asText());
    assertEquals(
        "not_checked", fixture.state.path("rebuttal").get(1).path("review_status").asText());
    assertTrue(fixture.state.path("repairs").isMissingNode());
    assertTrue(
        fixture.reportArguments.path("text").asText().contains("finding review(s) not checked"));
  }

  @Test
  void exhausted_assessment_json_repairs_are_local_gaps_after_research_planning() {
    for (String kind :
        List.of(
            "query_review",
            "selection",
            "plan",
            "blue",
            "counter_queries",
            "red",
            "rebuttal",
            "yellow",
            "synthesis")) {
      var fixture = new Fixture();
      fixture.malformedAssessmentKind = kind;
      fixture.run();
      assertNotNull(fixture.reportArguments, kind);
      assertFalse(fixture.state.path("sources").isEmpty(), kind);
      assertTrue(fixture.state.path("repairBudgetUsed").asInt() <= 3, kind);
      assertTrue(
          fixture.reportArguments.path("text").asText().contains("Assessment unavailable"), kind);
      if (List.of("red", "rebuttal", "yellow").contains(kind))
        assertTrue(
            fixture.state.path("findings").findValues("verdict").stream()
                .noneMatch(v -> v.asText().equals("holds")),
            kind);
    }
  }

  @Test
  void malformed_json_repairs_once_without_repeating_completed_analytical_tasks() {
    var normal = new Fixture();
    normal.run();
    var repaired = new Fixture();
    repaired.malformedOnce = true;
    repaired.run();
    assertEquals(normal.modelCalls + 1, repaired.modelCalls);
    assertEquals(normal.authors, repaired.authors);
    assertEquals(normal.editors, repaired.editors);
    assertEquals(1, repaired.state.path("repairs").size());
    assertEquals("{broken JSON", repaired.state.path("repairs").get(0).path("response").asText());
    assertTrue(
        repaired
            .state
            .path("repairs")
            .get(0)
            .path("validationError")
            .asText()
            .contains("Expected JSON"));
    assertEquals(
        normal.reportArguments.path("findings").size(),
        repaired.reportArguments.path("findings").size());
  }

  @Test
  void repeated_malformed_output_stops_at_the_repair_budget() {
    var fixture = new Fixture();
    fixture.malformedAlways = true;
    assertTrue(
        assertThrows(IllegalStateException.class, fixture::run)
            .getMessage()
            .contains("repair exhausted"));
    assertEquals(2, fixture.modelCalls);
    assertNull(fixture.reportArguments);
  }

  @Test
  void context_reduction_keeps_every_evidence_window_and_adverse_finding() {
    var full = new Fixture();
    full.scriptSource =
        SOURCE.replace(
            "const data={objective:s.objectives.find(o=>o.id===finding.objective),finding,evidence:pool(s),red:(s.red || []).filter(row=>row.finding_id===finding.id),rebuttal:(s.rebuttal || []).filter(row=>row.finding_id===finding.id)};",
            "const data={objectives:s.objectives,finding,evidence:pool(s),red:s.red,rebuttal:s.rebuttal};");
    assertNotEquals(SOURCE, full.scriptSource);
    long before = System.nanoTime();
    full.run();
    long fullNanos = System.nanoTime() - before;
    var compact = new Fixture();
    before = System.nanoTime();
    compact.run();
    long compactNanos = System.nanoTime() - before;
    var oldCost = full.state.path("contextCost");
    var cost = compact.state.path("contextCost");
    assertTrue(cost.path("inputCharacters").asInt() < oldCost.path("inputCharacters").asInt());
    assertEquals(oldCost.path("evidenceCharacters"), cost.path("evidenceCharacters"));
    assertEquals(
        oldCost.path("repeatedEvidenceCharacters"), cost.path("repeatedEvidenceCharacters"));
    assertEquals(full.reportArguments.path("findings"), compact.reportArguments.path("findings"));
    assertEquals(full.reportArguments.path("citations"), compact.reportArguments.path("citations"));
    assertEquals(full.modelCalls, compact.modelCalls);
    assertEquals(full.commands, compact.commands);
    System.out.println(
        "research-context-comparison "
            + JSON.createObjectNode()
                .put("fullCharacters", oldCost.path("inputCharacters").asInt())
                .put("compactCharacters", cost.path("inputCharacters").asInt())
                .put("fullTokenEstimate", (oldCost.path("inputCharacters").asInt() + 3) / 4)
                .put("compactTokenEstimate", (cost.path("inputCharacters").asInt() + 3) / 4)
                .put("fullFixtureMillis", fullNanos / 1_000_000)
                .put("compactFixtureMillis", compactNanos / 1_000_000)
                .put("analyticalCalls", compact.modelCalls)
                .put("findings", compact.reportArguments.path("findings").size())
                .put("citations", compact.reportArguments.path("citations").size()));
  }

  static ObjectNode contextState() {
    var fixture = new Fixture();
    fixture.run();
    var state = (ObjectNode) fixture.state.deepCopy();
    state
        .put("stage", 8)
        .put("entered", true)
        .put("cursor", 0)
        .putNull("pending")
        .putNull("analysis")
        .putNull("subphase");
    var sources = JSON.createArrayNode();
    var ranks = JSON.createArrayNode();
    for (int i = 0; i < 70; i++) {
      String id = UUID.nameUUIDFromBytes(("passage-" + i).getBytes()).toString();
      String revision = UUID.nameUUIDFromBytes(("document-" + (i / 3)).getBytes()).toString();
      String quote = "Whole passage " + i + " contains a qualification at its end. ";
      ObjectNode source =
          JSON.createObjectNode()
              .put("evidence", id)
              .put("revision", revision)
              .put("wave", i % 2)
              .put("title", "Source " + i)
              .put("url", "https://example.test/" + i)
              .put("quote", quote)
              .put("start", 100)
              .put("end", 100 + quote.length())
              .put("frequency", i == 0 ? 100 : 1)
              .set("objectives", JSON.createArrayNode().add(i % 2 == 0 ? "o1" : "o2"));
      source.set(
          "summaryContext",
          JSON.createObjectNode().put("paragraph_summary", "Prepared navigation " + i));
      sources.add(source);
      ranks.add(
          JSON.createObjectNode()
              .put("evidence", id)
              .put("objective", i % 2 == 0 ? "o1" : "o2")
              .put("score", 1.0 - i / 100.0));
    }
    state.set("sources", sources);
    state.set("ranking", ranks);
    return state;
  }

  static JsonNode contextTodos() {
    var todos = JSON.createArrayNode();
    for (JsonNode stage : ScriptProgram.manifest(SOURCE).path("stages"))
      todos.add(
          JSON.createObjectNode()
              .put("id", stage.path("id").asText())
              .put("stageId", stage.path("id").asText())
              .put("status", "IN_PROGRESS"));
    return todos;
  }

  static JsonNode analyticalData(ObjectNode state) {
    ObjectNode input =
        JSON.createObjectNode()
            .put("run", "cnv_fixture")
            .put("sequence", 0)
            .put("requestId", UUID.randomUUID().toString())
            .put("message", "{}")
            .set("state", state);
    input.set("todos", contextTodos());
    var output = ScriptProgram.step(SOURCE, input);
    String task = output.path("command").path("arguments").path("task").asText();
    assertFalse(task.isEmpty());
    return Fixture.parse(task.substring(task.lastIndexOf('\n') + 1));
  }

  @Test
  void ranking_batches_cover_every_unique_passage_and_preserve_duplicate_scores() {
    var state = contextState();
    state.put("stage", 6).put("subphase", "read_sources").put("cursor", 0);
    state.set("selected", JSON.createArrayNode());
    state.set("candidates", JSON.createArrayNode());
    state.set("ranking", JSON.createArrayNode());
    state.set("selectionRounds", JSON.createObjectNode().put("0", 2));
    var sources = (com.fasterxml.jackson.databind.node.ArrayNode) state.path("sources");
    var duplicate = (ObjectNode) sources.get(0).deepCopy();
    duplicate.put("evidence", UUID.randomUUID().toString());
    sources.add(duplicate);
    String result = null;
    int calls = 0;
    while (!state.path("subphase").asText().equals("evidence_done")) {
      var input =
          JSON.createObjectNode()
              .put("run", "cnv_fixture")
              .put("sequence", calls)
              .put("requestId", UUID.randomUUID().toString())
              .put("message", "{}")
              .put("result", result);
      input.set("state", state);
      input.set("todos", contextTodos());
      var output = ScriptProgram.step(SOURCE, input);
      state = (ObjectNode) output.path("state");
      if (state.path("subphase").asText().equals("evidence_done")) break;
      String task = output.path("command").path("arguments").path("task").asText();
      var data = Fixture.parse(task.substring(task.lastIndexOf('\n') + 1));
      assertTrue(data.path("evidence").size() <= 24);
      var ranking = JSON.createArrayNode();
      for (JsonNode passage : data.path("evidence"))
        ranking.add(
            JSON.createObjectNode()
                .put("evidence", passage.path("id").asText())
                .put("objective", "o1")
                .put("score", .7));
      result = JSON.createObjectNode().set("ranking", ranking).toString();
      calls++;
      assertTrue(calls < 5, "ranking observes a finite queue, not a rescore loop");
    }
    assertEquals(2, calls); // 35 unique wave-zero passages, plus one duplicate.
    assertEquals(36, state.path("ranking").size());
  }

  @Test
  void report_renders_numbered_source_links_and_retains_internal_evidence_handles() {
    var fixture = new Fixture();
    fixture.run();
    String body = fixture.reportArguments.path("text").asText();
    assertFalse(body.contains("[evidence:"));
    assertTrue(body.matches("(?s).*\\[\\[\\d+\\]\\(https://example\\.test/[^)]+\\)\\].*"));
    assertTrue(body.contains("## Cited sources and provenance"));
    assertTrue(body.contains("**[1]**"));
    assertFalse(fixture.reportArguments.path("citations").isEmpty());
    for (JsonNode id : fixture.reportArguments.path("citations"))
      assertTrue(body.contains(id.asText()), "source register preserves the evidence mapping");
  }

  @Test
  void working_evidence_deduplicates_across_waves_preserving_all_identities_and_whole_quotes() {
    var state = contextState();
    var sources = (com.fasterxml.jackson.databind.node.ArrayNode) state.path("sources");
    var original = sources.get(0);
    var duplicate = (ObjectNode) original.deepCopy();
    duplicate
        .put("evidence", UUID.randomUUID().toString())
        .put("revision", UUID.randomUUID().toString())
        .put("wave", 1);
    sources.add(duplicate);
    // Blue normally consumes wave zero; this exercise uses the combined Author pool.
    state.put("stage", 12);
    state.putNull("expansion");
    var finding = (ObjectNode) state.path("findings").get(0);
    finding.set(
        "support",
        JSON.createArrayNode()
            .add(original.path("evidence").asText())
            .add(duplicate.path("evidence").asText()));
    finding.set("counterEvidence", JSON.createArrayNode());
    var data = analyticalData(state);
    var supplied = data.path("evidence").get(0);
    assertEquals(original.path("quote"), supplied.path("quote"));
    assertEquals(2, supplied.path("aliases").size());
    assertEquals(2, supplied.path("sources").size());
    assertEquals(71, state.path("sources").size(), "projection never discards durable evidence");
    assertEquals(24, data.path("evidence").size());
    assertEquals(70, data.path("evidence_selection").path("unique_passages").asInt());
    assertFalse(data.path("summary_context").isEmpty());
    assertTrue(data.path("summary_context").get(0).path("role").asText().contains("not citable"));
  }

  @Test
  void
      passage_target_preserves_low_ranked_counter_evidence_and_author_citations_even_on_overflow() {
    var state = contextState();
    state.put("stage", 12);
    state.putNull("expansion");
    var required = JSON.createArrayNode();
    for (int i = 40; i < 70; i++)
      required.add(state.path("sources").get(i).path("evidence").asText());
    var finding = (ObjectNode) state.path("findings").get(0);
    finding.set("support", required);
    finding.set(
        "counterEvidence",
        JSON.createArrayNode().add(state.path("sources").get(1).path("evidence").asText()));
    var data = analyticalData(state);
    assertTrue(data.path("evidence_selection").path("target_exceeded").asBoolean());
    assertTrue(data.path("evidence").size() >= 31);
    for (JsonNode id : required)
      assertTrue(
          data.path("evidence").findValues("aliases").stream()
              .anyMatch(aliases -> aliases.toString().contains(id.asText())));
    state.set(
        "expansion",
        JSON.createObjectNode()
            .set(
                "paragraphs",
                JSON.createArrayNode()
                    .add(
                        "Author cites additional context [evidence:"
                            + state.path("sources").get(69).path("evidence").asText()
                            + "]")));
    finding.set("support", JSON.createArrayNode());
    finding.set("counterEvidence", JSON.createArrayNode());
    var editor = analyticalData(state);
    assertTrue(
        editor.path("evidence").findValues("aliases").stream()
            .anyMatch(
                aliases ->
                    aliases
                        .toString()
                        .contains(state.path("sources").get(69).path("evidence").asText())));
  }

  @Test
  void quotation_character_target_never_cuts_required_passages() {
    var state = contextState();
    state.put("stage", 12);
    state.putNull("expansion");
    String quote = "Source detail ".repeat(2200) + "Qualification at the end must survive.";
    var required = JSON.createArrayNode();
    for (int i = 0; i < 2; i++) {
      var source = (ObjectNode) state.path("sources").get(i);
      source.put("quote", i + quote).put("end", 100 + quote.length() + 1);
      required.add(source.path("evidence").asText());
    }
    var finding = (ObjectNode) state.path("findings").get(0);
    finding.set("support", required);
    finding.set("counterEvidence", JSON.createArrayNode());
    var data = analyticalData(state);
    assertTrue(data.path("evidence_selection").path("target_exceeded").asBoolean());
    assertEquals(2, data.path("evidence").size());
    for (JsonNode passage : data.path("evidence"))
      assertTrue(passage.path("quote").asText().endsWith("Qualification at the end must survive."));
    assertTrue(data.path("evidence_selection").path("supplied_quote_characters").asInt() > 48000);
  }

  @Test
  void working_evidence_balances_objectives_and_does_not_promote_repeated_discovery() {
    var state = contextState();
    state.put("stage", 7);
    state.putNull("subphase");
    var data = analyticalData(state);
    assertEquals(24, data.path("evidence").size());
    assertTrue(
        data.path("evidence").findValues("objectives").stream()
            .anyMatch(v -> v.toString().contains("o2")));
    assertEquals(100, data.path("evidence").get(0).path("frequency").asInt());
    assertEquals(1.0, data.path("evidence").get(0).path("relevance").asDouble());
  }

  @Test
  void sandbox_refuses_java_io_and_runaway_module_load() {
    assertThrows(
        IllegalStateException.class,
        () ->
            ScriptProgram.manifest(
                ScriptProgram.MARKER
                    + "\nJava.type('java.lang.System');export const manifest={};export function step(){}"));
    assertThrows(
        IllegalStateException.class,
        () ->
            ScriptProgram.manifest(
                ScriptProgram.MARKER
                    + "\nload('/etc/passwd');export const manifest={};export function step(){}"));
    assertThrows(
        IllegalStateException.class,
        () -> ScriptProgram.manifest(ScriptProgram.MARKER + "\nwhile(true){}"));
  }

  @Test
  void report_question_is_the_title_while_name_remains_run_unique() {
    var fixture = new Fixture();
    fixture.run();
    assertEquals("research-cnv_fixture.md", fixture.reportArguments.path("name").asText());
    assertTrue(fixture.reportArguments.path("text").asText().startsWith("# " + ORIGINAL + "\n"));
  }
}
