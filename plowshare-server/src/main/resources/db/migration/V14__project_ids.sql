-- A project stops being its name.
--
-- WHY THIS EXISTS. A project's canonical name is about to become
-- `<MACHINE>/<PATH>/<PROJ_NAME>` (`2026-09-03-harness-presence-design.md` §10),
-- which puts the machine that holds a project inside the project's identity.
-- That is the right identity — a project re-rooted on another box is a
-- different project, and §8's residual question dissolves — but it makes the
-- name a thing that *changes*: moving a project to a new machine rewrites it.
--
-- V1 and V6 have `memories.project` and `conversations.project` holding that
-- name directly. So a move would mean rewriting every memory row and every
-- conversation row of one project, in one transaction, or leaving the project
-- split across two names with half its history in each. Atomicity would be
-- something to engineer, and a partial failure would be unreadable: nothing in
-- either table says which of the two names is the live one.
--
-- With a surrogate key a move is one row and one column. `projects.name` keeps
-- being the natural key — unique, and what a person types — and stops being the
-- key anything points at. Every reference survives a rename untouched, so
-- atomicity stops being a requirement and becomes a property of updating a
-- single row.
--
-- This is the same indirection `2026-09-03-documents-schema-decisions.md` §1
-- chose for paragraph identity, for the same reason: a natural key that can
-- change must not be what foreign keys point at.
--
-- WHAT DOES NOT CHANGE, and both halves matter:
--
--   * NULL still means the global tier. V1: "Global is the absence of a project
--     rather than a project named 'global', so no project can be named into"
--     becoming it. A NULL `project_id` is exactly a NULL `project` was, and the
--     guards below exist so that no row acquires one by accident during this
--     migration.
--   * The API still takes names. `GET /v1/conversations?project=` and every MCP
--     tool are unchanged from outside; the name is resolved to an id once,
--     inside, and no id is ever shown to anybody.


-- ---------------------------------------------------------------------------
-- 1. `projects` gains the surrogate key
-- ---------------------------------------------------------------------------

-- BIGINT IDENTITY and not a `prj_` TEXT id, which is the shape every other key
-- in this schema has (`mem_`, `prp_`, `cnv_`). Those ids are minted the way
-- `protocol.MemoryIds` mints them because they are *shown* — they appear in the
-- index an agent reads, in API paths, and in `ORDER BY id`, and the ten padded
-- hex digits are there so that string order is minting order.
--
-- None of that is true here. A project id is never rendered, never ordered by
-- and never typed: the name is what a person says and what the API takes, and
-- the id exists solely so a rename does not have to be a rewrite. Minting it
-- the sortable way would mean writing that 2020-epoch arithmetic in SQL to fill
-- the rows already in this table, which is a second copy of `MemoryIds.suffix`
-- in a language that cannot share it -- and getting the zero-padding wrong
-- there fails silently, as a list that is merely almost sorted.
--
-- ALWAYS and not BY DEFAULT: nothing may choose an id. `ProjectStore.define` is
-- an upsert on the name and never names this column, and ALWAYS is what makes
-- that a rule the database holds rather than a habit the store has.
--
-- Existing rows are filled by the identity as the column is added; there are no
-- projects yet that this could get wrong, but a deployment with rows gets ids
-- in whatever order the rewrite visits them, which is fine precisely because
-- nothing reads order out of this column.
ALTER TABLE projects ADD COLUMN id BIGINT GENERATED ALWAYS AS IDENTITY;

-- The name stops being the primary key and stays unique. UNIQUE and not merely
-- an index: `ProjectStore.define`'s `ON CONFLICT (name)` needs a constraint to
-- name, and two rows called `payments` would make "the project called payments"
-- a question with two answers -- the exact ambiguity the old primary key was
-- quietly providing.
--
-- SET NOT NULL explicitly rather than relying on what dropping a primary key
-- leaves behind. Postgres does keep the NOT NULL, but a schema whose most
-- important column is non-null by side effect is one nobody can read.
ALTER TABLE projects DROP CONSTRAINT projects_pkey;
ALTER TABLE projects ALTER COLUMN name SET NOT NULL;
ALTER TABLE projects ADD CONSTRAINT projects_pkey PRIMARY KEY (id);
ALTER TABLE projects ADD CONSTRAINT projects_name_is_unique UNIQUE (name);

