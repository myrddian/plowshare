package io.aeyer.plowshare.server.orchestrations.scripted;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import org.junit.jupiter.api.Test;

/** Exercises the shipped selection handler and its synthetic regression response in Graal. */
class SourceSelectionTest {
    private static final String PROBE=ScriptResearchTest.SOURCE
            .replace("export function step(input) {", "function researchStep(input) {")
            + "\nexport function step(input) { accept(input.state,input); return {state:input.state}; }";

    private ObjectNode state(JsonNode response) {
        var state=ScriptResearchTest.JSON.createObjectNode();
        state.set("pending",ScriptResearchTest.JSON.createObjectNode().put("kind","selection").put("wave",0));
        var objectives=state.putArray("objectives");
        for(int i=1;i<=4;i++) objectives.addObject().put("id","o"+i);
        var candidates=state.putArray("candidates");
        var seen=new LinkedHashSet<String>();
        for(JsonNode row:response.path("sources")) if(seen.add(row.path("key").asText())) {
            var candidate=candidates.addObject().put("url",row.path("key").asText()).put("wave",0);
            candidate.putArray("objectives");
        }
        state.putArray("sources");state.putArray("fetchAudit");state.putArray("reviews");state.putArray("failures");
        return state;
    }

    private JsonNode select(ObjectNode state,JsonNode response) {
        var input=ScriptResearchTest.JSON.createObjectNode().put("result",response.toString()).set("state",state);
        return ScriptProgram.step(PROBE,input).path("state");
    }

    @Test void the_production_selection_keeps_all_nineteen_unique_sources_and_both_rationales() throws Exception {
        var response=ScriptResearchTest.JSON.readTree(Files.readString(Path.of("src/test/resources/scripted/selection-duplicate.json")));
        var selected=select(state(response),response);
        assertEquals(20,response.path("sources").size());
        assertEquals(19,selected.path("selected").size());
        assertEquals(19,selected.path("fetchAudit").size());
        var repeated=java.util.stream.StreamSupport.stream(selected.path("selected").spliterator(),false)
                .filter(v->v.path("url").asText().equals("https://fixture.example.invalid/source/e94432e42d3dbdb8")).findFirst().orElseThrow();
        assertEquals(2,repeated.path("selection_rationales").size());
        var originalReview=ScriptResearchTest.JSON.readTree(selected.path("reviews").get(1).path("text").asText());
        assertEquals(response,originalReview,"the original model selection remains inspectable");
    }

    @Test void the_changed_production_url_is_omitted_while_all_twenty_three_matches_continue() throws Exception {
        var fixture=ScriptResearchTest.JSON.readTree(Files.readString(Path.of("src/test/resources/scripted/selection-unmatched.json")));
        var response=fixture.path("selection");
        var state=state(response);state.set("candidates",fixture.path("candidates"));
        var selected=select(state,response);
        assertEquals(24,response.path("sources").size());
        assertEquals(23,selected.path("selected").size());
        assertEquals(23,selected.path("fetchAudit").size());
        String changed="https://fixture.example.invalid/source/8f9dd577f55dec6b";
        assertTrue(java.util.stream.StreamSupport.stream(selected.path("fetchAudit").spliterator(),false)
                .noneMatch(row->changed.equals(row.path("requested_url").asText())));
        var normalization=ScriptResearchTest.Fixture.parse(selected.path("reviews").get(0).path("text").asText());
        assertEquals(changed,normalization.path("omitted").get(0).path("key").asText());
        assertFalse(normalization.path("fallback").asBoolean());
    }

    @Test void a_reselected_retained_document_merges_coverage_without_new_audit_or_evidence() {
        var response=ScriptResearchTest.JSON.createObjectNode();
        response.putArray("sources").addObject().put("key","https://example.test/primary")
                .put("rationale","Also supports efficacy").putArray("objectives").add("o2");
        var state=state(response);
        state.withArray("fetchAudit").addObject().put("wave",0).put("key","https://example.test/primary")
                .put("selection_rationale","Supports adoption").putArray("objectives").add("o1");
        state.withArray("sources").addObject().put("wave",0).put("auditIndex",0).put("evidence",ScriptResearchTest.BLUE)
                .putArray("objectives").add("o1");
        var selected=select(state,response);
        assertTrue(selected.path("selected").isEmpty());
        assertEquals(1,selected.path("fetchAudit").size());
        assertEquals(1,selected.path("sources").size());
        assertEquals(ScriptResearchTest.BLUE,selected.path("sources").get(0).path("evidence").asText());
        assertEquals("[\"o1\",\"o2\"]",selected.path("sources").get(0).path("objectives").toString());
        assertEquals(2,selected.path("fetchAudit").get(0).path("selection_rationales").size());
    }

