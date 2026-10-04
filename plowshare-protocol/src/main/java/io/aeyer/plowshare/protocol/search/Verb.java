package io.aeyer.plowshare.protocol.search;

/**
 * What a provider will do.
 *
 * <p>{@code FETCH} is declared here and served by nothing in this slice. It costs an enum constant
 * now and avoids a protocol change when the fetch facade lands, exactly as Aletheia's {@code
 * SearchHandler} reserves its verbs behind {@code UNSUPPORTED} defaults. Spec §9.
 */
public enum Verb {
  SEARCH,
  FETCH
}
