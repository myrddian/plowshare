package io.aeyer.plowshare.server.archive;

import java.util.Objects;

/**
 * A lexical search page whose total may describe a bounded candidate window.
 *
 * <p>{@link LogSearch#total()} is exact when {@code truncated} is false. When it is true, more
 * entries matched than the search ranks: the page and the total cover only the ranked candidates,
 * and a caller must say so instead of presenting the total as the number of matches in the archive.
 *
 * @param page the hits, the number of groups among ranked candidates, and what was out of reach
 * @param truncated whether matching entries were left unranked
 */
public record BoundedLogSearch(LogSearch page, boolean truncated) {
  /** An empty, complete answer. */
  public static final BoundedLogSearch NONE = new BoundedLogSearch(LogSearch.NONE, false);

  public BoundedLogSearch {
    Objects.requireNonNull(page, "page");
  }
}
