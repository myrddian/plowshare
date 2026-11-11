import type { ProcConversation } from 'plowshare-client-ts/operations/proc';

export type ActivitySelection = { id: string };

export type ActivityListRow = {
  id: string;
  title: string;
  subtitle: string;
  origin: string;
  live: boolean;
  state: string;
  at: string;
  owner: string | null;
  agent: string | null;
  nestChat: boolean;
  current: boolean;
  /** Work of the shipped document-ingestion pipeline (see below). */
  document: boolean;
};

/** Newest first keeps live work on top; oldest first is plain chronology. */
export type ActivityOrder = 'newest' | 'oldest';

/**
 * Agents of the shipped document-ingestion pipeline: submission, the
 * summariser tree and tagging. The server marks no category for this work,
 * so the hub classifies by these names to let people hide the (often
 * voluminous) background processing. Application-defined agents that reuse
 * a name here are classified the same way.
 */
const documentAgents = new Set([
  'document_pipeline',
  'document_summariser',
  'chapter_summariser',
  'section_summariser',
  'paragraph_summariser',
  'span_summariser',
  'information_tagger',
  'information_tag_grouper',
]);

export function isDocumentProcessing(row: Pick<ProcConversation, 'agent'>) {
  return !!row.agent && documentAgents.has(row.agent);
}

const liveStates = new Set(['RUNNING', 'PROCESSING']);

export function activityVisibility(
  serverAdmin: boolean | undefined,
): 'mine' | 'server' {
  return serverAdmin === true ? 'server' : 'mine';
}

export function isLiveProcState(state: string) {
  return liveStates.has(state);
}

const stamp = (row: ProcConversation) => row.lastTurnAt ?? row.createdAt;

/**
 * Application Activity list from proc inventory. Newest order puts live work
 * first, then newest logs; oldest order is ascending by time. `hideDocuments`
 * drops document-ingestion work. Person titles overlay when
 * conversation.list supplied them.
 */
export function applicationActivityRows(
  items: readonly ProcConversation[],
  options: {
    titles?: Record<string, string>;
    selected?: string;
    handle?: string;
    order?: ActivityOrder;
    hideDocuments?: boolean;
  } = {},
): ActivityListRow[] {
  const oldest = options.order === 'oldest';
  const ranked = items
    .filter((row) => !options.hideDocuments || !isDocumentProcessing(row))
    .sort((a, b) => {
      if (oldest)
        return stamp(a).localeCompare(stamp(b)) || a.id.localeCompare(b.id);
      const live =
        Number(isLiveProcState(b.state)) - Number(isLiveProcState(a.state));
      if (live) return live;
      return stamp(b).localeCompare(stamp(a)) || a.id.localeCompare(b.id);
    });
  return ranked.map((row) => {
    const title =
      options.titles?.[row.id]?.trim() ||
      row.agent ||
      (row.origin === 'turn' ? 'Conversation' : row.origin) ||
      row.id;
    const bits = [
      row.origin,
      row.state,
      row.owner && row.owner !== options.handle ? row.owner : undefined,
      row.evidence || undefined,
    ].filter(Boolean);
    return {
      id: row.id,
      title,
      subtitle: bits.join(' · '),
      origin: row.origin,
      live: isLiveProcState(row.state),
      state: row.state,
      at: stamp(row),
      owner: row.owner,
      agent: row.agent,
      nestChat:
        row.origin === 'turn' &&
        (!!options.titles?.[row.id] || row.owner === options.handle),
      current: options.selected === row.id,
      document: isDocumentProcessing(row),
    };
  });
}
