-- A containment hop may record its lineage.
--
-- REPRODUCED FROM V162's operation_parent_valid WITH ONE SEMANTIC EDIT AND NO OTHERS. The
-- function's name, signature, language and return type are unchanged, and so is every condition in
-- the body. The single semantic edit replaces the conjunct
--
--     AND c.scope=p.scope AND c.project_name=p.project_name
--
-- with that same equality OR a contained arm, and nothing else in the body changes meaning.
--
-- TWO NON-SEMANTIC DIFFERENCES, stated because this repository's reviewers check the convention
-- mechanically and a claim of "byte-identical" would be false. First, `AND c.source_account=
-- p.source_account` now sits on its own line ahead of the scope conjunct instead of sharing a line
-- with the executor comparisons; second, the replaced conjunct is wrapped across four lines. Both
-- are re-ordering and re-wrapping of terms under a single AND, so the predicate is unchanged by
-- either. A reviewer normalising whitespace and term order should find exactly one difference.
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
-- The second constraint in front of a contained run is settled in SECTION 2 of this same file,
-- not deferred: operation_conversation_valid (V162:62) requires a conversation row's agent to
-- equal the bound envelope's, while conversations_a_person_s_conversation_names_no_agent (V17:212)
-- forbids an agent on an origin='turn' row, so a contained run's conversation needed an origin of
-- its own. Section 2 adds it. The two sections are separate decisions and the second says so; they
-- travel together because this migration is unshipped until its branch merges and one coherent
-- migration beats two that must be read in order.

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

-- ---------------------------------------------------------------------------
-- 2. the origin a contained run's conversation carries
-- ---------------------------------------------------------------------------
--
-- A SEPARATE DECISION FROM THE EDIT ABOVE, carried in the same migration because this one is
-- unshipped until its branch merges and one coherent migration beats two that must be read
-- together. Section 1 is the lineage trigger and has nothing to do with this; nothing below
-- touches operation_parent_valid.
--
-- WHY A NEW KIND RATHER THAN AN EXISTING ONE. A contained run's authority envelope always names an
-- agent, and `conversations_a_person_s_conversation_names_no_agent` (V17:212) requires
-- `(origin='turn') = (agent IS NULL)`, so a contained conversation cannot be a turn. Of the kinds
-- that may carry a budget at all, none describes harness-started work inside a confined child:
--
--   * `submission` is V17's caller's-own parentless run -- a script, a CI job, curl -- so a
--     contained run would be indistinguishable from an external submission in every listing;
--   * `memory` is system-owned digest construction;
--   * `event` is a trigger answering a tick, and the trigger machinery keys off it;
--   * `orchestration` is a conductor with its own lifecycle special-cases, a cost with no benefit;
--   * `delegation` fails on DESIGN and not on taste, which is what settled this: a delegation
--     shares its parent's allowance by reference and its row therefore holds no budget, so a
--     contained conversation carried on it would spend its CONTAINER's pool. ADR 0042 requires a
--     child to allocate its own allowance under a ceiling its container sets, never to share one.
--     Reusing the nearest existing kind would reintroduce exactly the defect a separate kind
--     exists to avoid.
--
-- The wire contract is unaffected: the SDKs type a conversation's `origin` as a plain string, not a
-- union of literals, so a new value changes no generated type. Both constraints below have been
-- restated by later migrations already (V74 for the known set, V169 for the allowance), so
-- restating them is this table's normal maintenance and not a novel operation.

ALTER TABLE conversations DROP CONSTRAINT conversations_origin_is_known;
ALTER TABLE conversations ADD CONSTRAINT conversations_origin_is_known
    CHECK (origin IN ('turn', 'delegation', 'curator', 'submission', 'memory', 'event',
                      'orchestration', 'board', 'contained'));

-- THE OWNED SIDE, AND THAT IS THE WHOLE POINT. `contained` joins the list whose rows MUST carry a
-- budget, so a contained conversation cannot be budget-less and therefore cannot share the pool of
-- anything above it. Putting it on the shared side -- or leaving it off this list, which has the
-- same effect -- would hand a child its container's allowance by reference and reintroduce the
-- `delegation` defect described above under a different name. ADR 0042's "allocated, never shared"
-- is this line. `ContainedConversationDoorDatabaseTest` asserts a budget-less contained row is
-- refused, because the one way this decision goes wrong is a permissive sharing arm.
ALTER TABLE conversations DROP CONSTRAINT conversations_an_allowance_is_owned_or_shared;
ALTER TABLE conversations ADD CONSTRAINT conversations_an_allowance_is_owned_or_shared CHECK (
  CASE WHEN origin='orchestration' THEN true
       ELSE (origin IN ('turn','submission','memory','event','contained'))
            = (budget_total IS NOT NULL OR budget_lifted) END);

-- The agent pairing needs no new constraint and deliberately gets none: V17:212 is an equality, so
-- it already fences both directions -- a turn row may not name an agent, and a row of any other
-- origin, `contained` included, MUST name one. Asserted in both directions by
-- ContainedConversationDoorDatabaseTest rather than restated here, since a second constraint
-- saying the same thing is a second thing to keep in agreement.
