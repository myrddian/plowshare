package io.aeyer.plowshare.server.orchestrations.scripted;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Exercises the shared Java decoder through the actual Graal bridge, without provider calls. */
class ResearchJsonRecoveryTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String PROBE="""
            export const manifest = {};
            export function step(input) { return llmJson.parse(input.result); }
            """;

    private JsonNode recover(String raw) {
        return ScriptProgram.step(PROBE,JSON.createObjectNode().put("result",raw));
    }
    private void recovered(String raw,String expected) throws Exception {
        var result=recover(raw);
        assertFalse(result.path("pass").isNull(),result.toString());
        assertEquals(JSON.readTree(expected),result.path("value"));
    }
    @Test void the_retained_production_decomposition_matches_aletheias_recovered_values() throws Exception {
        var result=recover(Files.readString(Path.of("src/test/resources/scripted/decomposition-malformed.json")));
        assertEquals("structural_and_literal_quotes",result.path("pass").asText());
        assertEquals(26,result.path("value").path("queries").size());
        assertEquals(JSON.readTree(Files.readString(Path.of("src/test/resources/scripted/decomposition-recovered.json"))),
                result.path("value"));
        assertTrue(result.path("attempts").get(0).has("error"));
        assertTrue(result.path("attempts").get(0).path("response").asText().contains("\\\"sub_question\\\""));
    }
    @Test void strict_json_preserves_quotes_backslashes_controls_and_punctuation() throws Exception {
        var object=JSON.createObjectNode().put("quote","He said \"hello\", then left.")
                .put("path","C:\\reports\\2026\\file").put("punctuation",",} ,]")
                .put("controls","line\nnext\tcolumn\rend");
        var result=recover(object.toString());
        assertEquals("strict",result.path("pass").asText());
        assertEquals(object,result.path("value"));assertEquals(1,result.path("attempts").size());
    }
    @Test void fences_and_prose_are_removed_without_cutting_braces_inside_strings() throws Exception {
        recovered("```json\n{\"claim\":\"a } bracket\"}\n```","{\"claim\":\"a } bracket\"}");
        recovered("Here is the result:\n{\"claim\":\"a \\\"}\\\" bracket\"}\nEnd of response.",
                "{\"claim\":\"a \\\"}\\\" bracket\"}");
        recovered("Here is an array: [\"a ] bracket\",2] Done.","[\"a ] bracket\",2]");
    }
    @Test void trailing_commas_and_raw_controls_do_not_change_literal_punctuation() throws Exception {
        recovered("{\"claim\":\"comma,} and ,]\",\"rows\":[1,2,],}",
                "{\"claim\":\"comma,} and ,]\",\"rows\":[1,2]}");
        recovered("{\"claim\":\"line\nnext\tcolumn\rend\"}",
                "{\"claim\":\"line\\nnext\\tcolumn\\rend\"}");
    }
    @Test void structural_escapes_invalid_escapes_and_literal_quotes_can_be_recovered() throws Exception {
        recovered("{\\\"claim\\\": \\\"supported\\\"}","{\"claim\":\"supported\"}");
        recovered("{\"claim\":\"bad \\l escape\"}","{\"claim\":\"bad \\\\l escape\"}");
        recovered("{\"claim\":\"He said \"hello world\" then left\"}",
                "{\"claim\":\"He said \\\"hello world\\\" then left\"}");
    }
    @Test void incomplete_or_non_json_output_is_not_invented_into_an_object() {
        for(String raw:new String[]{"{broken JSON","{\"queries\":[{\"query\":\"unfinished", "I cannot complete this task."}) {
            var result=recover(raw);assertTrue(result.path("pass").isNull());
            assertFalse(result.has("value"));assertTrue(result.path("attempts").get(0).has("error"));
        }
    }
    @Test void oversized_malformed_output_does_not_enter_recovery_passes() {
        var result=recover("{broken JSON "+"x".repeat(32768));
        assertTrue(result.path("pass").isNull());assertEquals(1,result.path("attempts").size());
    }
    @Test void enrichment_single_quotes_and_missing_commas_are_shared() throws Exception {
        recovered("{'claim': 'comma,} and ,]', 'rows': [1,2,],}",
                "{\"claim\":\"comma,} and ,]\",\"rows\":[1,2]}");
        recovered("{\n\"claim\":\"supported\"\n\"rows\":[\n{\"id\":1}\n\n{\"id\":2}\n]\n}",
                "{\"claim\":\"supported\",\"rows\":[{\"id\":1},{\"id\":2}]}");
    }
    @Test void ambiguous_roots_and_non_finite_numbers_are_refused() {
        for(String raw:new String[]{"{\"a\":1}{\"b\":2}", "{\"score\":NaN}", "{\"score\":Infinity}"}) {
            var result=recover(raw);
            assertTrue(result.path("pass").isNull(),result.toString());
            assertFalse(result.has("value"));
        }
    }
    @Test void diagnostics_include_original_error_location_and_bounded_excerpts() {
        var result=recover("{\n\\\"claim\\\":\\\"supported\\\"}");
        var original=result.path("attempts").get(0);
        assertEquals(2,original.path("line").asInt());
        assertTrue(original.path("column").asInt()>0);
        assertTrue(original.path("offset").asLong()>0);
        result=recover("{broken JSON "+"x".repeat(524288));
        assertTrue(result.path("pass").isNull());
        assertEquals(1,result.path("attempts").size());
        assertEquals(4096,result.path("attempts").get(0).path("response").asText().length());
        assertTrue(result.path("attempts").get(0).path("cut").asBoolean());
    }
    @Test void utility_returns_guest_objects_without_opening_java_or_host_access() {
        var result=ScriptProgram.step("""
                export const manifest = {};
                export function step(input) {
                  const result=llmJson.parse('{"claim":"supported"}');
                  let immutable=false;
                  try { llmJson.parse=()=>({}); } catch { immutable=true; }
                  let hostDenied=false;
                  try { Java.type('java.lang.System'); } catch { hostDenied=true; }
                  return {immutable,hostDenied,frozen:Object.isFrozen(llmJson),
                    hidden:typeof __llmJsonParser==='undefined',
                    plain:Object.getPrototypeOf(result.value)===Object.prototype,
                    hostMember:typeof result.value.getClass,claim:result.value.claim};
                }
                """,JSON.createObjectNode());
        for(String field:new String[]{"immutable","hostDenied","frozen","hidden","plain"})
            assertTrue(result.path(field).asBoolean(),field);
        assertEquals("undefined",result.path("hostMember").asText());
        assertEquals("supported",result.path("claim").asText());
        assertThrows(IllegalStateException.class,()->ScriptProgram.step(PROBE,
                JSON.createObjectNode().set("result",JSON.createObjectNode())));
    }
}
