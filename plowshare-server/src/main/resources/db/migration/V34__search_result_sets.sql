-- One search's results, held so the model can page through them without the
-- provider being asked again.
--
-- A RESULT SET, NOT A CACHE, AND THE NAME IS THE WHOLE DESIGN. A cache is
-- keyed on a query so that a LATER, DIFFERENT search can reuse the same
-- answer, and the question its lifetime has to answer is "how stale is this".
-- What this table holds is answered to the same request that produced it --
-- the ladder ran once, wrote its hits here, and every following page reads
-- them back -- so the question its lifetime answers is "how long may paging
-- continue", which is a different question with a different owner. Aletheia
-- conflated the two and needed a 430-line `QueryCacheService`, several
-- repositories and a TTL policy class to keep "reuse across searches" and
-- "keep paging alive" from stepping on each other; this needs one table
-- because it is only ever the second question. Cross-search reuse -- serving
-- one query's stored set to a different, later query that happens to hash the
-- same -- is not a feature this table provides; two searches that ask the
-- same thing get two ladder runs and, since `QueryKey` is deterministic, the
-- second `put` simply replaces the first's row rather than reading it back.
--
-- WRITTEN ONCE PER SEARCH, READ MANY TIMES, NEVER UPDATED. `ResultSetStore.put`
-- is the only writer and it is called once, right after the ladder returns.
-- Paging is `ResultSetStore.get` slicing the same JSON array by offset; no
-- statement in this migration or the store above it ever appends to a row
-- once written, so "replaces the set rather than adding one" describes the
-- one write path a second search through the same key takes, not an update
-- to an existing row's contents.
CREATE TABLE search_result_sets (
    -- SHA-256 of the normalised query text and the requested max, never the
    -- query itself. `QueryKey.of` is the one place that computes it, on
    -- `TurnStore.hashOf`'s idiom (`V32__turn_system_prompt.sql` already
    -- carries the argument for SHA-256 over a 32-bit digest or MD5; it is not
    -- repeated here).
    --
    -- THE QUERY TEXT ITSELF IS NOT A COLUMN HERE, AND THAT IS DELIBERATE. A
    -- stored set is not a record of what anybody asked -- the log is that
    -- record, on whatever retention policy the log carries (spec §10) -- and
    -- a second copy of the question sitting in this table would have a
    -- different lifetime (this table's TTL, not the log's) and a different
    -- retention story than the one place a query is meant to be kept. The key
    -- is one-way by construction: `query_key` cannot be turned back into the
    -- text that produced it, so this row answers "what came back" and nothing
    -- about "what was asked".
    query_key TEXT NOT NULL,

    -- Which provider answered, so the model (or an operator reading this
    -- table) can tell whose results it is paging through. Not a foreign key
    -- to `search_providers`: a provider can be deregistered while a result
    -- set it produced is still being paged, and that set's history of who
    -- answered it should not disappear along with the registration, nor
    -- should a deregistration be blocked by an unrelated row it has nothing
    -- to do with going forward.
    provider_key TEXT NOT NULL,

    -- The hits themselves, as `Hit(url, title, snippet)` serialises, in the
    -- order the provider returned them.
    --
    -- JSONB AND NOT A CHILD TABLE. The obvious normalised alternative is
    -- `search_result_set_hits(query_key, position, url, title, snippet)`, one
    -- row per hit. That buys ordering and per-column typing for a value
    -- nothing ever queries by: no route filters hits by url or joins on one,
    -- paging reads the whole set and slices it in Java, and the set is
    -- written exactly once and never amended (see above). A child table would
    -- add a join, an ordering column to maintain, and a second place a
    -- partial write could leave the set inconsistent, all to serve a query
    -- this feature never makes. `V11__entries.sql`'s `tool_calls` column
    -- already draws the same line for the same reason: "JSONB and not TEXT,
    -- although nothing queries inside it today... a document with a shape
    -- this server both writes and reads back". A hit is three strings this
    -- server writes back whole; jsonb also refuses a malformed document at
    -- the INSERT, same as that column.
    hits JSONB NOT NULL,

    -- When this set was fetched. Not "when this row was written" phrased as
    -- `created_at` -- `fetched_at` names what the timestamp is FOR, which is
    -- the clock `get` and `purgeExpired` measure staleness against.
    fetched_at TIMESTAMPTZ NOT NULL,

    -- ------------------------------------------------------------------
    -- What is deliberately not a column here.
    -- ------------------------------------------------------------------
    --
    -- THERE IS NO ttl COLUMN. The TTL is `plowshare.search.result-set-ttl`, a
    -- live runtime-config key (`SearchProperties.resultSetTtlNow`), read
    -- fresh on every `get` and `purgeExpired` call rather than copied onto a
    -- row at write time. A per-row copy would freeze each set under the
    -- policy in force the moment it was fetched: an operator who shortens the
    -- TTL because paging is being abused would find every set already on
    -- disk still honouring its old, longer allowance, and one who lengthens
    -- it to let a slow client keep paging would find every existing set
    -- deaf to the change. Reading the live value at query time means one
    -- operator decision governs every stored set at once, which is the same
    -- shape `V28__runtime_config.sql`'s live keys already argue for the
    -- ladder and the failure threshold.

    CONSTRAINT search_result_sets_are_named_by_their_key PRIMARY KEY (query_key),

    -- THE KEY REALLY IS A SHA-256 AND NOT WHATEVER A CALLER HAD HANDY --
    -- `system_blocks_hash_is_a_sha_256`'s argument (`V32__turn_system_prompt.sql`),
    -- carried over verbatim because the failure it guards against is the same
    -- shape. This cannot check that the digest MATCHES its query -- Postgres
    -- has no SHA-256 over text without pgcrypto, and `QueryKey.of` is the one
    -- writer that holds that rule -- but it does refuse the shapes a caller
    -- that skipped the hashing would produce. That matters more here than at
    -- `system_blocks`: the one fact this table exists to keep out of the
    -- database is the query text itself (see `query_key`'s own comment
    -- above), and a caller that passed the raw string into `ResultSetStore.put`
    -- instead of hashing it first -- a one-line mistake, and the kind a
    -- caller not yet written could make on its first day -- would otherwise
    -- write that exact string in unhashed, in the open, with nothing at any
    -- layer to notice. A constraint that refuses anything that is not 64 lower-
    -- case hex characters cannot verify the hash is correct, but it does
    -- verify that SOMETHING was hashed, which is the one property this table
    -- is not allowed to fail silently on.
    CONSTRAINT search_result_sets_a_key_is_a_sha_256 CHECK (query_key ~ '^[0-9a-f]{64}$'),

    CONSTRAINT search_result_sets_a_provider_key_is_not_blank CHECK (provider_key <> ''),

    -- A search that returned nothing is still a search that happened, and the
    -- model paging it should see an empty page rather than this table
    -- refusing to hold the outcome -- so `hits` may be `[]`, and this
    -- constraint only rules out a document that is not a JSON array at all
    -- (an object, a bare string, a bare number), which `ResultSetStore` never
    -- writes and would signal a caller that bypassed it.
    CONSTRAINT search_result_sets_hits_is_an_array CHECK (jsonb_typeof(hits) = 'array')
);

COMMENT ON TABLE search_result_sets IS
    'One search''s results, keyed by the SHA-256 of its normalised query text '
    'and requested max (never the query text itself), held so the model can '
    'page through them without the provider being asked again. Written once '
    'per search by ResultSetStore.put, which replaces rather than appends on '
    'a repeated key; read by get and purgeExpired against the live '
    'plowshare.search.result-set-ttl, never a per-row copy. This is a result '
    'set and not a query cache: its lifetime question is how long paging may '
    'continue, not how stale an answer is, and it is never read by a search '
    'other than the one that wrote it.';
