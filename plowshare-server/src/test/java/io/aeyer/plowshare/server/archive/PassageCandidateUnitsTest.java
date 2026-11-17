package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.embedding.EmbeddingProfile;
import io.aeyer.plowshare.server.embedding.EmbeddingQuery;
import io.aeyer.plowshare.server.embedding.EmbeddingSearchPolicy;
import io.aeyer.plowshare.server.embedding.EmbeddingSlot;
import io.aeyer.plowshare.server.embedding.EmbeddingSpace;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

class PassageCandidateUnitsTest {
  private record Key(String id, int position, String hash, long revision) {}

  @Test
  void repeatedKeysCountOnceWhileDifferentPositionsKeepTheirOwnRevision() throws Exception {
    var first =
        IntStream.range(0, 500).mapToObj(i -> new Key("source" + i, 0, "hash", i + 1)).toList();
    var fixture =
        new Fixture(
            List.of(
                first,
                List.of(
                    first.getFirst(),
                    new Key("source0", 1, "hash", 9000),
                    new Key("tail", 0, "hash", 9001))));
    var result = fixture.rank();
    assertEquals(1, result.matches().size());
    assertEquals(501, result.matches().getFirst().sourceCount());
    assertEquals(2, fixture.selections);
    assertEquals(502, fixture.validated);
  }

  @Test
  void repeatedKeyWithAnotherPassageRevisionInvalidatesTheWholeWindow() throws Exception {
    var first =
        IntStream.range(0, 500).mapToObj(i -> new Key("source" + i, 0, "hash", i + 1)).toList();
    var fixture = new Fixture(List.of(first, List.of(new Key("source0", 0, "hash", 9000))));
    var failure = assertThrows(ReadFailedException.class, fixture::rank);
    assertEquals(ReadFailedException.Reason.INVALIDATED, failure.reason());
    assertEquals(2, fixture.selections);
    assertEquals(0, fixture.validated);
  }

  @Test
  void anotherSourceHashAtADifferentPositionInvalidatesTheWholeWindow() throws Exception {
    var first =
        IntStream.range(0, 500).mapToObj(i -> new Key("source" + i, 0, "hash", i + 1)).toList();
    var fixture = new Fixture(List.of(first, List.of(new Key("source0", 1, "changed", 9000))));
    var failure = assertThrows(ReadFailedException.class, fixture::rank);
    assertEquals(ReadFailedException.Reason.INVALIDATED, failure.reason());
    assertEquals(2, fixture.selections);
    assertEquals(0, fixture.validated);
  }

  @ParameterizedTest
  @EnumSource(
      value = EmbeddingSearchPolicy.Mode.class,
      names = {"HNSW_HALF", "HNSW_BINARY", "IVFFLAT_VECTOR", "IVFFLAT_HALF", "IVFFLAT_BINARY"})
  void otherAnnPoliciesKeepOneUnpagedSqlUnit(EmbeddingSearchPolicy.Mode mode) throws Exception {
    var fixture = new Fixture(List.of(List.of(new Key("source", 0, "hash", 1))));
    fixture.rank(mode);
    assertEquals(1, fixture.selections);
    assertEquals(1, fixture.unitCount);
    assertEquals(0, fixture.validated);
    assertFalse(fixture.selectionSql.contains("OFFSET"));
  }

  private static final class Fixture {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final List<List<Key>> pages;
    final LinkedHashMap<String, Key> retained = new LinkedHashMap<>();
    List<Key> selected = List.of();
    int selections;
    int validated;
    int unitCount;
    String selectionSql = "";

    Fixture(List<List<Key>> pages) throws Exception {
      this.pages = pages;
      when(jdbc.query(
              anyString(),
              ArgumentMatchers.any(PreparedStatementSetter.class),
              ArgumentMatchers.<RowMapper<Object>>any()))
          .thenAnswer(
              call -> {
                String sql = call.getArgument(0);
                RowMapper<Object> mapper = call.getArgument(2);
                List<Key> rows;
                if (sql.startsWith("WITH authorized_sources")) {
                  selectionSql = sql;
                  selected = this.pages.get(selections++);
                  selected.forEach(
                      key -> retained.putIfAbsent(key.id() + ":" + key.position(), key));
                  rows = selected;
                } else if (sql.startsWith("SELECT selected.source_id")) rows = selected;
                else if (sql.startsWith("SELECT p.source_id,p.position FROM unnest")) {
                  rows = List.copyOf(retained.values());
                  validated = rows.size();
                } else rows = List.of(new Key("setting", 0, "hash", 0));
                var result = new ArrayList<Object>();
                for (var key : rows) result.add(mapper.mapRow(row(key), result.size()));
                return result;
              });
    }

    PassageIndex.Ranking rank() {
      return rank(EmbeddingSearchPolicy.Mode.HNSW_VECTOR);
    }

    PassageIndex.Ranking rank(EmbeddingSearchPolicy.Mode mode) {
      var space =
          new EmbeddingSpace(
              "0".repeat(64),
              new EmbeddingSpace.Definition(
                  "fixture", "v1", 1, "", "", "mean", EmbeddingSpace.Normalization.NONE, "none"));
      var profile =
          new EmbeddingProfile(
              EmbeddingSlot.PROSE,
              space,
              new EmbeddingSearchPolicy(mode, EmbeddingSearchPolicy.Distance.COSINE),
              1);
      var units =
          new PassageRepository.RankingReadUnit() {
            public <T> T independent(Supplier<T> read) {
              unitCount++;
              return read.get();
            }

            public <T> T joined(Supplier<T> read) {
              throw new AssertionError("no caller transaction");
            }
          };
      return new JdbcPassageRepository(jdbc, UnitOfWork.NONE)
          .rankWindow(
              Home.global(),
              "entry",
              new EmbeddingQuery(profile, new float[] {1}),
              200,
              "fixture",
              PassageRepository.ReadScope.UNRESTRICTED,
              units);
    }
  }

  private static ResultSet row(Key key) throws SQLException {
    var row = mock(ResultSet.class);
    when(row.getString(anyString()))
        .thenAnswer(
            call ->
                switch ((String) call.getArgument(0)) {
                  case "source_id" -> key.id();
                  case "source_hash" -> key.hash();
                  case "passage" -> "same passage";
                  default -> "off";
                });
    when(row.getInt("position")).thenReturn(key.position());
    when(row.getLong("embedding_source_revision")).thenReturn(key.revision());
    when(row.getDouble("distance")).thenReturn(.1);
    return row;
  }
}
