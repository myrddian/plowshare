-- Indexes for statements that run on a timer or a client poll and were reading whole growing
-- tables. Each index names the statement it serves; partial predicates match the statements' own
-- predicates so the planner can prove them.

-- Orchestration stall sweep (every minute): the newest recorded_at of each conversation in every
-- running run's delegation tree (OrchestrationStore QUIET_SINCE, activity, markStalled). Without
-- it each sweep fetched every entry of every live tree.
CREATE INDEX entries_recorded ON entries(conversation_id, recorded_at);

-- Acquisition claim (every second): state='queued' OR (state='running' AND lease_until<?) ORDER BY
-- created_at,id LIMIT 1. Both arms imply this predicate; succeeded/failed rows are kept forever and
-- are never claimable.
CREATE INDEX information_acquisitions_claimable ON information_acquisitions(created_at, id)
    WHERE state IN ('queued', 'running');

-- The "acquisition still outstanding for this revision" probe in the step claim, the tagging and
-- grouping sweeps and the PROC queue audit: revision_id=r.id AND state<>'succeeded'.
CREATE INDEX information_acquisitions_unsettled ON information_acquisitions(revision_id)
    WHERE state <> 'succeeded';

-- Retrieval repair poll (every two seconds): ready sources embedded under another generation are
-- found by IS NULL / < / > ranges on this index, all empty once every source is current
-- (JdbcPassageRepository.pending). Unready sources keep using retrieval_pending.
CREATE INDEX retrieval_ready_generation ON retrieval_sources(generation) WHERE status = 'ready';

-- PROC conversations (5 s client poll): each page row's latest job, and the running-only probe for
-- an unfinished job. V127 added the column without an index.
CREATE INDEX jobs_by_conversation ON jobs(conversation_id, started_at DESC, id DESC)
    WHERE conversation_id IS NOT NULL;

-- PROC conversations: each page row's information revisions, and the running-only probe for a
-- revision whose steps are still pending, both by the conversation logging its processing.
CREATE INDEX information_revisions_processing_log ON information_revisions(processing_log)
    WHERE processing_log IS NOT NULL;

-- Every child of a run, ended or not, newest first (OrchestrationStore.children): status views,
-- the consent tree walk and the ending cascade. orchestrations_live_children covers live children
-- only, so these read the whole table once per call.
CREATE INDEX orchestrations_by_parent ON orchestrations(parent, created_at DESC, id DESC)
    WHERE parent IS NOT NULL;
