package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.aeyer.plowshare.server.archive.PassageRanking.Candidate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PassageRankingTest {
  @Test
  void fullPrecisionRankingSelectsOnePassagePerSourceThenGroupsBeforeTheResultLimit() {
    var candidates =
        List.of(
            new Candidate("b", 0, "shared passage", "b1", .2),
            new Candidate("a", 1, "another passage", "a1", .3),
            new Candidate("c", 0, "distinct evidence", "c1", .4),
            new Candidate("a", 0, " shared\npassage ", "a1", .1),
            new Candidate("weak", 0, "weak evidence", "w1", .9));
    var matches = PassageRanking.group(candidates, 2, true, true);
    assertEquals(List.of("a", "c"), matches.stream().map(PassageIndex.Match::id).toList());
    assertEquals(.9, matches.getFirst().similarity());
    assertEquals(
        List.of("a", "b"),
        matches.getFirst().sources().stream().map(PassageIndex.Occurrence::id).toList());
    assertEquals(2, matches.getFirst().sourceCount());
  }

  @Test
  void tiesAreStableAndReferencesStayBoundedWithoutDroppingAnotherGroup() {
    var candidates = new ArrayList<Candidate>();
    for (int i = 39; i >= 0; i--)
      candidates.add(new Candidate("source-%02d".formatted(i), 0, "repeated", "r", .1));
    candidates.add(new Candidate("other", 0, "distinct", "r", .2));
    var matches = PassageRanking.group(candidates, 2, true, true);
    assertEquals(2, matches.size());
    assertEquals("source-00", matches.getFirst().id());
    assertEquals(40, matches.getFirst().sourceCount());
    assertEquals(20, matches.getFirst().sources().size());
    assertEquals("other", matches.getLast().id());
  }

  @Test
  void digestSourcesRemainSeparateAndNonCosineMetricsHaveNoCosineFloor() {
    var candidates =
        List.of(new Candidate("a", 0, "same", "r", 3), new Candidate("b", 0, "same", "r", 2));
    assertEquals(
        List.of("b", "a"),
        PassageRanking.group(candidates, 2, false, false).stream()
            .map(PassageIndex.Match::id)
            .toList());
  }
}
