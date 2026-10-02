-- An address a model can ask for a stored tool result at, so that leaving a
-- result out of a later turn's prompt stops meaning losing it.
--
-- WHAT CHANGED ABOVE THIS FILE. Until now `agents.Compaction` DROPPED every
-- `tool_result` from a later turn's view. The row stayed here with its text
-- intact and a person could read it, but a model had no way to reach it, so it
-- read the same file again on the next turn and paid for it again. What replaces
-- the drop is a REFERENCE: the tool message is still there, in the same slot,
-- against the same `tool_call_id`, with its content swapped for a sentence
-- naming the tool, its arguments, the size of the stored result and this handle.
-- The model reads the sentence, decides whether it needs the thing, and asks for
-- it only if it does.
--
-- So the log is now the whole of what a conversation knows AND the whole of what
-- it can get back to. Compaction stops being lossy: a summary is a view, the
-- rows behind the seam keep their text, and -- because `EntryStore.redeem`
-- deliberately does not filter `superseded_by` the way the projection's read
-- does -- a handle survives the fold that hid the sentence carrying it.
--
-- WHY A NEW COLUMN AND NOT (conversation_id, ordinal). That pair addresses the
-- row already and would have cost no storage at all. It was rejected because it
-- is ENUMERABLE. A model holding one handle can write down another -- the same
-- conversation, a different number -- and probe it; ordinals are small integers
-- and a small integer invites arithmetic. The scoping check in `redeem` would
-- refuse a request across conversations, but WITHIN one conversation there is
-- nothing left to refuse: every row of it is legitimately that model's own
-- conversation, including the seven kinds a model is never shown. A random
-- 128-bit value cannot be constructed by guessing, which is the difference
-- between a guard that has to work and an attack that cannot be expressed.
--
-- WHY NOT `tool_call_id`. It is already on every row this column goes on, and it
-- is the obvious saving. It is not one. Those ids come from the endpoint: they
-- are whatever the model emitted, `JobRuntime` mints a stand-in only when one
-- arrives BLANK, and nothing anywhere guarantees a model will not emit `call_1`
-- in two different runs of the same conversation. An address that repeats is an
-- address that answers with the wrong row, silently, on the request that happens
-- to arrive second.
--
-- WHY ONLY A `tool_result` HAS ONE, which is the constraint at the foot of this
-- file and the security-relevant half of the change. A handle is an address a
-- MODEL MAY ASK FOR CONTENT AT. The other three projecting kinds are shown in
-- full, so an address for one of them buys nothing; and the four that do not
-- project -- `attempt_failed`, `runtime_note`, `plan`, `diagnostic` -- are
-- invisible ON PURPOSE. V11 wrote that rule as `entries_role_matches_kind` and
-- V12 spelled out the safety property it holds: "a kind added without deciding
-- what a model should see is invisible rather than accidentally visible". A
-- handle on a `diagnostic` would be a second door into exactly the rows that
-- rule exists to keep shut -- the harness talking ABOUT the conversation, handed
-- back to a participant in it. So the column is confined to the one kind that
-- has a reason for it, by a CHECK, in the schema, and not by whatever the
-- redeeming query remembers to filter on.
--
-- WHY THE STORE MINTS IT AND NOT THIS FILE. There is no DEFAULT here. V11's
-- `ordinal` is assigned by `EntryStore.append` and never by its caller, and this
-- follows it for the sharper version of the same reason: a caller that could
-- choose a handle could choose one already in use, or a predictable one, and the
-- one place that mints them is the one place that can be read to find out how.
-- `UUID.randomUUID()` draws from a SecureRandom, which `gen_random_uuid()` also
-- does -- the choice between them is not about entropy but about there being one
-- writer.
--
-- NULLABLE, so this migration runs against a live `entries` without rewriting
-- history. Every row already written keeps NULL, which means "no handle was
-- minted for this one" and never "this one cannot be redeemed as a matter of
-- policy": those rows are simply older than the mechanism, they are still in
-- every projection they were in before, and the worst a model does with one is
-- read the result inline exactly as it does today. The CHECK below is therefore
-- deliberately one-directional -- see its own comment.
ALTER TABLE entries ADD COLUMN handle UUID;

-- ONE ROW PER ADDRESS, IN THE WHOLE TABLE and not per conversation. A UUID is
-- global, so this is the claim the type already makes, written down where the
-- database can hold it: `redeem` names the conversation as well, and this index
-- is what makes that clause an AUTHORISATION CHECK rather than a way of telling
-- two rows apart. Without it, a mint that collided would leave the scoping check
-- deciding which row a handle meant, which is not a thing an authorisation check
-- should ever be load-bearing for.
--
-- A UNIQUE INDEX and not a UNIQUE constraint, for the NULLs. Postgres treats
-- NULLs as distinct in both, so either one permits the whole of the existing
-- table; the index is the form that says out loud it is also the read path.
--
-- IT IS THE READ PATH. `EntryStore.redeem` looks a handle up directly, and this
-- is the only index that serves it: `entries_one_per_place_in_a_conversation`
-- leads on `conversation_id, ordinal` and cannot help. V11 declined to add an
-- index for the projection's ORDER BY and gave the reason -- a conversation's
-- log is a few hundred rows and the primary key already cuts to it -- and that
-- reasoning does not carry here, because a redemption knows no conversation
-- until it has read the row. This index is not a guess about a cost; it is the
-- only thing that makes the lookup something other than a scan of every entry
-- every conversation has ever held.
CREATE UNIQUE INDEX entries_handle_addresses_one_row ON entries (handle);

-- A HANDLE IS EXACTLY WHAT A TOOL RESULT CARRIES -- in one direction, and the
-- missing direction is the point rather than an oversight.
--
-- `kind <> 'tool_result' => handle IS NULL` is enforced: nothing but a result
-- may be addressed, ever, including the rows written before this migration and
-- including a ninth kind added later by somebody who did not read this file.
--
-- The converse -- every `tool_result` HAS one -- is NOT enforced, and cannot be
-- while rows written before this file exist. Writing it as the biconditional
-- `entries_a_tool_result_is_exactly_what_answers_a_call` uses would make this
-- ALTER fail on any database that has ever held a conversation, and the repair
-- would be backfilling handles onto history -- minting addresses for results no
-- reference will ever name, to satisfy a constraint whose only real job is the
-- direction above. `EntryStoreTest.a_tool_result_is_exactly_what_carries_a_
-- handle` holds the other direction over what this build WRITES, which is where
-- that half belongs: it is a rule about the minting and not about the table.
ALTER TABLE entries ADD CONSTRAINT entries_only_a_tool_result_is_addressable
    CHECK (handle IS NULL OR kind = 'tool_result');

COMMENT ON COLUMN entries.handle IS
    'The address a model redeems this tool result at, or NULL for every other '
    'kind and for a result written before V13. A later turn is shown a '
    'reference in place of an earlier turn''s result -- same tool slot, same '
    'tool_call_id, content replaced by the tool, its arguments, the size and '
    'this handle -- and the content comes back only if the model asks for it. A '
    'random UUID and not (conversation_id, ordinal), because an ordinal is '
    'enumerable and this must not be: a model holding one handle must not be '
    'able to write down another. It survives a fold on purpose, which is what '
    'makes compaction non-destructive.';
