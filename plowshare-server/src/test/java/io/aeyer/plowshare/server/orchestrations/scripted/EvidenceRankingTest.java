package io.aeyer.plowshare.server.orchestrations.scripted;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Replays advisory ranking errors through the shipped Graal handler. */
class EvidenceRankingTest {
  private static final String PROBE =
      ScriptResearchTest.SOURCE.replace(
              "export function step(input) {", "function researchStep(input) {")
          + "\nexport function step(input) { accept(input.state,input); return {state:input.state,evidence:pool(input.state)}; }";

  private ObjectNode state() {
    var state = ScriptResearchTest.JSON.createObjectNode();
    state.putObject("pending").put("kind", "ranking").put("wave", 0);
    state.putArray("objectives").addObject().put("id", "o1");
    var sources = state.putArray("sources");
    for (String id : new String[] {ScriptResearchTest.BLUE, ScriptResearchTest.RED})
      sources.addObject().put("evidence", id).put("wave", 0).put("quote", "Retained exact text");
    state.putArray("ranking");
    state.putArray("reviews");
    state.putArray("failures");
    return state;
  }

  private JsonNode rank(ObjectNode state, JsonNode response) {
    var input =
        ScriptResearchTest.JSON
            .createObjectNode()
            .put("result", response.toString())
            .set("state", state);
    return ScriptProgram.step(PROBE, input);
  }

  @Test
  void production_response_preserves_all_twenty_one_passages_when_only_four_are_ranked()
      throws Exception {
    var fixture =
        ScriptResearchTest.JSON.readTree(
            Files.readString(Path.of("src/test/resources/scripted/ranking-incomplete.json")));
    var state = state();
    state.set("objectives", fixture.path("objectives"));
    state.withArray("sources").removeAll();
    for (JsonNode e : fixture.path("evidence"))
      state
          .withArray("sources")
          .addObject()
          .put("evidence", e.path("id").asText())
          .put("wave", 0)
          .put("title", e.path("title").asText());
    var result = rank(state, fixture.path("response"));
    assertEquals(8, result.path("state").path("ranking").size());
    assertEquals(21, result.path("evidence").size());
    assertEquals(
        17,
        result.path("evidence").findValues("ranking_status").stream()
            .filter(v -> v.asText().equals("not_checked"))
            .count());
    assertEquals(
        17,
        result.path("evidence").findValues("relevance").stream().filter(JsonNode::isNull).count());
    assertEquals("evidence_done", result.path("state").path("subphase").asText());
    assertTrue(result.path("state").path("failures").toString().contains("17 retained passage(s)"));
  }

  @Test
  void invalid_rows_are_omitted_duplicates_collapse_and_numeric_strings_do_not_abort() {
    var response =
        ScriptResearchTest.Fixture.parse(
            """
                {"ranking":[null,
                  {"evidence":"unlisted","objective":"o1","score":0.9},
                  {"evidence":"%1$s","objective":"unlisted","score":0.9},
                  {"evidence":"%2$s","objective":"o1","score":2},
                  {"evidence":"%2$s","objective":"o1","score":null},
                  {"evidence":"%2$s","objective":"o1","score":"NaN"},
                  {"evidence":" %1$s ","objective":" o1 ","score":"0.8"},
                  {"evidence":"%1$s","objective":"o1","score":0.3},
                  {"evidence":"%2$s","objective":"o1","score":0,"rationale":"Unrelated"}]}
                """
                .formatted(ScriptResearchTest.BLUE, ScriptResearchTest.RED));
    var state = state();
    state
        .withArray("sources")
        .addObject()
        .put("evidence", ScriptResearchTest.UNUSED)
        .put("wave", 0);
    var result = rank(state, response);
    assertEquals(2, result.path("state").path("ranking").size());
    assertEquals(0.8, result.path("evidence").get(0).path("relevance").asDouble());
    assertEquals(0, result.path("evidence").get(1).path("relevance").asDouble());
    assertEquals("ranked", result.path("evidence").get(1).path("ranking_status").asText());
    assertTrue(
        result.path("evidence").get(2).path("relevance").isNull(),
        "unranked evidence sorts after an assessed zero");
    var diagnostic =
        ScriptResearchTest.Fixture.parse(
            result.path("state").path("reviews").get(0).path("text").asText());
    assertEquals(6, diagnostic.path("omitted_count").asInt());
    assertEquals(1, diagnostic.path("duplicates").asInt());
    assertEquals(1, diagnostic.path("metadata_adjusted").asInt());
  }

  @Test
  void supplemental_omissions_keep_prior_scores_but_valid_new_scores_replace_the_same_pair() {
    var state = state();
    state
        .withArray("ranking")
        .addObject()
        .put("evidence", ScriptResearchTest.BLUE)
        .put("objective", "o1")
        .put("score", 0.1)
        .put("rationale", "Earlier assessment")
        .put("wave", 0);
    state
        .withArray("sources")
        .addObject()
        .put("evidence", ScriptResearchTest.UNUSED)
        .put("wave", 1);
    state
        .withArray("ranking")
        .addObject()
        .put("evidence", ScriptResearchTest.UNUSED)
        .put("objective", "o1")
        .put("score", 0.5)
        .put("rationale", "Counter evidence")
        .put("wave", 1);
    var response =
        ScriptResearchTest.Fixture.parse(
            "{\"ranking\":[{\"evidence\":\""
                + ScriptResearchTest.RED
                + "\",\"objective\":\"o1\",\"score\":0.8}]}");
    var result = rank(state, response);
    assertEquals(3, result.path("state").path("ranking").size());
    assertEquals(0.1, result.path("evidence").get(2).path("relevance").asDouble());
    ((ObjectNode) response.path("ranking").get(0))
        .put("evidence", ScriptResearchTest.BLUE)
        .put("score", 0.9);
    ((ObjectNode) result.path("state")).putObject("pending").put("kind", "ranking").put("wave", 0);
    result = rank((ObjectNode) result.path("state"), response);
    assertEquals(3, result.path("state").path("ranking").size());
    assertEquals(0.9, result.path("evidence").get(0).path("relevance").asDouble());
  }

  @Test
  void missing_ranking_array_and_empty_output_leave_every_passage_explicitly_not_checked() {
    for (String response :
        new String[] {"{}", "null", "{\"ranking\":[]}", "{\"ranking\":\"incomplete\"}"}) {
      var result = rank(state(), ScriptResearchTest.Fixture.parse(response));
      assertEquals(2, result.path("evidence").size());
      assertTrue(result.path("state").path("ranking").isEmpty());
      for (JsonNode e : result.path("evidence")) {
        assertTrue(e.path("relevance").isNull());
        assertEquals("not_checked", e.path("ranking_status").asText());
      }
    }
  }
}
