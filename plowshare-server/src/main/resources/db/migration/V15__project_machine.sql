-- A project stops being a path and becomes a place.
--
-- WHY THIS EXISTS. `2026-09-03-harness-presence-design.md` §5 asks for one
-- thing above everything else in this area: that **the code always knows which
-- of the two trust models applies to a given path**. `LocalProvider` reads the
-- *server's* disk directly; `RemoteProvider` reads a *client's* through the
-- leash that client enforces. Both are correct, they coexist permanently, and
-- the spec's own words for the failure mode are that this "is the shape of
-- thing that hides for months once it becomes implicit".
--
-- It had become implicit. `AgentsConfig.serverCannotServe` decided which model
-- applied by probing: it read the project's `workspace` and asked whether this
-- server has a directory there. That probe cannot tell the two states apart,
-- and §13.3 recorded the fact rather than the fix -- "the two states are
-- byte-identical in the row":
--
--   * a project that has always lived on somebody's laptop, and
--   * a project that lived on this server until somebody deleted the directory
--
-- are one row with one `workspace` this machine cannot reach. The first wants
-- the sentence "no presence serves this project, start a client on the machine
-- that holds it". The second is an outage, and it must keep ending the run.
-- Guessing between them means being wrong half the time about which of two
-- unrelated things an operator has to go and fix.
--
-- So the discriminator is stored instead of inferred. `machine` names the box
-- the project's files are on. It is the `<MACHINE>` of §10's canonical name
-- `<MACHINE>/<PATH>/<PROJ_NAME>`, and §10 already argued at length that this
-- half of the identity belongs in the row: "which machine holds it is stable
-- and belongs in the identity; whether a live session is currently serving it
-- is ephemeral and stays a runtime registry." Both halves are still true and
-- they are still different fields -- `PresenceRegistry` is the second one and
-- nothing here replaces it. A project rooted nowhere right now is still a
-- project, and its row still says which machine it is on.
--
-- WHAT THIS OPENS, and it is the reason the column is worth a migration rather
-- than a better guess. Until now there was **no way to create a project whose
-- files are on another machine at all**. §13.4 found it: `ProjectStore.define`
-- validates the workspace against the *server's* disk and refuses otherwise, so
-- "define a project at a path that only exists on my laptop" is not a thing an
-- operator can type. The only two ways a row came to exist were `define` (a
-- server directory required) and `ProjectIds.toWrite` (the first memory written
-- into a name). The thing presence exists for could not be expressed.
--
-- With this column it can, and the verb turns out not to be missing: **the
-- client already declares `?session=&machine=&root=&project=` when it opens the
-- file channel, and that declaration is the registration.** There is no
-- server-side validation of the path, and there must not be -- the server does
-- not have that disk, and a check it cannot perform would be theatre. The
-- client is the enforcement point for its own files and is therefore the
-- authority on them, which is the same reasoning that puts `ProjectTools` on
-- the MCP side: the party that sets the leash must not be a party the leash
-- binds.


-- ---------------------------------------------------------------------------
-- 1. The column, and what NULL means
-- ---------------------------------------------------------------------------

-- NULLABLE, AND NULL MEANS THIS SERVER. Not a default of the hostname, and not
-- NOT NULL with a sentinel. §10 is explicit that the machine name is a CLIENT
-- property: "the server cannot know what machine a session sits on unless the
-- session declares it". A server that wrote its own hostname into this column
-- would be asserting an identity nothing asked it for, and two servers restored
-- from one backup would then disagree about rows neither of them wrote.
--
-- NULL is the same shape `memories.project_id` uses for the global tier and for
-- the same reason: the absence of a value is a meaning in its own right, and
-- inventing a name for it ("localhost", "server") creates a string a client
-- could also send, at which point the two are indistinguishable.
--
-- TEXT and not a foreign key to a `machines` table. There is nothing to
-- reference: a machine name is whatever a client says it is -- §10's known
-- collision mode is two laptops both defaulting to `MacBook-Pro.local` -- and a
-- table of them would be a table of strings clients have used, which is a log
-- rather than a referent set.
ALTER TABLE projects ADD COLUMN machine TEXT;

-- The same rule V3 wrote for `workspace`, for the same reason and spelled the
-- same way: NULL and the empty string are different things, and only the first
-- is a meaning. `NULL <> ''` is NULL and a CHECK passes on NULL, so this
-- refuses a blank machine name without touching a server-rooted row.
ALTER TABLE projects ADD CONSTRAINT projects_machine_named CHECK (machine <> '');


-- ---------------------------------------------------------------------------
-- 2. THE DISCRIMINATOR IS THE PAIR, NOT THIS COLUMN ALONE
-- ---------------------------------------------------------------------------

