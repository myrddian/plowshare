-- One fetched web page, held whole so an agent can read it back in windows
-- without the page being fetched a second time.
--
-- KEYED BY THE URL'S SHA-256, AND THE URL IS KEPT BESIDE IT. This differs
-- from `search_result_sets`, which deliberately keeps no query text at all --
-- see that migration's own comment. The two rows are not the same kind of
-- fact. A search's query text is a person's question, and `V34`'s argument is
-- that the one place a question belongs is the log, on the log's own
-- retention (spec §10), not a second copy sitting in a results table with a
-- retention story of its own. A URL is not a question -- it is the address of
-- a document that is, by the fact of having been fetched over the open web,
-- already public. An operator looking at what this table is holding, for
-- retention or for an incident, needs to be able to say what page a row is
-- (not just that some page produced this text), and hashing the URL away
-- would trade that answer for a privacy guarantee this row was never carrying
-- in the first place. So `url` is a column, `url_key` is still the primary
-- key, and the hash exists for the reason `UrlKey`'s own javadoc gives: an
-- agent hands back the same short key on every read of a page it already
-- fetched, not the URL restated in full each time.
--
-- NO ttl COLUMN, NO LIVENESS COLUMN. Both are live runtime-config keys, read
-- fresh by `FetchedPageStore.purgeExpired` on every call rather than copied
-- onto a row at write time -- `V34__search_result_sets.sql` makes this
-- argument in full for its own TTL and it carries here unchanged: a per-row
-- copy would freeze each page under the policy in force the moment it was
-- fetched, so an operator who shortens either window would find rows already
-- on disk still deaf to the change.
--
-- NO etag, NO content_hash, NO version. Change detection was considered and
-- rejected outright, not deferred to a later migration (spec §7): what this
-- table backs is a claim about what an agent SAW, on some past turn, not a
-- claim about the current state of the page at that URL. An etag or a content
-- hash is machinery for noticing that the world moved on since the fetch --
-- and re-fetching, or flagging staleness, on the strength of that notice --
-- which is a guarantee this feature does not make and does not want to look
-- like it makes. Machinery defending a guarantee nobody makes is machinery
-- for nothing, and it is named here, empty, so a later reader who wants to
-- add "just an etag column, for cheap revalidation" finds the reason it was
-- left out rather than reinventing the feature this table was built to not
-- have.
--
-- byte_size EXISTS SO RETENTION IS MEASURABLE. Nothing anywhere caps how
-- large an extracted page's text can be, and an operator deciding whether
-- this table's growth is a problem needs a number to sum without measuring
-- `text` itself on every row, on every question.
CREATE TABLE fetched_pages (
    -- SHA-256 of the URL, host lowercased, computed once by `UrlKey.of` and
    -- never here. Fixed-width and short, unlike the URL it stands for, so an
    -- agent can carry it in a tool result instead of restating the address in
    -- full on every read of a page it already fetched.
    url_key TEXT NOT NULL,

    -- The URL this row was fetched from, kept beside the key -- see this
    -- table's own comment above for why that differs from `search_result_sets`
    -- keeping no query text at all.
    url TEXT NOT NULL,

    -- The page's title, exactly as `ExtractedPage.title` produced it. Blank
    -- rather than NULL for a page with none -- `ExtractedPage`'s own
    -- canonicalising constructor already turns a missing title into "", so
    -- this column only ever holds what that record already guarantees.
    title TEXT NOT NULL,

    -- The page's whole extracted prose. Stored entire and never chunked at
    -- write time -- windowing is a read-time concern for whatever reads this
    -- column back, not a decision this table bakes in by splitting the text
    -- into rows it would then have to keep in order.
    text TEXT NOT NULL,

    -- When this page was fetched. The clock `purgeExpired`'s TTL half
    -- measures against, on `V34`'s `fetched_at` naming: this names what the
    -- timestamp is FOR, not merely when the row was written.
    fetched_at TIMESTAMPTZ NOT NULL,

    -- The last time an agent read this page back. Starts equal to
    -- `fetched_at` -- a page nobody has read yet has, at the moment it lands,
    -- been "read" only by the fetch that produced it -- and moves forward on
    -- every `touchRead`. This is the liveness half `purgeExpired` measures
    -- against, and the reason it exists as a second, independent timestamp
    -- rather than folded into `fetched_at` is the whole of this table's
    -- design decision; see `purgeExpired`'s own comment on `FetchedPageStore`
    -- for the argument in full.
    last_read TIMESTAMPTZ NOT NULL,

    -- The size, in bytes, of `text`'s UTF-8 encoding. See this table's own
    -- comment above for why it exists.
    byte_size BIGINT NOT NULL,

    CONSTRAINT fetched_pages_are_named_by_their_key PRIMARY KEY (url_key),

    -- THE KEY REALLY IS A SHA-256 AND NOT WHATEVER A CALLER HAD HANDY --
    -- `system_blocks_hash_is_a_sha_256`'s argument (`V32__turn_system_prompt.sql`)
    -- and `search_result_sets_a_key_is_a_sha_256`'s restatement of it
    -- (`V34__search_result_sets.sql`), carried over unchanged because the
    -- failure it guards against is the same shape: a caller that passed a raw
    -- URL into `FetchedPageStore.put` instead of hashing it first would
    -- otherwise write that string in, unrefused, as a primary key nothing
    -- downstream expects to be readable. This cannot verify the digest
    -- matches `url` -- Postgres has no SHA-256 over text without pgcrypto,
    -- and `UrlKey.of` is the one writer that holds that rule -- but it does
    -- refuse the shapes a caller that skipped the hashing would produce.
    CONSTRAINT fetched_pages_a_key_is_a_sha_256 CHECK (url_key ~ '^[0-9a-f]{64}$'),

    CONSTRAINT fetched_pages_a_url_is_not_blank CHECK (url <> ''),

    CONSTRAINT fetched_pages_byte_size_is_not_negative CHECK (byte_size >= 0)
);

COMMENT ON TABLE fetched_pages IS
    'One fetched web page, keyed by the SHA-256 of its URL (host lowercased) '
    'and held whole so an agent can read it back in windows without the page '
    'being fetched again. Written once per fetch by FetchedPageStore.put, '
    'which replaces rather than adds a row on a repeated key; last_read moves '
    'forward on every touchRead. purgeExpired deletes only a row that is both '
    'past its TTL and not being read -- see that method''s own comment for '
    'why liveness is an eviction guard as well as a refetch guard. No etag, '
    'content_hash or version: this table backs a claim about what an agent '
    'saw, not about the current state of the page, and change detection was '
    'rejected rather than deferred (spec section 7).';
