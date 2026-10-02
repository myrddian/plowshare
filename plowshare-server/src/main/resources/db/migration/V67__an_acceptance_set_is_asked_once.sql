-- An acceptance set is asked about once — or not at all when the command judge finds it clearly
-- safe.
--
-- Measured 2026-09-29, orc_318DFD3782228160: when `spec` was marked done, the acceptance gate
-- asked the person fifteen separate approvals, one per acceptance command, seconds after they had
-- answered `accept` to the verifier's question listing those same commands. "Why do I get approval
-- bombed?" So every command of a set that needs consent shares ONE approval, and the command judge
-- (agents/command_judge.md) is shown the set first: clearly safe, it is allowed without asking.
--
--   commands  the set one approval asks about, each command's argv, in the section's order; null
--             for an approval of one command, which keeps it in `argv`. A set's own `argv` is the
--             empty list, which no command matches, so the run tool's lookup (RunApprovalStore
--             .consume) never spends it and no project prefix covers it.
--   judged    the command judge's one line about what it was shown, or null when it was not asked
--             or gave none: why it allowed the command or set (answered_by 'command judge'), or why
--             it put it to the person (then shown in the question).
--
-- No CHECK is rewritten: V50 is the only migration that defines run_approvals' constraints, and
-- none of them is touched here.
ALTER TABLE run_approvals ADD COLUMN commands JSONB;
ALTER TABLE run_approvals ADD CONSTRAINT run_approvals_commands_is_a_set
    CHECK (commands IS NULL
        OR (jsonb_typeof(commands) = 'array' AND jsonb_array_length(commands) > 0
            AND argv = '[]'::jsonb));
ALTER TABLE run_approvals ADD COLUMN judged TEXT;
