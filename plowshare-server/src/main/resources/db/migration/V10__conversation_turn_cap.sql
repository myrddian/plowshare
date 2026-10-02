-- What ceiling a conversation puts on each of its turns, if it decides one.
--
-- V6 gave a conversation the middle level of one configuration -- the model-call
-- budget every turn in it spends from -- and the turn cap had no middle level at
-- all: it lived in an agent's frontmatter and was fixed at boot. The three
-- levels are now agent definition, then conversation, then run, narrowest
-- winning, which is what `POST /v1/conversations` already did for model calls
-- and now does for turns.
--
-- TWO COLUMNS FOR ONE KNOB, because there are three states and a single column
-- can only hold two of them honestly. A conversation may say nothing about the
-- cap, may name a number, or may say there is to be no cap at all -- and the
-- third is a decision somebody takes, not a large number they type. That is the
-- rule `agents.TurnCap` exists to hold in Java ("no cap is a state and not a
-- large number standing in for infinity"), and a schema that encoded it as 0, or
-- as -1, or as 2147483647 would be the place the rule stopped being true. The
-- three states are:
--
--   turn_cap_lifted = FALSE, turn_cap IS NULL -- this conversation does not
--                                                decide; the agent's own
--                                                max-turns stands
--   turn_cap_lifted = FALSE, turn_cap = n     -- n turns, for every turn in it
--   turn_cap_lifted = TRUE,  turn_cap IS NULL -- no cap at all
--
-- and the fourth combination is refused below rather than left to be read two
-- ways by two readers.
--
-- NOT NULL WITH A DEFAULT ON THE BOOLEAN, so that every conversation opened
-- before this migration reads back as one that does not decide -- which is what
-- they are. A nullable boolean would add a fourth state to a column that exists
-- to remove one.
--
-- THE RUN LEVEL IS NOT HERE and does not need a column. A run-level override is
-- one run's, arrives in the body of `POST /v1/agents/{name}/runs`, and is spent
-- when that run ends; the only thing that outlives a run is what the
-- conversation decided, which is this.
ALTER TABLE conversations ADD COLUMN turn_cap INT;

ALTER TABLE conversations
    ADD COLUMN turn_cap_lifted BOOLEAN NOT NULL DEFAULT FALSE;

-- A cap is a cap. Zero turns is not a smaller ceiling, it is a conversation
-- whose every turn can only end at its cap having done nothing --
-- `TurnCap.of`'s refusal, and `AgentRegistry`'s for `max-turns`, arriving in the
-- one place neither of them can reach.
ALTER TABLE conversations ADD CONSTRAINT conversations_turn_cap_is_a_cap
    CHECK (turn_cap IS NULL OR turn_cap >= 1);

-- A number and "no cap" are alternatives and never both. A row holding both
-- would be a conversation that had answered one question twice, and whichever
-- of the two a reader preferred would be a decision taken by the reader.
ALTER TABLE conversations ADD CONSTRAINT conversations_turn_cap_is_a_number_or_no_cap
    CHECK (NOT (turn_cap_lifted AND turn_cap IS NOT NULL));

COMMENT ON COLUMN conversations.turn_cap IS
    'How many turns each turn in this conversation may take, or NULL when this '
    'conversation does not decide -- in which case the agent''s own max-turns '
    'stands. NULL with turn_cap_lifted TRUE means there is to be no cap.';

COMMENT ON COLUMN conversations.turn_cap_lifted IS
    'Whether this conversation says its turns are to run with no turn cap at '
    'all. FALSE and turn_cap NULL is the ordinary shape: this conversation does '
    'not decide. Never TRUE beside a number.';