-- READ THIS BEFORE WRITING ANY QUERY AGAINST EITHER COLUMN. "NULL means the
-- server" is exact only for a row that has a workspace. There are three states
-- and not two, and collapsing the last two is the mistake this comment exists
-- to prevent:
--
--   machine IS NULL     AND workspace IS NOT NULL  -> SERVER-ROOTED. `define`
--       validated that directory against this server's own disk before writing
--       it, so this is a fact somebody established and not an assumption. This
--       is `LocalProvider`'s trust model, and a workspace that vanishes under
--       it is an outage that ends the run -- exactly as before this migration.
--
--   machine IS NOT NULL AND workspace IS NOT NULL  -> ROOTED ELSEWHERE. The
--       pair is the place: the machine and the path on it, as the client that
--       holds the files spells them. This is `RemoteProvider`'s trust model and
--       `workspace` here is a string this server MUST NOT resolve -- see the
--       column comment below, which is where that rule is written down.
--
--   machine IS NULL     AND workspace IS NULL      -> NO PLACE AT ALL. Not "the
--       server": the server has no directory for it and never claimed one. This
--       is a project that has an id, a name and an archive and has never been
--       given anywhere to be -- which V14 made a state the table can hold, and
--       which is the ordinary shape of a project an agent wrote a memory into.
--
-- The fourth combination is not a state. A machine with no path on it names a
-- box and not a place, and a row holding one would make `<MACHINE>/<PATH>/
-- <PROJ_NAME>` uncomposable while looking, to every query that tests `machine
-- IS NOT NULL`, exactly like a project somebody had rooted.
ALTER TABLE projects ADD CONSTRAINT projects_machine_has_a_place
    CHECK (machine IS NULL OR workspace IS NOT NULL);


-- ---------------------------------------------------------------------------
-- 3. What happens to the rows that are already here
-- ---------------------------------------------------------------------------

-- NOTHING, AND THAT IS A DECISION RATHER THAN THE ABSENCE OF ONE. Every row in
-- this table was created under the old rules, so every one of them gets NULL --
-- and it is worth being precise about why that is right for both kinds of row,
-- because they arrive at NULL by different routes and only one of them is the
-- server.
--
--   * A ROW WITH A WORKSPACE came from `ProjectStore.define`, which refuses any
--     path that is not a directory on the machine the server runs on. So NULL
--     records something that was checked at the time it was written: those
--     files are here. This is the only backfill in this migration that asserts
--     anything, and the assertion was already made by the code that wrote the
--     row.
--
--   * A ROW WITH NO WORKSPACE came from `ProjectIds.toWrite` -- an agent wrote
--     a memory into a name nobody had defined -- or from V14's step 3, which
--     registered every project `memories` and `conversations` already named.
--     Those projects have never had a place. NULL here does NOT say the server
--     holds them; combined with the NULL workspace it says the third state
--     above, which is the true one. §12.1 settled the equivalent question for
--     names and settled it the same way: "a row nothing has ever rooted is a
--     project that has never been rooted, which is a coherent state."
--
-- The alternative was to invent something -- the server's hostname for rows
-- with a workspace, a placeholder for the rest -- and V14's own orphan decision
-- is the precedent against it: "a migration is not a thing that may issue" a
-- grant, and it is not a thing that may assert a location either. What it can
-- do is write down what was already true, which is what NULL does here.
--
-- AND THE PLACELESS ROWS ARE THE POINT. They are precisely the projects a
-- presence wants to claim: a project whose files are on a laptop has an archive
-- here and no workspace here, which is §13.4's whole complaint. Because their
-- machine is NULL and their workspace is NULL, a client declaring a presence
-- can fill both in one write and the project keeps its id -- and therefore
-- keeps every memory and every conversation already filed under it. A backfill
-- that had guessed "the server" for those rows would have locked every existing
-- project out of the feature this column exists to deliver.


-- ---------------------------------------------------------------------------
-- 4. What each column means now, said in the schema
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN projects.machine IS
    'The machine this project''s files are on, as the client holding them calls '
    'itself, or NULL for this server. Read it with workspace and never alone: '
    'NULL with a workspace is server-rooted, NULL without one is a project that '
    'has no place at all. It is the <MACHINE> of the canonical name '
    '<MACHINE>/<PATH>/<PROJ_NAME>, and it is written by a client declaring a '
    'presence -- never guessed by the server, which cannot know it.';

-- V14's comment for this column is superseded rather than contradicted: it said
-- "the directory this project's jobs may reach", which was the only thing a
-- workspace could be when every row was this server's. It is now the path
-- component of a place, and on a row with a machine it is a path on THAT
-- machine.
--
-- THE RULE THIS COMMENT EXISTS FOR: a workspace may be resolved as a path on
-- this server's filesystem only when `machine IS NULL`. `Presence` already
-- makes the argument for the string it carries -- "a path resolved against this
-- server's filesystem is a claim about a different set of files that happen to
-- share a name" -- and this is the same string at rest. The failure it prevents
-- is not hypothetical and it is silent: a laptop rooting `/Users/example/proj/
-- ledger` on a server that happens to have a directory of that name would hand
-- an agent the server's copy, under the leash of a project whose files are
-- somewhere else entirely. `ProjectStore.find` carries `machine IS NULL` for
-- this reason and is the only door `LocalProvider` reads a workspace through.
COMMENT ON COLUMN projects.workspace IS
    'Where this project''s files are on the machine named by the machine column '
    '-- the directory its jobs may reach when that column is NULL, and a path on '
    'somebody else''s disk when it is not. NULL for a project that has never '
    'been given a place. MUST NOT be resolved against this server''s filesystem '
    'unless machine IS NULL: on a row with a machine it is a string only that '
    'machine can interpret.';
