package io.aeyer.plowshare.server.orchestrations.scripted;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** Checks acceptance boundaries without turning missing judgments into approval. */
class ResearchConstraintTest {
    private static final String PROBE=ScriptResearchTest.SOURCE
            .replace("export function step(input) {", "function researchStep(input) {")
            + "\nexport function step(input) { accept(input.state,input); return {state:input.state}; }";
    private ObjectNode state(String kind) {
        var s=ScriptResearchTest.JSON.createObjectNode().put("cursor",0);
        s.putObject("pending").put("kind",kind).put("wave",0);
        var o=s.putArray("objectives").addObject().put("id","o1").put("objective","Example efficacy")
                .put("intent","Measured efficacy").put("expected_evidence","Controlled observations");
        o.putArray("anchors").add("Example");
        s.putArray("sources").addObject().put("evidence",ScriptResearchTest.BLUE).put("wave",0);
        s.putArray("ranking");s.putArray("reviews");s.putArray("failures");s.putArray("scopeChanges");
        s.putArray("queries").addObject().put("id","q1").put("query","Example efficacy").put("objective","o1").put("type","TOPIC_CENTRIC");
        s.putArray("coverage");
        var f=s.putArray("findings").addObject().put("id","f1").put("objective","o1").put("claim","Original claim")
                .put("rationale","Original assessment").put("verdict","not_checked");
        f.putArray("support").add(ScriptResearchTest.BLUE);f.putArray("counterEvidence");
        s.putArray("red").addObject().put("finding_id","f1").put("review_status","reviewed");
        s.putArray("rebuttal").addObject().put("finding_id","f1").put("review_status","reviewed");
        return s;
    }
    private JsonNode accept(ObjectNode s,JsonNode out) {
        var input=ScriptResearchTest.JSON.createObjectNode().put("result",out.toString()).set("state",s);
        return ScriptProgram.step(PROBE,input).path("state");
    }

    @Test void objective_counts_and_anchor_counts_are_guidance_and_optional_metadata_does_not_abort() {
        var out=ScriptResearchTest.JSON.createObjectNode();var objectives=out.putArray("objectives");
        for(int i=0;i<7;i++) {
            var o=objectives.addObject().put("objective","Research topic "+i);var anchors=o.putArray("anchors");
            for(int a=0;a<20;a++) anchors.add("Entity "+a);
        }
        var result=accept(state("objectives"),out);
        assertEquals(7,result.path("objectives").size());assertEquals(20,result.path("objectives").get(0).path("anchors").size());
        assertFalse(result.path("objectiveApproved").asBoolean());
        assertTrue(result.path("scope").asText().contains("confirm"));
    }
    @Test void incomplete_or_unknown_plan_revisions_do_not_replace_accepted_objectives() {
        var s=state("plan");var out=ScriptResearchTest.Fixture.parse("""
                {"revised_objectives":[{"id":"o1","objective":"Revised Example efficacy"},
                 {"id":"o1","objective":"Duplicate"},{"id":"invented","objective":"Unrelated"}],
                 "supplemental_queries":[{"query":"Example comparison","objective":"missing"}],
                 "needs_supplemental_retrieval":"false"}
                """);
        var result=accept(s,out);assertEquals(1,result.path("objectives").size());
        assertEquals("Revised Example efficacy",result.path("objectives").get(0).path("objective").asText());
        assertEquals("Controlled observations",result.path("objectives").get(0).path("expected_evidence").asText());
        assertEquals("plan_done",result.path("subphase").asText());
    }
    @Test void a_single_revised_object_is_accepted_without_array_massaging() {
        var result=accept(state("plan"),ScriptResearchTest.Fixture.parse("""
                {"assessment":"A focused refinement","revised_objectives":{"id":"o1","objective":"Refined Example topic"}}
                """));
        assertEquals("Refined Example topic",result.path("objectives").get(0).path("objective").asText());
    }
    @Test void unsupported_holds_and_missing_required_reviews_are_recorded_as_not_checked() {
        for(boolean missing: new boolean[]{false,true}) {
            var s=state("yellow");
            if(missing) ((ObjectNode)s.path("red").get(0)).put("review_status","not_checked");
            var row=ScriptResearchTest.JSON.createObjectNode().put("finding_id","f1").put("verdict"," HOLDS ")
                    .put("claim","A proposed claim").put("rationale","A proposed rationale");
            row.putArray("support");if(missing) row.withArray("support").add(ScriptResearchTest.BLUE);
            var out=ScriptResearchTest.JSON.createObjectNode();out.putArray("findings").add(row);
            var result=accept(s,out);
            assertEquals("not_checked",result.path("findings").get(0).path("verdict").asText());
            assertFalse(result.path("failures").isEmpty());
        }
    }
    @Test void invalid_citations_reject_only_the_affected_review_and_keep_the_original_claim() {
        var out=ScriptResearchTest.Fixture.parse("""
                {"findings":[{"finding_id":"f1","verdict":"holds","claim":"Invalid revised claim",
                 "rationale":"Unsupported","support":["invented"],"counterEvidence":[]}]}
                """);
        var result=accept(state("yellow"),out);
        assertEquals("Original claim",result.path("findings").get(0).path("claim").asText());
        assertEquals("not_checked",result.path("findings").get(0).path("verdict").asText());
        assertEquals(ScriptResearchTest.BLUE,result.path("findings").get(0).path("support").get(0).asText());
    }
    @Test void unknown_synthesis_citations_and_missing_sections_are_local_gaps() {
        var out=ScriptResearchTest.JSON.createObjectNode().put("executive_summary","A claim [evidence:invented]")
                .put("limitations","Actual limitations");
        var result=accept(state("synthesis"),out);
        assertFalse(result.path("synthesis").toString().contains("invented"));
        assertEquals("Actual limitations",result.path("synthesis").path("limitations").asText());
        assertTrue(result.path("synthesis").path("conclusion").asText().contains("No synthesized conclusion"));
    }
    @Test void large_review_metadata_has_a_bounded_preview_and_does_not_abort_the_assessment() throws Exception {
        var out=ScriptResearchTest.JSON.createObjectNode().put("assessment","Valid plan assessment").put("diagnostic","x".repeat(40000));
        var result=accept(state("plan"),out);
        var review=result.path("reviews").get(0).path("text").asText();
        assertTrue(review.length()<=32768);assertTrue(ScriptResearchTest.JSON.readTree(review).path("cut").asBoolean());
        assertEquals("plan_done",result.path("subphase").asText());
    }
    @Test void report_review_previews_are_bounded_without_deleting_the_full_journal_reviews() {
        var s=state("plan");
        for(int i=0;i<150;i++) s.withArray("reviews").addObject().put("stage","stage"+i).put("outcome","recorded").put("text","λ".repeat(10000));
        var probe=PROBE.replace("return {state:input.state};","return {state:input.state,reviews:reportReviews(input.state)};");
        var input=ScriptResearchTest.JSON.createObjectNode().put("result","{\"assessment\":\"Completed critique\"}").set("state",s);
        var result=ScriptProgram.step(probe,input);
        assertEquals(151,result.path("state").path("reviews").size());
        assertTrue(result.path("reviews").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<200000);
        assertEquals("report.metadata",result.path("reviews").get(result.path("reviews").size()-1).path("stage").asText());
    }
}
