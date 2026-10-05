package io.aeyer.plowshare.server.llm.openai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransportException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LoadedModelsCodecTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void only_live_positive_windows_are_retained_and_the_result_is_immutable() throws Exception {
    var result =
        LoadedModelsCodec.read(
            JSON.readTree(
                """
      {"data":[{"id":"loaded","loaded_context_length":8192,"max_context_length":32768},
      {"id":"zero","loaded_context_length":0},
      {"id":"unloaded","max_context_length":100000}]}
      """));
    assertEquals(java.util.Map.of("loaded", 8192), result.contextLengths());
    assertThrows(UnsupportedOperationException.class, result.contextLengths()::clear);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"data\":{}}",
        "{\"data\":[null]}",
        "{\"data\":[{\"id\":4}]}",
        "{\"data\":[{\"id\":\" padded \"}]}",
        "{\"data\":[{\"id\":\"a\",\"loaded_context_length\":\"8192\"}]}",
        "{\"data\":[{\"id\":\"a\",\"loaded_context_length\":1.5}]}",
        "{\"data\":[{\"id\":\"a\",\"loaded_context_length\":2147483648}]}",
        "{\"data\":[{\"id\":\"a\"},{\"id\":\"a\"}]}"
      })
  void invalid_members_are_refused_before_discovery(String wire) throws Exception {
    assertThrows(LlmTransportException.class, () -> LoadedModelsCodec.read(JSON.readTree(wire)));
  }

  @Test
  void wire_duplicates_and_trailing_documents_are_refused_before_discovery() {
    assertThrows(
        LlmTransportException.class, () -> LoadedModelsCodec.read("{\"data\":[],\"data\":[]}"));
    assertThrows(LlmTransportException.class, () -> LoadedModelsCodec.read("{\"data\":[]} {}"));
  }
}
