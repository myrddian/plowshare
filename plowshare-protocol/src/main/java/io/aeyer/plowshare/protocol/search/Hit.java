package io.aeyer.plowshare.protocol.search;

/** One result. Three fields, because a model reads three fields. */
public record Hit(String url, String title, String snippet) {
  public Hit {
    url = io.aeyer.plowshare.protocol.WebContractValues.url(url);
    title = io.aeyer.plowshare.protocol.WebContractValues.text(title, "search title", 32768, false);
    snippet =
        io.aeyer.plowshare.protocol.WebContractValues.text(
            snippet, "search snippet", 1048576, false);
  }
}
