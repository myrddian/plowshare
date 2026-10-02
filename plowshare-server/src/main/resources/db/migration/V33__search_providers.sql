-- Every provider process this server knows how to reach, and what has
-- happened to each one.
--
-- WHAT A ROW IS FOR. Spec §4: "a row records what exists and what has
-- happened to it; a key records what the operator wants." `POST
-- /v1/search/providers` fetches `/v1/search/capabilities` from a URL an
-- operator gave it and upserts the answer here. `ProviderStore.recordOutcome`
-- then updates the health columns once per terminal search outcome. Nothing
-- else writes this table.
--
-- WHAT THIS TABLE IS DELIBERATELY NOT, AND WHY A READER WILL EXPECT IT ANYWAY.
-- There is no `enabled` column, no `rung` and no `timeout_ms`. All three read
-- as obviously missing to anyone who has met a provider registry before --
-- Aletheia's has all three on the row -- so the absence is recorded here as a
-- decision rather than left for a later migration to "fix". Ladder order and
-- membership live in `plowshare.search.ladder`, a runtime-config key, and
-- per-rung timeout is a policy value beside it; both are the OPERATOR'S
-- opinion about a provider this table only describes. Folding them in here
-- would let the same fact be set two ways -- a row's flag and a key's
-- membership -- and disagree, which is what V28's `RuntimeConfig` javadoc
-- calls the thing that drifts. A provider absent from the ladder is thereby
-- disabled; that is the whole mechanism, and it needs no flag beside it to
-- say so a second time. Spec §4.
--
-- WHAT THE ROW DOES NOT CHECK: WHETHER THE URL IS REACHABLE RIGHT NOW. That is
-- `ProviderStore.recordOutcome`'s job, on the read side, once a search has
-- actually tried it. Registration itself is required to fail at the moment an
-- operator caused it (Spec §4) by probing `/v1/search/capabilities` BEFORE
-- this table is touched at all -- so a row existing here already means the
-- probe succeeded once. What can go stale afterwards is what
-- `consecutive_failures` is for.
CREATE TABLE search_providers (
    -- THE KEY IS THE PROVIDER'S OWN provider_key, NOT A SEQUENCE. Every write
    -- this table takes is a registration, and a registration is inherently an
    -- upsert on the key a provider's capabilities response names itself with
    -- -- registering "brave" twice is not two providers, it is one deployment
    -- corrected. A synthetic `id` would still need a UNIQUE constraint on
    -- `provider_key` to make `ON CONFLICT (provider_key)` mean anything, so it
    -- would buy nothing and cost a second column nothing here reads by.
    provider_key TEXT NOT NULL,

    -- Where the adapter sends the request. Owned by the operator who
    -- registered it, not by the provider's own facts -- a provider does not
    -- get to say where it lives, because that is exactly the value a
    -- confused or malicious deployment would want to lie about.
    base_url TEXT NOT NULL,

    -- ------------------------------------------------------------------
    -- The capability facts, as `ProviderFacts` carried them off the wire.
    -- ------------------------------------------------------------------
    --
    -- FLAT COLUMNS, NOT A JSON BLOB, WITH ONE NAMED EXCEPTION. The obvious
    -- alternative is `facts JSONB` holding the capabilities response
    -- verbatim. That was rejected because it moves the shape of a provider's
    -- facts somewhere no migration can check: a `CHECK` can hold `cost_class`
    -- to three known values, a `jsonb` column cannot be told that its
    -- `costClass` key is one of them without a trigger reimplementing what a
    -- column constraint already does for free, and a typo in a JSON key
    -- would silently read back as absent rather than fail on write. Flat
    -- columns put every fact where the schema itself is the check.
    --
    -- THE ONE EXCEPTION IS `verbs`, AND IT IS AN ARRAY BECAUSE THAT IS WHAT A
    -- SET OF ENUM NAMES IS. `ProviderFacts.verbs` is a `Set<Verb>`; a
    -- provider can serve more than one, `Verb.FETCH` is reserved in the
    -- contract today for exactly this reason (`Verb`'s own javadoc), and a
    -- second table for the join would be one row per verb per provider for a
    -- fact that is read whole, never filtered on, and never grows past two
    -- members before the next slice. `verbs TEXT[]` stores the enum's own
    -- names, so a value here is either a name `Verb.valueOf` accepts or the
    -- row was not written by this store.
    name TEXT NOT NULL,
    version TEXT NOT NULL,
    -- Nullable, unlike everything around it: a provider that answers its
    -- capabilities probe without a description is not lying about anything,
    -- it simply has nothing to say, and `ProviderFacts` itself carries no
    -- constraint forcing one.
    description TEXT,
    verbs TEXT[] NOT NULL,
    cost_class TEXT NOT NULL,
    network_tier TEXT NOT NULL,
    max_results INT NOT NULL,
    max_query_length INT NOT NULL,
    domain_exclusion BOOLEAN NOT NULL,

    -- When this row's facts were last written by a registration. Moves on a
    -- re-registration for the same reason `consecutive_failures` resets on
    -- one, below: a row that kept its very first timestamp across a
    -- redeployment months later would answer "when was this provider set up"
    -- with a date that describes nothing running today.
    registered_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- ------------------------------------------------------------------
    -- What has happened to it since. Written only by `recordOutcome`.
    -- ------------------------------------------------------------------

    -- NULL until the first search that ever tries this provider completes.
    -- A freshly registered row has been PROBED (the capabilities fetch that
    -- registration itself performs) but never SEARCHED, and those are
    -- different facts -- a probe answers "does this URL speak the contract",
    -- a search outcome answers "did the last real call succeed" -- so a row
    -- that has only been probed reports no health at all rather than a
    -- fabricated success.
    last_health_at TIMESTAMPTZ,
    -- `AnswerStatus.name()` as a free string, not `CHECK (... IN (...))`
    -- against that enum's members. Every other free-standing status column
    -- this project has added is checked against its known values at the
    -- table (`entries_kind_is_known`, `turns_ending_is_known`), and the
    -- omission here is deliberate rather than a miss: that enum lives in
    -- `plowshare-protocol`, a jar this migration cannot import a symbol
    -- from, and hand-copying its three names into a `CHECK` would be a
    -- second place that has to be edited the day a fourth status is added to
    -- the wire type -- the exact drift V28's `RuntimeConfig` javadoc argues
    -- against, one layer up.
    last_health_status TEXT,
    -- The message an operator reads on a failing rung. NULL for a row that
    -- has never failed, and cleared back to NULL by the same `recordOutcome`
    -- call that clears `consecutive_failures` on a success -- a stale
    -- "timeout" sitting beside a currently-healthy provider would be read as
    -- current by anyone who did not also check the counter beside it.
    last_health_message TEXT,
    -- HOW MANY TIMES IN A ROW THE LAST SEARCH THROUGH THIS PROVIDER FAILED.
    -- RESETS ON UPSERT, AND THIS IS THE DECISION WORTH ARGUING. A
    -- re-registration is a new deployment -- a fixed API key, a restarted
    -- container, a different box entirely, all reachable at the same
    -- `provider_key` -- and carrying a dead deployment's failure count
    -- forward into a healthy one would leave the new provider skipped by
    -- `plowshare.search.failure-threshold` for a reason nothing on screen
    -- explains: an operator who just fixed the thing would see it still
    -- being passed over, with no failure of its own to point at.
    consecutive_failures INT NOT NULL DEFAULT 0,

    CONSTRAINT search_providers_are_named_by_their_key PRIMARY KEY (provider_key),

    -- Belt beside the primary key's suspenders: a primary key already forbids
    -- NULL, but not the empty string, and an empty `provider_key` is not a
    -- name anything downstream could route on or log usefully.
    CONSTRAINT search_providers_a_key_is_not_blank CHECK (provider_key <> ''),

    -- base_url IS CHECKED FOR SCHEME AND FOR NO TRAILING SLASH, SO THE
    -- ADAPTER CONCATENATES WITHOUT DEFENSIVE STRIPPING AT EVERY CALL SITE.
    -- The remote adapter builds every request as `baseUrl + "/v1/search"`;
    -- without this pair, that adapter (or every future caller of `find`)
    -- would need its own `stripTrailingSlash` and its own "did somebody
    -- paste a bare host" guard, repeated wherever a `Registration` is read.
    -- Enforcing both shapes once, here, means a row that exists is a row an
    -- adapter can concatenate onto blindly.
    CONSTRAINT search_providers_a_base_url_is_http CHECK (base_url ~ '^https?://'),
    CONSTRAINT search_providers_a_base_url_has_no_trailing_slash CHECK (base_url !~ '/$'),

    -- A provider that serves nothing is not a provider; `ProviderFacts` never
    -- constructs an empty set from the wire (Task 1's `SearchContractTest`
    -- covers the null case, not the empty one), so this closes the gap
    -- between "empty" and "absent" that a Java record alone cannot.
    CONSTRAINT search_providers_declares_at_least_one_verb CHECK (cardinality(verbs) > 0),

    -- A count that ever went negative would mean `recordOutcome`'s `CASE`
    -- branch inverted somewhere between here and the caller, and this is the
    -- one place that can catch that class of bug regardless of which caller
    -- introduced it.
    CONSTRAINT search_providers_failures_are_not_negative CHECK (consecutive_failures >= 0)
);

COMMENT ON TABLE search_providers IS
    'Every provider process this server has been told to route search calls '
    'to, keyed by the provider''s own key. Written by registration (upsert) '
    'and by recordOutcome (health columns only). Carries no enabled flag, no '
    'rung and no timeout: ladder membership and per-rung policy are operator '
    'opinion and live in plowshare.search.ladder and its neighbouring '
    'runtime-config keys, not on this row. Spec section 4.';
