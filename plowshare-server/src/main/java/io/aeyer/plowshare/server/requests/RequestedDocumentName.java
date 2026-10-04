package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * What the corpus should file an upload under — the caller's chosen name, or the filename the bytes
 * arrived as.
 *
 * <h2>Blank is not absent, and the distinction is the whole rule</h2>
 *
 * <p>A missing {@code name} is a caller with no opinion, and the filename is the right answer for
 * it. A blank one is a caller that meant to name this document and sent the field empty: falling
 * back to the filename would file it somewhere they did not choose and never say so, and <b>a name
 * is what a re-ingest matches on</b>, so one nobody chose is one nobody can re-ingest under. The
 * same distinction {@code JobStore} draws between a null session id and a blank one.
 *
 * <h2>Why this is in {@code requests} although no frame reads it</h2>
 *
 * <p>{@code POST /v1/documents} is the multipart upload and the breadth plan rules that it gets no
 * frame — bytes in a text frame have no precedent on this server, and {@code client.Capabilities}
 * carries that reasoning as a decision rather than a backlog item. So this is the one rule Task 2
 * moved that has exactly one caller today.
 *
 * <p>It moved anyway, and for the reason the pin exists: the rule is a fact about two request
 * fields and nothing else — no store, no domain object, nothing beyond two strings — which is what
 * this package is for, and leaving it inline would have left {@code DocumentController} holding one
 * throw for a reason that is about transport rather than about the rule. If a binary frame shape is
 * ever worth having, the sentence is already where its handler can reach it.
 */
public final class RequestedDocumentName {

  private RequestedDocumentName() {}

  /**
   * The name to file an upload under.
   *
   * @param name the request's own {@code name} part, or null for the filename
   * @param filename what the uploaded part was called, which may itself be null — the container
   *     does not promise one, and a document filed under nothing is {@code IngestService}'s
   *     question rather than this one's
   * @throws CallerFault if {@code name} is present and blank
   */
  public static String in(String name, String filename) {
    if (name != null && name.isBlank()) {
      throw new CallerFault(
          "the `name` field is blank. Leave it out to file the document under its"
              + " uploaded filename, because a name is what a re-ingest matches on"
              + " and one nobody chose is one nobody can re-ingest under");
    }
    return name == null ? filename : name;
  }
}
