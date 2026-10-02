-- Who asked. `resolved_by` has always said who ANSWERED a proposal; nothing on
-- the row said who raised it, so a person working the queue could not tell a
-- curator's question from anyone else's and the reason text was the whole of
-- what they had to go on. Left open by 3a's Task 9 and again by its Task 10.
--
-- V2__proposals.sql STILL CARRIES AN "OPEN" NOTE SAYING THIS COLUMN DOES NOT
-- EXIST, and it is left standing on purpose. Flyway checksums a migration over
-- its whole text, comments included, and `application.yml` sets only
-- `spring.flyway.enabled` and `locations`, so Boot's `validate-on-migrate`
-- default of true stands: editing an applied file fails every database that has
-- already run it, at boot, with a checksum mismatch. The tests cannot see that,
-- because each replays V1 onward into a fresh container.
--
-- This file was written once with that note "corrected" in place and the edit is
-- reverted; the restored V2 is byte-identical to the applied version. A
-- migration is the record of what was applied, not a place to keep commentary,
-- so the correction lives HERE and in `Proposal.proposedBy` -- and in the
-- database itself, through the COMMENT ON below, which is what `\d+ proposals`
-- shows a reader who never opens either file.
ALTER TABLE proposals ADD COLUMN proposed_by TEXT;

-- No backfill, and that is a decision rather than laziness. 3a recorded this as
-- needing none "because every existing row was the curator's" -- which is a
-- claim about production data a migration cannot check: `ProposalStore.propose`
-- is public, and nothing recorded who called it. Writing 'curator' onto rows
-- nobody observed would put an unverifiable name where the truth is that no
-- name was kept, and it cannot be undone afterwards. NULL and 'curator' are
-- different facts -- "not recorded" against "the curator asked" -- and the
-- backfill would destroy the distinction on exactly the rows that predate the
-- column. New rows are required to name a proposer; see ProposalStore.propose.
COMMENT ON COLUMN proposals.proposed_by IS
    'Who raised this question. NULL only on rows filed before this column '
    'existed, where nobody recorded it.';

-- The same shape as proposals_resolver_named, which V2 declares on the column
-- this one sits beside: absent is a state, the empty string is not. Without it an omitted field arrives as '' and reads
-- to a human as a proposer whose name is nothing.
ALTER TABLE proposals ADD CONSTRAINT proposals_proposer_named
    CHECK (proposed_by IS NULL OR proposed_by <> '');