    @Test void acquisition_budget_is_applied_after_normalizing_model_rows() throws Exception {
        var response=ScriptResearchTest.JSON.createObjectNode();
        var rows=response.putArray("sources");
        for(int i=0;i<30;i++) for(int copy=0;copy<2;copy++) rows.addObject()
                .put("key","https://example.test/"+i).put("rationale","Relevant")
                .putArray("objectives").add("o1");
        var selected=select(state(response),response);
        assertEquals(24,selected.path("selected").size());
        assertEquals(24,selected.path("fetchAudit").size());
        var normalization=ScriptResearchTest.JSON.readTree(selected.path("reviews").get(0).path("text").asText());
        assertEquals(30,normalization.path("duplicates").asInt());
        assertEquals(6,normalization.path("deferred").asInt());
    }

    @Test void unmatched_sources_and_unknown_selection_labels_are_recorded_without_aborting() {
        var response=ScriptResearchTest.JSON.createObjectNode();
        var rows=response.putArray("sources");
        rows.addObject().put("key","https://example.test/primary").put("rationale","Relevant")
                .putArray("objectives").add("o1");
        rows.addObject().put("key","https://example.test/primary").put("rationale","Repeated")
                .putArray("objectives").add("invented");
        var state=state(response);
        rows.addObject().put("key","https://invented.test/source").put("rationale","Invented")
                .putArray("objectives").add("o1");
        var selected=select(state,response);
        assertEquals(1,selected.path("selected").size());
        assertEquals("https://example.test/primary",selected.path("selected").get(0).path("url").asText());
        assertEquals("[\"o1\"]",selected.path("selected").get(0).path("objectives").toString());
        var normalization=ScriptResearchTest.Fixture.parse(selected.path("reviews").get(0).path("text").asText());
        assertEquals(1,normalization.path("metadata_adjusted").asInt());
        assertEquals("https://invented.test/source",normalization.path("omitted").get(0).path("key").asText());
        assertEquals(1,selected.path("failures").size());
    }

    @Test void entirely_unmatched_selection_falls_back_to_balanced_discovered_candidates() {
        var response=ScriptResearchTest.JSON.createObjectNode();
        var rows=response.putArray("sources");
        rows.addObject().put("key","https://example.test/primary").put("rationale","Relevant")
                .putArray("objectives").add("o1");
        var state=state(response);
        rows.removeAll();rows.addNull();rows.addObject().put("key","https://unmatched.test/source");
        var selected=select(state,response);
        assertEquals(1,selected.path("selected").size());
        assertEquals("https://example.test/primary",selected.path("selected").get(0).path("url").asText());
        var normalization=ScriptResearchTest.Fixture.parse(selected.path("reviews").get(0).path("text").asText());
        assertTrue(normalization.path("fallback").asBoolean());
        assertEquals(2,normalization.path("omitted").size());
        assertTrue(selected.path("failures").toString().contains("bounded fallback"));
    }

    @Test void whitespace_and_missing_selection_metadata_recover_without_new_source_identity() {
        var response=ScriptResearchTest.JSON.createObjectNode();
        var row=response.putArray("sources").addObject().put("key","https://example.test/primary");
        var state=state(response);
        ((ObjectNode)state.path("candidates").get(0)).withArray("objectives").add("o2");
        row.put("key","  https://example.test/primary\n");
        var selected=select(state,response);
        assertEquals("https://example.test/primary",selected.path("selected").get(0).path("url").asText());
        assertEquals("[\"o2\"]",selected.path("selected").get(0).path("objectives").toString());
        assertTrue(selected.path("selected").get(0).path("selection_rationale").asText().contains("no selection rationale"));
    }
}
