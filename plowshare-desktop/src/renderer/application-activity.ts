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
  /** Same gate as Processes: trajectory needs account custody, not proc visibility. */
  canOpenTrajectory: boolean;
  current: boolean;
};

const liveStates = new Set(['RUNNING', 'PROCESSING']);

export function activityVisibility(
  serverAdmin: boolean | undefined,
): 'mine' | 'server' {
  return serverAdmin === true ? 'server' : 'mine';
}

export function isLiveProcState(state: string) {
  return liveStates.has(state);
}

const stamp = (row: ProcConversation) =>
  row.lastTurnAt ?? row.createdAt;

/**
 * Application Activity list from proc inventory: live work first, then newest
 * logs. Person titles overlay when conversation.list supplied them.
 */
export function applicationActivityRows(
  items: readonly ProcConversation[],
  options: {
    titles?: Record<string, string>;
    selected?: string;
    handle?: string;
  } = {},
): ActivityListRow[] {
  const ranked = [...items].sort((a, b) => {
    const live =
      Number(isLiveProcState(b.state)) - Number(isLiveProcState(a.state));
    if (live) return live;
    return (
      stamp(b).localeCompare(stamp(a)) || a.id.localeCompare(b.id)
    );
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
      canOpenTrajectory: row.owner === options.handle,
      current: options.selected === row.id,
    };
  });
}
