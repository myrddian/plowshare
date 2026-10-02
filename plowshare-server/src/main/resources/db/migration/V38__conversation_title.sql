-- A conversation has a name, and the server is what decides it.
--
-- WHAT WAS WRONG. `api.ConversationView` is an id and four numbers, so every
-- surface that lists conversations lists `cnv_3134E666E2D847AD`. That was
-- invisible while there was one client; a terminal client now stands beside the
-- web console, and two clients showing the same unreadable string is what made
-- the missing fact legible as a missing fact rather than as a client being lazy.
--
-- WHY THE COLUMN AND NOT A DERIVATION IN EACH CLIENT. Three clients deriving a
-- name from the first turn would invent three different names for one row --
-- three widths, three opinions about markdown, three answers to "what if the
-- first line is a pasted URL" -- and a person moving between a browser tab and a
-- terminal would be unable to tell that two listings were showing them the same
-- conversation. A title is a fact ABOUT the conversation, in the same sense
-- `origin` is, so it belongs where the conversation is. V17 makes the identical
-- argument for `turns.agent` and `conversations.origin`: a fact that several
-- readers need is stored once rather than recomputed per reader.
--
-- NULLABLE, PERMANENTLY, AND THERE IS NO BACKFILL. Every row this table already
-- holds keeps a NULL title for ever. The utterance that would name them IS
-- recoverable -- it is `turns.utterance` at ordinal 1, unlike V32's system
-- blocks, which are gone -- so this is a decision and not a limitation, and it
-- is worth writing down as one. Writing history from a migration means this file
-- deciding a derivation for rows nobody was looking at when it ran, and freezing
-- the output of that derivation beside rows whose titles were written by a
-- later, different version of it. Naming NEW conversations and rewriting OLD
-- ones are two decisions; this migration takes the first and leaves the second
-- to a task that can argue it on its own terms. Every reader handles the NULL,
-- and a client that meets one shows the id, which is exactly what it shows
-- today.
--
-- NOT NULL WAS CONSIDERED AND IS NOT AVAILABLE, and not only because of the
-- rows above. A conversation is opened before anybody speaks into it --
-- `ConversationStore.open` writes the row and the first turn arrives later, if
-- it arrives at all -- so a conversation with no name yet is an ordinary state
-- and not a degenerate one. It is the same state `last_turn_at` carries one
-- column over, for the reason V6 gives there: "no turn yet" is a fact, and a
-- default invented to make a NOT NULL available would destroy it on exactly the
-- rows where it is interesting.
ALTER TABLE conversations ADD COLUMN title TEXT;

-- A title nothing can read is not a name. The empty string is what an omitted
-- field arrives as and what a derivation that ran off the end of a blank
-- utterance would produce, and a row holding one is a conversation whose name is
-- nothing -- which renders as a blank row a person cannot tell from a broken
-- one. The absence is NULL and it is one value over, so this CHECK is what keeps
-- the two from collapsing into each other.
--
-- The same sentence `conversations_project_named` carries two columns up, and
-- `projects_name_named` and `memories_project_named` before it.
ALTER TABLE conversations ADD CONSTRAINT conversations_a_title_is_named
    CHECK (title IS NULL OR title <> '');

-- NO INDEX, AND NO LENGTH LIMIT IN THE COLUMN, both for the reason V7 gives
-- about a column with no reader.
--
-- Nothing searches or orders by title: `conversations_home` -- V6 -- is the one
-- read that filters, it is `(project, created_at)`, and the title travels as a
-- field of rows that query already selected. An index here would be a question
-- nobody asks paid for on every insert and on every first turn.
--
-- And the width is `ConversationStore.TITLE_WIDTH`, in Java, where the
-- derivation that has to respect it lives. A `VARCHAR(n)` here would be a second
-- statement of the same number, in a place that cannot be changed once it has
-- shipped -- so the day somebody widens the derivation, the database would
-- refuse rows the derivation considers correct, and the repair would be another
-- migration. One writer, one rule.

COMMENT ON COLUMN conversations.title IS
    'A human name for this conversation, derived by ConversationStore.titleFrom '
    'from the first turn''s utterance and written by the first turn only -- the '
    'write is UPDATE ... WHERE id = ? AND title IS NULL, so a second turn '
    'changes nothing and the conversation keeps the name it opened with. NULL '
    'means unnamed, which is true of every row written before V38, of a '
    'conversation nobody has spoken into yet, and of one whose first utterance '
    'held no line to name it with. NULL is never filled in later: it is a fact '
    'a client renders (by showing the id), not a gap to paper over, and a '
    'server that invented "Untitled" would be three clients showing three '
    'inventions again.';
