package io.aeyer.plowshare.server.api;

/**
 * The body of {@code POST /v1/memories/recall}.
 *
 * @param project the tier to recall from; {@code null} means global, matching {@link
 *     io.aeyer.plowshare.protocol.Home}. A project also draws on global, per {@code
 *     Archive.recall}; a global request searches global alone.
 * @param question what is being asked, in prose
 * @param limit how many memories to return at most; {@code null} takes {@link
 *     MemoryController#DEFAULT_RECALL_LIMIT}. There is no Excalibur default to port here — {@code
 *     recall} as a vector query has no Python equivalent, per {@code Archive}'s own javadoc, so
 *     this number is a judgement call rather than a ported constant.
 */
public record RecallRequest(String project, String question, Integer limit) {

  /**
   * How many memories to return: {@link #limit}, or {@link MemoryController#DEFAULT_RECALL_LIMIT}
   * when the field named none.
   *
   * <p><b>On the record rather than at either call site</b>, because both surfaces bind this record
   * — {@code POST /v1/memories/recall} through Jackson and {@code memory.recall} through {@code
   * ws.Payloads.as} — and a default written out twice is two defaults the day one of them is tuned.
   * What an absent field means is a fact about the request, which is where this record's own
   * javadoc already documents it.
   */
  public int limitOrDefault() {
    return limit == null ? MemoryController.DEFAULT_RECALL_LIMIT : limit;
  }
}
