package io.aeyer.plowshare.server.api;

/**
 * What {@code POST /v1/documents/&#123;id&#125;/stance} is asked.
 *
 * <p><b>{@code claim} and not {@code query}</b>, which every other read on this resource calls the
 * same field. The difference is real: a query is something you want to find, and this field is a
 * <em>statement</em> that gets negated — the server prefixes {@code "not "} and scores both. "Does
 * X inhibit Y" negates to nothing useful; "X inhibits Y" is what this route is for, and a field
 * name is the cheapest place to say so.
 *
 * @param claim the statement to score against this document, in plain language. A sentence rather
 *     than a question
 */
public record StanceRequest(String claim) {}
