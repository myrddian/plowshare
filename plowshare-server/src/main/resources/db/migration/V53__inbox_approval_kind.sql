-- An approval question delivered to the inbox is a notice of kind 'approval'.
--
-- 8f90cb10 began delivering unattended approval questions to the account's inbox under that kind
-- (ApprovalDelivery.INBOX_KIND) and added no migration, so every one failed at the insert against
-- this check -- caught by ApprovalDelivery, logged, and left undelivered for a retry that could
-- never succeed. Measured 2026-09-26, on the first approval raised under an orchestration.
--
-- Recreated from its latest definition, V48's, which added 'orchestration' to V46's; rewriting it
-- from V46 would silently drop that.
ALTER TABLE user_inbox DROP CONSTRAINT user_inbox_kind_is_known;
ALTER TABLE user_inbox ADD CONSTRAINT user_inbox_kind_is_known
    CHECK (kind IN ('run', 'sync.conflict', 'orchestration', 'approval'));
