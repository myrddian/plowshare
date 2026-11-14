package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BoardRelayRoutesTest {
  @Test
  void board_destinations_are_typed_and_pinned_by_the_route() {
    var selections =
        RelayRouteCodec.selections(
            """
        [{"name":"post","receiver":"board.post","work":{"topic":"bdt_Topic"}},
         {"name":"open","receiver":"board.open","work":{"swarm":"research"}},
         {"name":"close","receiver":"board.close","work":{"topic":"bdt_Topic"}}]
        """);
    assertEquals("bdt_Topic", selections.getFirst().work().topic());
    assertEquals("research", selections.get(1).work().swarm());
    assertEquals("board.close", selections.get(2).receiver());
  }

  @Test
  void mixed_destinations_project_overrides_handlers_and_unknown_fields_are_refused() {
    for (String branch :
        new String[] {
          "{\"receiver\":\"board.post\"}",
          "{\"receiver\":\"board.post\",\"work\":{\"agent\":\"researcher\"}}",
          "{\"receiver\":\"board.post\",\"work\":{\"topic\":\"bdt_Topic\",\"swarm\":\"research\"}}",
          "{\"receiver\":\"board.close\",\"work\":{\"topic\":\"bdt_Topic\",\"project\":\"foreign\"}}",
          "{\"receiver\":\"board.open\",\"work\":{\"swarm\":\"research\",\"project\":\"foreign\"}}",
          "{\"receiver\":\"board.open\",\"work\":{\"swarm\":\"../bad\"}}",
          "{\"receiver\":\"board.post\",\"script\":\"handler.js\",\"work\":{\"topic\":\"bdt_Topic\"}}",
          "{\"receiver\":\"board.post\",\"work\":{\"topic\":\"bdt_Topic\",\"author\":\"worker\"}}",
          "{\"receiver\":\"agent.run\",\"work\":{\"topic\":\"bdt_Topic\"}}"
        }) {
      String value = "[{\"name\":\"board\"," + branch.substring(1) + "]";
      assertThrows(RuntimeException.class, () -> RelayRouteCodec.selections(value));
    }
  }
}
