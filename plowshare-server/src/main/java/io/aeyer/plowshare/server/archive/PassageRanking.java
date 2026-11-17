package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.archive.PassageIndex.Match;
import io.aeyer.plowshare.server.archive.PassageIndex.Occurrence;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure, bounded post-processing of already authorized and full-precision-scored ANN candidates. */
final class PassageRanking {
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Comparator<Candidate> ORDER =
      Comparator.comparingDouble(Candidate::distance)
          .thenComparing(Candidate::id)
          .thenComparingInt(Candidate::position);

  record Candidate(String id, int position, String text, String revision, double distance) {}

  private PassageRanking() {}

  /** Best passage per source first, then one vote per normalized excerpt and twenty references. */
  static List<Match> group(List<Candidate> candidates, int most, boolean group, boolean cosine) {
    Set<String> seen = new HashSet<>();
    var groups = new LinkedHashMap<String, List<Candidate>>();
    for (Candidate candidate : candidates.stream().sorted(ORDER).toList()) {
      if (!Double.isFinite(candidate.distance()) || cosine && candidate.distance() > .65) continue;
      if (!seen.add(candidate.id())) continue;
      String key =
          group ? WHITESPACE.matcher(candidate.text().strip()).replaceAll(" ") : candidate.id();
      groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
    }
    List<Match> matches = new ArrayList<>();
    for (List<Candidate> sources : groups.values()) {
      if (matches.size() == most) break;
      Candidate representative = sources.getFirst();
      matches.add(
          new Match(
              representative.id(),
              representative.position(),
              representative.text(),
              1 - representative.distance(),
              representative.revision(),
              sources.stream().limit(20).map(s -> new Occurrence(s.id(), s.revision())).toList(),
              sources.size()));
    }
    return List.copyOf(matches);
  }
}
