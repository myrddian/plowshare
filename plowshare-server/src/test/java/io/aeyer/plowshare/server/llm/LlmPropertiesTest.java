package io.aeyer.plowshare.server.llm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** {@link LlmProperties#systemSpecifier(String)}: what a type of harness work runs on. */
class LlmPropertiesTest {

    @Test void a_type_with_no_override_inherits_the_system_binding() {
        var props = new LlmProperties();
        props.setSystem("openai/gpt-oss-120b");
        assertEquals("openai/gpt-oss-120b", props.systemSpecifier("compaction"));
        assertEquals("openai/gpt-oss-120b", props.systemSpecifier("memory"));
    }

    @Test void an_override_wins_over_the_system_binding_for_that_type_only() {
        var props = new LlmProperties();
        props.setSystem("openai/gpt-oss-120b");
        props.setSystemOverrides(java.util.Map.of("memory", "fast"));
        assertEquals("fast", props.systemSpecifier("memory"));
        assertEquals("openai/gpt-oss-120b", props.systemSpecifier("compaction"));
    }

    @Test void a_blank_override_is_not_an_override_and_does_not_blank_the_binding() {
        var props = new LlmProperties();
        props.setSystem("openai/gpt-oss-120b");
        props.setSystemOverrides(java.util.Map.of("memory", "   "));
        assertEquals("openai/gpt-oss-120b", props.systemSpecifier("memory"));
    }

    @Test void an_unset_binding_resolves_to_the_empty_string_rather_than_null() {
        assertEquals("", new LlmProperties().systemSpecifier("memory"));
    }
}
