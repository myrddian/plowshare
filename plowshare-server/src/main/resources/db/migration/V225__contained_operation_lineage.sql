-- A containment hop may record its lineage.
--
-- REPRODUCED FROM V162's operation_parent_valid WITH ONE EDIT AND NO OTHERS. The function's name,
-- signature, language, return type and every other condition in it are byte-identical to V162; the
-- single edit replaces the conjunct
--
--     AND c.scope=p.scope AND c.project_name=p.project_name
--
-- with that same equality OR a contained arm, and nothing else in the body is touched. A reviewer
-- should diff this against V162 lines 28-53 and find exactly that one difference.
--
-- WHY THIS IS A REPLACEMENT AND NOT A WIDENING. ADR 0042's "two live-authority gates" already made
-- this exact replacement in Java: for a contained admission, `require`'s demand for an ancestor
-- with an identical scope AND home was replaced by the containment edge itself -- "the child's own
-- stored row must name this ancestor's scope as its container". operation_parent_valid is the SQL
-- counterpart of the same demand, and it never got the same treatment for one reason: no hop was
-- ever persisted to fail it. Every test of the hop mocked OperationRecords, so `contain` asserted
-- retainChild and never executed it, and two shipped slices described a live contained admission
-- that no database had ever accepted. This migration applies the documented replacement at the SQL
-- level. It admits nothing the Java gate does not already admit, and anything broader than that is
-- outside the decision this implements.
--
-- THIS CONJUNCT IS THE SUBSTRATE'S NO-ESCALATION INVARIANT. Do not relax it further. A later reader
-- looking at the contained arm and wondering why it is so verbose should read it as four facts
-- bound at once, the same four containedHome binds: the row exists, it is CONTAINED, its container
-- is the parent admission's own scope, and it maps to the project the child admission retained.
-- Dropping any one of them opens a different hole:
--   * without s.id=c.scope             -- any contained row would vouch for any child
--   * without s.kind='CONTAINED'       -- an ordinary PROJECT scope would travel this arm
--   * without s.container=p.scope      -- a grandparent, a sibling subtree or an unrelated scope
--                                         would be accepted as a parent, which is escalation
--   * without pr.name=c.project_name   -- a child could retain another child's project as its home
-- The prefix test is deliberately NOT one of those four and is not a substitute for any of them: it
-- is a cheap sanity condition on a reserved namespace, and s.kind plus s.container are what do the
-- work. The non-contained path keeps the original equality untouched, so an ordinary derived
-- admission is decided by exactly the predicate that decided it before this migration.
--
-- Everything else the trigger compares is still compared, and that is what keeps a hop a hop rather
-- than an escalation: c.executor=p.executor, c.executor_handle=p.executor_handle and
-- c.source_account=p.source_account are unchanged, so a hop cannot change who is executing or whose
-- grants are being spent -- which is also why ADR 0025's immutable attribution survives this. The
-- support-subset check is unchanged, so a child still cannot hold an action or a grant its parent
-- does not. The recursion bound and the cycle check are unchanged. The configured containment depth
-- is not enforced here and never was: ContainmentBounds is server configuration and PinnedOperation
-- Authority.requireDepth enforces it, which is why a depth refusal is a CallerFault and not a
-- trigger error.
--
-- Gate six is NOT addressed here. operation_conversation_valid (V162:62) requires a conversation
-- row's agent to equal the bound envelope's, while conversations_a_person_s_conversation_names_no_
-- agent (V17:212) forbids an agent on an origin='turn' row, so a contained run's envelope still
-- cannot be bound to the Origin.TURN row ConversationStore.openContained writes. That is a choice
-- about which Origin a contained run carries -- a shipped enum with contract reach -- and it is
-- reported for decision rather than settled by a migration.

CREATE OR REPLACE FUNCTION operation_parent_valid() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- Serialize overlapping ancestry inserts without changing an existing admission.
 PERFORM id FROM operation_admissions WHERE id IN (NEW.child,NEW.parent) ORDER BY id FOR UPDATE;
 IF NOT EXISTS (
   SELECT 1 FROM operation_admissions c JOIN operation_admissions p ON p.id=NEW.parent
   WHERE c.id=NEW.child AND c.sealed AND p.sealed AND c.job_id IS NULL
     AND c.executor=p.executor AND c.executor_handle=p.executor_handle
     AND c.source_account=p.source_account
     -- THE ONE EDIT. Either the original equality, or the containment edge itself.
     AND ((c.scope=p.scope AND c.project_name=p.project_name)
       OR EXISTS (SELECT 1 FROM authority_scopes s JOIN projects pr ON pr.id=s.project_id
         WHERE s.id=c.scope AND s.kind='CONTAINED' AND s.container=p.scope
           AND pr.name=c.project_name AND pr.name LIKE 'contained:%'))
     AND NOT EXISTS (SELECT 1 FROM operation_conversations bound WHERE bound.operation=c.id)
     AND NOT EXISTS (
       SELECT 1 FROM operation_admission_support cs WHERE cs.operation=c.id AND
         (cs.action NOT IN ('READ','WRITE','CONVERSATION_CREATE','RUN_MODEL') OR NOT EXISTS (
           SELECT 1 FROM operation_admission_support ps WHERE ps.operation=p.id
             AND ps.action=cs.action AND ps.grant_id=cs.grant_id)))) THEN
   RAISE EXCEPTION 'child authority exceeds retained parent';
 END IF;
 IF EXISTS (WITH RECURSIVE chain(id,depth) AS (
   SELECT NEW.parent,0 UNION ALL
   SELECT edge.parent,chain.depth+1 FROM operation_admission_parents edge JOIN chain ON edge.child=chain.id
   WHERE chain.depth<64
 ) SELECT 1 FROM chain WHERE id=NEW.child OR depth=64) THEN
   RAISE EXCEPTION 'invalid operation ancestry';
 END IF;
 RETURN NEW;
END $$;
