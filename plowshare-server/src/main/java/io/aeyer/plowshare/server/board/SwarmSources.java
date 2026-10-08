package io.aeyer.plowshare.server.board;

import java.util.List;

/** Bounded definition source reads, protected by the owning workspace fence. */
public interface SwarmSources {
  record Source(String name, String format, String text, String origin) {}

  /** An existing empty directory is an empty catalog, never a fallback to another tier. */
  List<Source> read(String project);
}
