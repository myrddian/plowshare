package io.aeyer.plowshare.server.orchestrations.scripted;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcScriptStoreTest {
  @Test
  void shipped_research_prepares_and_continues_with_timestamped_stage_todos() throws Exception {
    String source = Files.readString(Path.of("src/main/resources/orchestrations/deep_research.js"));
    String hash = sourceHash(source);
    Instant updatedAt = Instant.parse("2026-10-08T19:40:01.741Z");
    var stages = ScriptProgram.manifest(source).path("stages");
    var todos = new java.util.ArrayList<TodoItem>();
    for (var stage : stages) {
      String id = stage.path("id").asText();
      todos.add(
          new TodoItem(
              "todo_" + id,
              "cnv_fixture",
              null,
              todos.size(),
              id,
              TodoStatus.PENDING,
              null,
              true,
              id,
              updatedAt));
    }
    String message = "{\"request\":\"Compare research findings\",\"context\":\"\"}";
    var jdbc = mock(JdbcTemplate.class);
    var store = new JdbcScriptStore(jdbc);

    var first =
        assertInstanceOf(
            ScriptStore.Prepared.class,
            store.prepareNext(
                source,
                new ScriptStore.Input(
                    "cnv_fixture", message, 0, UUID.randomUUID(), null, null, todos, hash),
                true));
    var transition = assertInstanceOf(ScriptStore.Tool.class, first.step().command());
    assertEquals("todo_write", transition.name());
    var json = new ObjectMapper();
    var operation = json.readTree(transition.arguments()).path("ops").get(0);
    assertEquals("todo_objectives", operation.path("id").asText());
    assertEquals("in_progress", operation.path("status").asText());
    var state = ArgumentCaptor.forClass(String.class);
    verify(jdbc)
        .update(anyString(), eq("cnv_fixture"), eq(0), eq(hash), state.capture(), anyString());
    JsonNode retained = json.readTree(state.getValue());
    when(jdbc.queryForObject(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<JsonNode>>any(),
            eq("cnv_fixture"),
            eq(0),
            eq(hash)))
        .thenReturn(retained);
    todos.set(0, todos.get(0).withStatus(TodoStatus.IN_PROGRESS, updatedAt.plusSeconds(1)));

    var next =
        assertInstanceOf(
            ScriptStore.Prepared.class,
            store.prepareNext(
                source,
                new ScriptStore.Input(
                    "cnv_fixture", message, 1, UUID.randomUUID(), 0, "Updated.", todos, hash),
                true));
    var delegation = assertInstanceOf(ScriptStore.Tool.class, next.step().command());
    assertEquals("agent_run", delegation.name());
    assertEquals("research_analyst", json.readTree(delegation.arguments()).path("agent").asText());
    assertTrue(
        json.readTree(delegation.arguments())
            .path("task")
            .asText()
            .contains("Compare research findings"));
    verify(jdbc).update(anyString(), eq("cnv_fixture"), eq(1), eq(hash), anyString(), anyString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-10-08T19:40:01.741Z",
        "2026-10-09T06:40:01.741+11:00",
        "2026-10-08T15:40:01.741-04:00"
      })
  void timestamped_todos_reach_the_script_and_its_prepared_journal(String timestamp)
      throws Exception {
    String source =
        ScriptProgram.MARKER
            + """
            \nexport const manifest = {};
            export function step(input) {
              return {
                state: {todos: input.todos},
                command: {finish: input.todos.map(todo => todo.stageId + ':' + todo.status).join(',')}
              };
            }
            """;
    String hash = sourceHash(source);
    // Different local dates/offsets describe the same instant; the script receives UTC epoch
    // seconds.
    Instant updatedAt = OffsetDateTime.parse(timestamp).toInstant();
    Instant expected = Instant.parse("2026-10-08T19:40:01.741Z");
    var todos =
        List.of(
            new TodoItem(
                "todo_one",
                "cnv_fixture",
                null,
                0,
                "Research",
                TodoStatus.DONE,
                "Evidence collected",
                true,
                "research",
                updatedAt),
            new TodoItem(
                "todo_two",
                "cnv_fixture",
                "todo_one",
                0,
                "Write report",
                TodoStatus.PENDING,
                null,
                false,
                "report",
                updatedAt));
    var input =
        new ScriptStore.Input("cnv_fixture", "task", 0, UUID.randomUUID(), null, null, todos, hash);
    var jdbc = mock(JdbcTemplate.class);

    var prepared =
        assertInstanceOf(
            ScriptStore.Prepared.class, new JdbcScriptStore(jdbc).prepareNext(source, input, true));

    assertEquals(new ScriptStore.Finish("research:DONE,report:PENDING"), prepared.step().command());
    assertEquals(0, prepared.step().sequence());
    assertEquals(hash, prepared.step().hash());
    var state = ArgumentCaptor.forClass(String.class);
    var command = ArgumentCaptor.forClass(String.class);
    verify(jdbc)
        .update(
            anyString(),
            eq(input.run()),
            eq(input.sequence()),
            eq(hash),
            state.capture(),
            command.capture());
    verifyNoMoreInteractions(jdbc);
    var json = new ObjectMapper();
    var encodedTodos = json.readTree(state.getValue()).path("todos");
    assertEquals(2, encodedTodos.size());
    var first = encodedTodos.get(0);
    assertEquals("todo_one", first.path("id").asText());
    assertEquals("cnv_fixture", first.path("conversation").asText());
    assertTrue(first.path("parent").isNull());
    assertEquals("Evidence collected", first.path("summary").asText());
    assertTrue(first.path("locked").asBoolean());
    // Preserve the numeric epoch-seconds encoding used before serialization moved into the store.
    assertTrue(first.path("updatedAt").isNumber());
    assertEquals(
        expected.getEpochSecond() + expected.getNano() / 1_000_000_000.0,
        first.path("updatedAt").asDouble());
    assertEquals("todo_one", encodedTodos.get(1).path("parent").asText());
    assertTrue(encodedTodos.get(1).path("summary").isNull());
    assertEquals(
        "research:DONE,report:PENDING", json.readTree(command.getValue()).path("finish").asText());
  }

  private static String sourceHash(String source) throws Exception {
    return "sha256:"
        + HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
  }
}
