-- A project may be lent more than one directory.
--
-- WHY THIS EXISTS. `809338d` made a dot-prefixed path component unreachable
-- unless a root names it, and its stated cost was that `.git` and `.github`
-- become unreachable *but are recoverable by adding them as explicit roots*.
-- Review found the recovery is not expressible on two of the three ways a
-- workspace arrives: `ProjectStore.define` takes one workspace and this table
-- has one `workspace TEXT` column, so `LocalProvider` builds its leash from a
-- singleton list; and `ClientPresence.root` feeds one element to a
-- plural-capable `Workspace`. Only `plowshare --workspace a,b` could say it,
-- and it already does. `FileAccess` has been plural since it was written. It is
-- everything upstream of it that collapses the list.
--
-- THE COLUMN IS NEW BECAUSE `workspace` MAY NOT BE PLURALISED, AND THAT IS THE
-- WHOLE DECISION. `projects.workspace` does two jobs today: it is the leash
-- `FileAccess` is built from, *and* it is the `<PATH>` component of
-- `Presence.canonicalName()` -> `<MACHINE>/<PATH>/<PROJ_NAME>` that V14 and V15
-- both argue at length. Pluralising it pluralises the identity, and there is no
-- honest way to compose a canonical name from an array: an array is ordered but
-- its order is whatever the last writer happened to pass, so any join rule makes
-- reordering the roots rename the project -- and V14 exists precisely so that a
-- rename is a thing the schema can survive rather than a thing it invites.
--
-- So the two jobs are split rather than merged:
--
--   * `workspace` STAYS EXACTLY AS IT IS -- scalar, nullable, the project's
--     place, the `<PATH>` in the canonical name, the column
--     `projects_machine_has_a_place` keys on and the column `IS_THIS_SERVERS`
--     tests. Identity does not move, and no query in `ProjectStore` that tests
--     `workspace` changes meaning because of this migration.
--   * `lent` CARRIES THE ADDITIONAL DIRECTORIES LENT AT THAT PLACE. The leash
--     becomes `workspace` prepended to `lent`; the identity reads `workspace`
--     alone and never sees this column.
--
-- Enzo confirmed on 2026-09-07 that one machine lending several directories does
-- not overturn his 2026-09-03 "a project exists in exactly one location". That
-- decision refused one project *mirrored across two presences or two machines* --
-- the case where two locations hold the same content and calling them one
-- project asserts something nothing can check. It stays intact: a project still
-- has exactly one `workspace`, on exactly one `machine`.
--
-- A LENT ROOT MAY SIT OUTSIDE THE WORKSPACE, and no constraint here says
-- otherwise. Inside-only would make this precisely the per-project unhide list
-- that was declined, in different words, and a directory outside the workspace
-- is exactly what a person means by lending a second one -- it is what
-- `--workspace a,b` has always meant. The bound is the mandatory exclusions,
-- which are applied in Java by `ProjectStore.effectiveExclusions` and which
-- `FileAccess.of` uses to drop any root they cover, lent roots included. A
-- containment rule written here would be a second, weaker fence in a place that
-- cannot see the first.


-- TEXT[] NOT NULL DEFAULT '{}', copying `exclusions` exactly. Not a child table:
-- the two things a child table buys -- per-element rows to reference, and a
-- uniqueness constraint -- have no reader here. Nothing references a root, and
-- a root listed twice is a `FileAccess` root listed twice, which `permits`
-- resolves identically and `roots()` renders as a duplicate line a person can
-- see and remove. A join for a list that is read whole, every time, on the one
-- path `LocalProvider.leash` takes, would be a second query on the hottest read
-- in the file seam.
--
-- NOT NULL and not nullable, unlike `workspace`. NULL means something for
-- `workspace` -- "this project has no place at all", V15's third state -- and
-- means nothing here: a project that lends no extra directory has an empty list,
-- which is what `'{}'` is. Nullable would make "lends nothing" and "lends
-- nothing" two values, and the `ROW_MAPPER` would need a branch no fixture could
-- reach.
ALTER TABLE projects ADD COLUMN lent TEXT[] NOT NULL DEFAULT '{}';

-- V3's argument for `projects_exclusions_named`, one column across and unchanged
-- in every particular: measured on JDK 21, `Path.of("").toAbsolutePath()
-- .normalize()` is the process's working directory, so a blank element here is
-- not a root covering nothing -- it is a root covering the server's whole
-- checkout, handed to an agent as a lent directory. `ProjectStore` refuses it
-- before absolutising; this stops anything that bypasses the store.
--
-- Blank only, not NULL, for V3's reason spelled the same way: a NULL element
-- makes `'' = ANY(...)` return NULL and the CHECK pass. The store's own guard is
-- what refuses those, and saying so here is cheaper than a second predicate that
-- would need its own test.
ALTER TABLE projects ADD CONSTRAINT projects_lent_named
    CHECK (NOT ('' = ANY (lent)));

COMMENT ON COLUMN projects.lent IS
    'Additional directories this project''s jobs may reach, beyond the '
    'workspace. The leash is workspace prepended to this list; the identity is '
    'the workspace alone and never reads this column, which is why workspace '
    'stayed scalar. A lent root may sit outside the workspace -- the only bound '
    'is the mandatory exclusions, applied in Java, which FileAccess.of drops a '
    'covered root against whether it came from here or from workspace.';
