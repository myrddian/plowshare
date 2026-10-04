package io.aeyer.plowshare.server.api;

/**
 * The body of {@code PUT /v1/conversations/{id}/lifecycle}: where to put this conversation.
 *
 * @param lifecycle the state to move to, as the column spells it — {@code active}, {@code
 *     archived}, {@code to_be_ejected} or {@code ejected}.
 *     <p><b>A destination and not a verb</b>, which is the whole reason there is one endpoint here
 *     rather than four. {@code /archive} and {@code /unarchive} would each have to know which
 *     states they may be called from, so the table of what follows what would be spread across as
 *     many endpoints as there are edges; a destination lets {@code
 *     ConversationLifecycle.reachedFrom} answer that once, and a refusal quotes it rather than
 *     paraphrasing it.
 *     <p>Spelled as the column spells it, lower case with underscores, rather than as the Java
 *     constant. That is the spelling in every other wire value this server takes — {@code origin},
 *     {@code kind}, {@code state} — and it is what {@code GET /v1/conversations?lifecycle=} takes
 *     on the same vocabulary, so a caller that can read a listing can write this
 */
public record LifecycleRequest(String lifecycle) {}
