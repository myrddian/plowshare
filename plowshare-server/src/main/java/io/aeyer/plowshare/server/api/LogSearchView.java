package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.archive.LogSearch;
import java.time.Instant;
import java.util.List;

/**
 * One page of what a question's words reached in a tier's conversation log, and
 * everything a caller needs to ask for the next one.
 *
 * <h2>Why this is not {@link EntryPageView}</h2>
 *
 * <p>The paging half is the same and is right to be — {@code total}, {@code
 * offset} and {@code limit} are needed for that record's reasons word for word.
 * Two things are not.
 *
 * <p><b>A hit's text is a snippet and not an excerpt.</b> {@link
 * EntryView#excerpt} is the <em>opening</em> of an entry and its contract is that
 * {@code length > excerpt.length()} means "there is more after this"; a snippet
 * is the words around the match, from wherever in the entry the match happened,
 * with the matched words marked. A client that rendered one as the other would
 * offer to fetch "the rest" of something that has no rest.
 *
 * <p><b>And a search carries {@link Reach}, which a page has no equivalent
 * of.</b> A trajectory shows an ejected result in its place, marked as ejected;
 * a search cannot, because there is nothing left to match on and nothing to rank.
 * So an empty {@code hits} is only readable as "the log was searched and held
 * nothing" when the numbers beside it say what was searched. {@code
 * DocumentSearchResponse} carries {@code searchable}/{@code unsearchable} for
 * exactly this reason one corpus over.
 *
 * @param hits what matched, best first. Never null; empty for a question nothing
 *     matched and for a page past the end, which {@code total} tells apart
 * @param total how many entries matched in all — about the tier and not about
 *     this page
 * @param offset how many hits this page skipped, as asked for
 * @param limit how many hits this page could hold — <b>the number this server
 *     used and not the one the caller sent</b>, which differ exactly when the
 *     caller asked for more than {@code ConversationController.MOST_ENTRIES_A_PAGE}
 * @param reach what the search could and could not look at, in the same instant
 */
public record LogSearchView(
        List<Hit> hits, int total, int offset, int limit, Reach reach,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Retrieval retrieval) {

    public LogSearchView(List<Hit> hits, int total, int offset, int limit, Reach reach) {
        this(hits,total,offset,limit,reach,null);
    }
    public record Retrieval(String requestedMode, String effectiveMode, String totalMeaning,
            String snapshot, boolean truncated, boolean complete, String fallback,
            io.aeyer.plowshare.server.archive.PassageIndex.Coverage coverage,
            String generation, int queryEmbeddingCalls, String provenance) {}

    public static LogSearchView of(LogSearch found, int offset, int limit) {
        return new LogSearchView(
                found.hits().stream().map(Hit::of).toList(),
                found.total(),
                offset,
                limit,
                Reach.of(found.reach()));
    }

    /**
     * One entry a question's words reached.
     *
     * @param conversationId which conversation said it. <b>On every hit, unlike
     *     {@link EntryView}</b>, because a search crosses conversations and this
     *     is what turns a hit into something a reader can go and read
     * @param ordinal where in that conversation's log it came
     * @param turnOrdinal which turn produced it, or for a {@code summary} the
     *     last turn it stands for
     * @param kind what this is, as the column spells it. Always one of the four
     *     that reach a model; {@link Reach#recordedOnly} accounts for the rest
     * @param rank what {@code ts_rank_cd} scored this against the question,
     *     always above zero. <b>Not comparable across questions</b> — Postgres
     *     keeps no corpus-wide term statistics, so this is a cover density and
     *     not a calibrated relevance, and what it is good for is the order it
     *     puts one question's hits in
     * @param snippet the words around the match, with the matched words in
     *     square brackets. <b>Not a prefix of the entry</b>
     * @param length how many characters the entry really is, which is what says
     *     how much of it the snippet is not showing
     * @param supersededBy the ordinal of the summary that folded this entry away,
     *     or null. <b>A hit either way</b>: a fold does not unsay anything, and
     *     this is the field that tells a reader the model can no longer see it
     * @param handle the address {@code result_read} redeems the whole result at,
     *     null for every kind but {@code tool_result}. The only route from a hit
     *     back to the entire text
     * @param recordedAt when this was written, or null for an entry older than
     *     the column
     */
    public record Hit(
            String conversationId,
            int ordinal,
            int turnOrdinal,
            String kind,
            double rank,
            String snippet,
            int length,
            Integer supersededBy,
            String handle,
            Instant recordedAt,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            Evidence evidence) {
        public Hit(String conversationId,int ordinal,int turnOrdinal,String kind,double rank,String snippet,
                int length,Integer supersededBy,String handle,Instant recordedAt) {
            this(conversationId,ordinal,turnOrdinal,kind,rank,snippet,length,supersededBy,handle,recordedAt,null);
        }
        public Hit withEvidence(Evidence evidence) {
            return new Hit(conversationId,ordinal,turnOrdinal,kind,rank,snippet,length,supersededBy,handle,recordedAt,evidence);
        }
        static Hit of(LogSearch.Hit hit) {
            return new Hit(
                    hit.conversationId(),
                    hit.ordinal(),
                    hit.turnOrdinal(),
                    hit.kind().wireName(),
                    hit.rank(),
                    hit.snippet(),
                    hit.length(),
                    hit.supersededBy(),
                    // A string and not a UUID, as EntryView sends it: the id
                    // travels as text everywhere else on this surface.
                    hit.handle() == null ? null : hit.handle().toString(),
                    hit.recordedAt());
        }
    }

    /** Passage identity and the candidate list(s) that contributed to this entry's ranking. */
    public record Evidence(String retrievedBy,Integer passagePosition,String sourceRevision) {}

    /**
     * What the search looked at, and what it could not.
     *
     * <p>The three add up to every entry in the tier, and are sent as three
     * rather than as one "unsearchable" total because a client has something
     * different to say about each: an ejected payload is <em>gone and recorded as
     * gone</em>, and a roleless kind is <em>there and deliberately not
     * searched</em>.
     *
     * @param searched entries whose words the question was asked of
     * @param ejected entries a retention sweep took the payload of, keeping the
     *     row. Never a hit, and never silently missing
     * @param recordedOnly entries of a kind that never reaches a model — {@code
     *     attempt_failed}, {@code runtime_note}, {@code plan}, {@code
     *     diagnostic}. Read through {@code GET /v1/conversations/&#123;id&#125;/trajectory}
     */
    public record Reach(int searched, int ejected, int recordedOnly) {

        static Reach of(LogSearch.Reach reach) {
            return new Reach(reach.searched(), reach.ejected(), reach.recordedOnly());
        }
    }
}
