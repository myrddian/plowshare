package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.embedding.QuerySources;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ArchivePublicationQueryReuseTest {
  private MemoryWrites.Learning learning() {
    return new MemoryWrites.Learning(
        "log",
        List.of(
            new EntryRecord(
                "log",
                1,
                EntryKind.UTTERANCE,
                "first",
                null,
                List.of(),
                null,
                null,
                1,
                null,
                null,
                null,
                null),
            new EntryRecord(
                "log",
                2,
                EntryKind.ANSWER,
                "second",
                null,
                List.of(),
                null,
                null,
                1,
                null,
                null,
                null,
                null),
            new EntryRecord(
                "log",
                3,
                EntryKind.UTTERANCE,
                "third",
                null,
                List.of(),
                null,
                null,
                2,
                null,
                null,
                null,
                null)),
        0);
  }

  @Test
  void exactLearningCohortIsOneBoundTupleCheckButFreshScopeAndConversationChecksRemain() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(3L)
        .thenReturn(2L);
    var learning = learning();
    var request =
        new MemoryWrites.Request(
            null,
            Home.of("home"),
            new MemoryProposal("claim", "scope", "body", "writer", "test"),
            new MemoryWrites.Origin("learning-invocation", () -> {}, () -> {}, learning));
    var pin = new QuerySources.Pin(UUID.randomUUID(), UUID.randomUUID());
    var store = new MemoryStore(jdbc);
    store.require(request, pin, false);
    var arguments = org.mockito.ArgumentCaptor.forClass(Object[].class);
    verify(jdbc)
        .queryForObject(
            contains(") expected(ordinal,turn_ordinal,content,kind)"),
            eq(Long.class),
            arguments.capture());
    assertEquals(
        List.of(
            1,
            1,
            "first",
            "utterance",
            2,
            1,
            "second",
            "answer",
            3,
            2,
            "third",
            "utterance",
            "log"),
        Arrays.asList(arguments.getValue()));
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () -> store.require(request, pin, false));
    verify(jdbc, times(4)).queryForObject(anyString(), eq(Boolean.class), any(Object[].class));
  }

  @Test
  void provenanceUsesOneInsertForDistinctTurns() {
    var jdbc = mock(JdbcTemplate.class);
    new MemoryStore(jdbc).provenance("memory", learning());
    verify(jdbc)
        .update(
            "INSERT INTO memory_provenance VALUES (?,?,?),(?,?,?) ON CONFLICT DO NOTHING",
            "memory",
            "log",
            1,
            "memory",
            "log",
            2);
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void passagePublicationKeepsOwnerQueueFencesAndPublishesOneBoundSixteenRowInsert() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(startsWith("SELECT e.ordinal"), any(Object[].class)))
        .thenReturn(List.of(Map.of("ordinal", 1)));
    when(jdbc.queryForList(startsWith("SELECT source_hash"), any(Object[].class)))
        .thenReturn(
            List.of(
                Map.of(
                    "source_hash",
                    "hash",
                    "generation",
                    "old",
                    "next_position",
                    0,
                    "status",
                    "pending")));
    UnitOfWork work =
        new UnitOfWork() {
          public <T> T inTransaction(Supplier<T> action) {
            return action.get();
          }
        };
    var repository = new JdbcPassageRepository(jdbc, work);
    var texts = java.util.stream.IntStream.range(0, 16).mapToObj(i -> "text-" + i).toList();
    assertTrue(
        repository.publishText(
            new PassageIndex.Source("entry", "log:1", "hash", "original", 0),
            texts,
            true,
            "current"));
    var sql = org.mockito.ArgumentCaptor.forClass(String.class);
    var parameters = org.mockito.ArgumentCaptor.forClass(Object[].class);
    verify(jdbc, times(3)).update(sql.capture(), parameters.capture());
    assertTrue(sql.getAllValues().get(0).startsWith("DELETE FROM retrieval_passages"));
    assertEquals(16, sql.getAllValues().get(1).split("CAST\\(\\? AS vector\\)", -1).length - 1);
    var inserted = parameters.getAllValues().get(1);
    assertEquals(80, inserted.length);
    assertEquals(List.of("entry", "log:1", 0, "text-0"), Arrays.asList(inserted).subList(0, 4));
    assertEquals(15, inserted[77]);
    assertEquals("text-15", inserted[78]);
    assertNull(inserted[79]);
    assertTrue(sql.getAllValues().get(2).startsWith("UPDATE retrieval_sources"));
    when(jdbc.queryForList(startsWith("SELECT source_hash"), any(Object[].class)))
        .thenReturn(
            List.of(
                Map.of(
                    "source_hash",
                    "changed",
                    "generation",
                    "old",
                    "next_position",
                    0,
                    "status",
                    "pending")));
    assertFalse(
        repository.publishText(
            new PassageIndex.Source("entry", "log:1", "hash", "original", 0),
            texts,
            true,
            "current"));
    verify(jdbc, times(3)).update(anyString(), any(Object[].class));
  }
}
