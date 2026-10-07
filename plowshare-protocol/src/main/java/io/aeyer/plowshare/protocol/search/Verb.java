package io.aeyer.plowshare.protocol.search;

/**
 * What a provider will do.
 *
 * <p>{@code FETCH} is declared here and served by nothing in this slice. It costs an enum constant
 * now and lets providers declare fetch support without changing the protocol. Unsupported verbs are
 * answered explicitly through {@link AnswerStatus#UNSUPPORTED}.
 */
public enum Verb {
  SEARCH,
  FETCH
}
