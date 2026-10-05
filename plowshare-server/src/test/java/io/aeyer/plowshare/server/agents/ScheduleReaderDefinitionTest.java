package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The agent {@code events.ScheduleReader} asks to read a sentence into a schedule.
 *
 * <p>Read from {@code src/main/resources} by path, for the reason {@code
 * InterlocutorDefinitionTest} records: on a test classpath {@code /agents} resolves to the test
 * fixtures and would validate the wrong directory in silence.
 */
class ScheduleReaderDefinitionTest {

  private static final Path SHIPPED = Path.of("src/main/resources/agents");

  private static AgentDefinition shipped() {
    return new AgentRegistry(AgentRegistry.load(SHIPPED, BoundTools.boundByThisServer()))
        .get("schedule_reader");
  }

  /**
   * Nothing but {@code ScheduleReader} runs it: not a client, not another agent, and with nothing
   * to call. A reading that could run a tool would be a reading that could act.
   */
  @Test
  void the_schedule_reader_is_not_exported_not_delegable_and_has_no_tools() {
    AgentDefinition reader = shipped();
    assertFalse(reader.exported());
    assertFalse(reader.delegable());
    assertEquals(List.of(), reader.tools());
    assertEquals(List.of(), reader.calls());
    assertEquals(1, reader.maxTurns());
    assertTrue(reader.maxModelCalls() <= 2, String.valueOf(reader.maxModelCalls()));
  }

  /** It answers in a shape, so the reader parses a document rather than prose. */
  @Test
  void the_schedule_reader_answers_in_a_schema() {
    assertTrue(shipped().sampling().responseFormat().isPresent());
  }

  /**
   * Every property the schema declares is required.
   *
   * <p>The production bug this guards against: with no {@code required} list, grammar-constrained
   * decoding (LM Studio, {@code json_schema}) is free to skip any key, and a small model closed the
   * object early on a long, compound sentence and dropped {@code cron} with nothing raised,
   * checked, or logged. Requiring every key forces an answer for each one, even if that answer is
   * the empty string.
   */
  @Test
  void every_property_the_schema_declares_is_required() {
    var schema = shipped().sampling().responseFormat().orElseThrow().schema();
    @SuppressWarnings("unchecked")
    var properties = schema.properties();
    @SuppressWarnings("unchecked")
    var required = schema.required();
    assertEquals(
        Set.copyOf(properties.keySet()),
        Set.copyOf(required),
        "every property schedule_reader declares must be required, or"
            + " grammar-constrained decoding is free to skip it");
  }
}
