-- A project is the container: it owns memories, jobs run in it, and this table
-- is where it gains a filesystem. The workspace is the leash — the only place a
-- job's file tools may reach — so a row here is a grant, and the two paths no
-- grant may cover are applied in Java rather than seeded here.
CREATE TABLE projects (
    name        TEXT PRIMARY KEY,
    workspace   TEXT NOT NULL,
    exclusions  TEXT[] NOT NULL DEFAULT '{}',
    defined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Blank is not a project, for the reason V1 gives about memories.project:
    -- `Home.of` refuses a blank name, so a row holding one would be a workspace
    -- belonging to a tier nobody can name and no job can be routed to. The
    -- store refuses it first; this stops anything that bypasses the store.
    CONSTRAINT projects_name_named CHECK (name <> ''),

    -- Likewise a blank workspace. `ProjectStore.define` validates that the path
    -- is an existing directory before it writes, and the empty string is
    -- neither — but the empty string is also what an omitted field arrives as,
    -- and a row holding one would resolve to the process's working directory
    -- the moment anything absolutised it. That is the whole server's checkout,
    -- handed out as a workspace.
    CONSTRAINT projects_workspace_named CHECK (workspace <> ''),

    -- And no blank *element* of the exclusions array, which is the one place
    -- this table did not carry what V1 and V2 carry for every other non-blank
    -- TEXT column. The argument is the workspace's, one level in: measured on
    -- JDK 21, Path.of("").toAbsolutePath().normalize() is the process's working
    -- directory, so a blank element is an exclusion covering the server's whole
    -- checkout rather than nothing. ProjectStore refuses it before absolutising;
    -- this stops anything that bypasses the store.
    --
    -- Blank only, not NULL: a NULL element makes `'' = ANY(...)` return NULL and
    -- the CHECK pass. The store's own guard is what refuses those, and saying so
    -- here is cheaper than a second predicate that would need its own test.
    CONSTRAINT projects_exclusions_named CHECK (NOT ('' = ANY (exclusions)))
);

-- No foreign key to memories.project. A project may hold memories with no
-- workspace (every project did, before this slice) and may hold a workspace
-- before its first memory. Tying them would make defining a workspace depend
-- on having already written something, which is backwards.
COMMENT ON COLUMN projects.exclusions IS
    'Extra paths this project may not reach. The two mandatory exclusions are '
    'applied in Java and are not stored here, so a row cannot override them.';
