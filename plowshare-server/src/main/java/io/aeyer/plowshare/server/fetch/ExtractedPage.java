package io.aeyer.plowshare.server.fetch;

/**
 * A page reduced to what a reader wants: its title and its readable prose.
 *
 * <p>Nothing here remembers where the page came from, or when it was fetched, or what the raw
 * markup looked like. That is deliberate: this record is the boundary between {@link
 * PageExtractor}, which knows HTML, and everything downstream of it, which must not need to. A
 * later task stores {@link #text} whole and reads it back in windows; it has no use for a DOM.
 *
 * <p>Neither field is ever null. A blank title or an unparsable document is an ordinary answer
 * along this pipe, not a defect to guard against with a null check at every call site — see the
 * canonicalising constructor below, and {@link PageExtractor#extract} for the one case (empty
 * markup) that exercises it.
 */
public record ExtractedPage(String title, String text) {

  public ExtractedPage {
    title = title == null ? "" : title;
    text = text == null ? "" : text;
  }
}