-- An identity starts at 1 and only climbs, so this CHECK refuses nothing that
-- could ever be written -- which is the point. It makes 0 an id no row holds,
-- provably and by the database rather than by convention, and that is what lets
-- a name nobody has defined resolve to a value that matches no row instead of
-- to NULL. NULL is the global tier: a read for an unknown project that resolved
-- to NULL would hand back every global memory as if it were that project's,
-- which is the one wrong answer this whole column exists to prevent.
ALTER TABLE projects ADD CONSTRAINT projects_id_is_positive CHECK (id > 0);


-- ---------------------------------------------------------------------------
-- 2. `workspace` becomes optional, because this table changes meaning
-- ---------------------------------------------------------------------------

-- UNTIL NOW `projects` HAS BEEN A TABLE OF WORKSPACE GRANTS, NOT OF PROJECTS.
-- Read V3 and `ProjectStore` together and they say so plainly: `find` is "a
-- project that has never been given a workspace is a question, not a mistake";
-- `all` is "every project that has a workspace"; `forget` DELETEs the row and
-- is documented as "drop a project's workspace, leaving its memories alone".
-- A project that nobody ran `define` on has memories, has conversations, and
-- has no row here at all.
--
-- A foreign key cannot be added to a table that only holds some of its
-- referents. So the table becomes what its name always claimed: one row per
-- project that exists, with `workspace` NULL for a project that has not been
-- given one. That is not new information -- it is the fact "this project has no
-- workspace", which was previously recorded as the absence of a row and is now
-- recorded as a NULL in one.
--
-- The three methods keep their exact answers by filtering on `workspace IS NOT
-- NULL`, and `forget` stops deleting: it nulls the workspace instead. That is a
-- gain rather than a concession -- deleting would now break every memory
-- pointing at the row, and nulling means a project that loses its workspace and
-- is later re-defined keeps the same id, so its memories are still its own.
--
-- `projects_workspace_named CHECK (workspace <> '')` is left exactly as V3
-- wrote it and still does its job: `NULL <> ''` is NULL, and a CHECK passes on
-- NULL. "No workspace" and "a workspace that is the empty string" stay
-- different things, and the second is still refused.
ALTER TABLE projects ALTER COLUMN workspace DROP NOT NULL;

COMMENT ON COLUMN projects.workspace IS
    'The directory this project''s jobs may reach, or NULL for a project that '
    'has never been given one. ProjectStore.find and .all answer only for rows '
    'that have one, which is the behaviour they had when a project without a '
    'workspace had no row at all.';


-- ---------------------------------------------------------------------------
-- 3. Every project a row names gets registered
-- ---------------------------------------------------------------------------

-- THE ORPHAN DECISION, AND IT IS NOT A DEFAULT. A memory or conversation naming
-- a project with no row is the ordinary case, not a corruption: V3 says it in
-- as many words -- "A project may hold memories with no workspace (every
-- project did, before this slice)". So there is nothing here to repair.
--
-- The two things this migration refuses to do:
--
--   * It does not drop the row. A memory is a thing an agent formed and a
--     person may have pinned, and V1 is built on nothing ever being deleted --
--     even a retired memory is kept as a tombstone. A migration that discarded
--     live memories to make a constraint pass would be the constraint deciding
--     what is true.
--   * It does not invent a workspace. `workspace` is a grant -- the only
--     directory a job's file tools may reach -- and a migration is not a thing
--     that may issue one. The registered row has NULL, which is exactly the
--     access the project had a minute ago: none.
--
-- What is left is the honest reading: the project already existed, and this
-- writes down the name it was already being called by. `defined_at` defaults to
-- now(), which is when the row appeared and not a claim about when the project
-- did; nothing reads it for a project with no workspace.
--
-- UNION and not UNION ALL, and wrapped in a subquery: a name in both tables
-- must produce one row, and `ON CONFLICT` binds to the INSERT rather than to
-- the last arm of a bare set operation.
INSERT INTO projects (name)
SELECT name FROM (
    SELECT DISTINCT project AS name FROM memories WHERE project IS NOT NULL
    UNION
    SELECT DISTINCT project AS name FROM conversations WHERE project IS NOT NULL
) AS named
ON CONFLICT (name) DO NOTHING;


-- ---------------------------------------------------------------------------
-- 4. `memories.project` becomes `memories.project_id`
-- ---------------------------------------------------------------------------

ALTER TABLE memories ADD COLUMN project_id BIGINT;

UPDATE memories SET project_id = p.id FROM projects p WHERE p.name = memories.project;

