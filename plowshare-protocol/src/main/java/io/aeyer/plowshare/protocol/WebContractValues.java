package io.aeyer.plowshare.protocol;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/** Field bounds for fetched/search content. These checks confer no network access or trust. */
public final class WebContractValues {
  private WebContractValues() {}

  /**
   * Preserve a usable public HTTP(S) reference; embedded credentials and control text are refused.
   */
  public static String url(String value) {
    ContractValues.identity(value, "web URL", 8192);
    try {
      var address = new URI(value);
      if (address.getScheme() == null
          || !Set.of("http", "https").contains(address.getScheme().toLowerCase(Locale.ROOT))
          || address.getHost() == null
          || address.getRawUserInfo() != null)
        throw new IllegalArgumentException("web URL must be HTTP(S) without credentials");
    } catch (URISyntaxException invalid) {
      throw new IllegalArgumentException("invalid web URL", invalid);
    }
    return value;
  }

  /** Narrative text is retained verbatim; its destination still requires contextual escaping. */
  public static String text(String value, String field, int maximum, boolean required) {
    return ContractValues.text(value, field, maximum, required);
  }
}