-- THE FOREIGN KEY `memories.project` COULD NEVER HAVE HAD. V1 created this
-- table before V3 created `projects`, so at the moment the column was written
-- there was no table for it to reference -- and once V3 arrived, V3 argued
-- against adding one on the grounds that a project may hold memories before it
-- has a workspace, which was true of what `projects` then meant. The result is
-- that a memory has been able to name a project that does not exist since the
-- first migration, and nothing has ever noticed.
--
-- Step 2 makes the referent set complete and step 3 fills it, so the reference
-- can finally be real. NO ACTION on delete, which is the default and is
-- deliberate: nothing in the server deletes a project any more (see `forget`
-- above), and if something ever tries, a project with memories should refuse
-- rather than cascade. ON DELETE SET NULL would be the disastrous spelling --
-- it would silently promote every memory of a deleted project into the global
-- tier.
ALTER TABLE memories ADD CONSTRAINT memories_project_exists
    FOREIGN KEY (project_id) REFERENCES projects (id);


-- ---------------------------------------------------------------------------
-- 5. And the same for `conversations`
-- ---------------------------------------------------------------------------

ALTER TABLE conversations ADD COLUMN project_id BIGINT;

UPDATE conversations SET project_id = p.id FROM projects p
    WHERE p.name = conversations.project;

ALTER TABLE conversations ADD CONSTRAINT conversations_project_exists
    FOREIGN KEY (project_id) REFERENCES projects (id);


-- ---------------------------------------------------------------------------
-- 6. Prove the backfill was total before anything is dropped
-- ---------------------------------------------------------------------------

-- THE ONE FAILURE MODE THAT WOULD BE INVISIBLE. The next statements drop the
-- text columns. If any row still had a name and no id, dropping the name would
-- leave NULL -- and NULL is the global tier, so a project memory would arrive
-- in the tier every agent everywhere reads, with nothing left in the row to say
-- it had ever been anywhere else. There is no query that finds that afterwards,
-- because the evidence is the column being deleted.
--
-- Step 3 makes this unreachable by construction; it is written down anyway for
-- the same reason V2 and V5 write down invariants Java already holds. What
-- guarantees it today is one INSERT twenty lines up, and a future edit to that
-- INSERT -- a WHERE that excludes something, a name that needs normalising --
-- would break this silently. Here it stops the migration instead, with the
-- transaction rolled back and the columns still there to look at.
DO $$
DECLARE
    stranded BIGINT;
BEGIN
    SELECT count(*) INTO stranded FROM memories
        WHERE project IS NOT NULL AND project_id IS NULL;
    IF stranded > 0 THEN
        RAISE EXCEPTION
            'V14 would strand % memories in the global tier: their project '
            'name resolved to no project row. Nothing is dropped; fix the '
            'registration above and re-run.', stranded;
    END IF;

    SELECT count(*) INTO stranded FROM conversations
        WHERE project IS NOT NULL AND project_id IS NULL;
    IF stranded > 0 THEN
        RAISE EXCEPTION
            'V14 would strand % conversations in the global tier: their '
            'project name resolved to no project row. Nothing is dropped; fix '
            'the registration above and re-run.', stranded;
    END IF;
END $$;


-- ---------------------------------------------------------------------------
-- 7. Retire the text columns
-- ---------------------------------------------------------------------------

-- Dropped rather than kept in step with the id, because two columns holding one
-- fact is two things that can disagree and the whole point of the surrogate key
-- is that a rename touches one place. It takes `memories_project_named` and
-- `conversations_project_named` with it -- the CHECKs that refused a blank
-- project name -- and that argument does not go away: it moves to
-- `projects_name_named`, which V3 already wrote and which now guards the single
-- place a project name is stored.
--
-- It also takes the two indexes with it, since both lead with the dropped
-- column, so both are rebuilt below on the id. The names are reused: they name
-- the read, not the column, and every comment elsewhere that cites them stays
-- true.
ALTER TABLE memories DROP COLUMN project;
ALTER TABLE conversations DROP COLUMN project;

-- V1's index, unchanged in purpose: "Recall reads one tier at a time and only
-- ever wants live records, so the index leads with the two columns every read
-- filters on."
CREATE INDEX memories_home_state ON memories (project_id, state);

-- V6's, likewise: "every conversation in one home, oldest first". It still
-- indexes NULL project ids along with the rest, which is still the point -- a
-- btree holds NULLs, and the global tier is a real tier this query really
-- reads.
CREATE INDEX conversations_home ON conversations (project_id, created_at);

COMMENT ON COLUMN memories.project_id IS
    'The project this memory belongs to, or NULL for the global tier. V1''s '
    'rule is unchanged: global is the absence of a project, not a project '
    'named ''global''.';

COMMENT ON COLUMN conversations.project_id IS
    'The project this conversation belongs to, or NULL for the global tier. '
    'Same rule as memories.project_id, and the same one V1 states.';
