export const manifest = {
  version: 1,
  subscriptions: [{name: 'completed', topic: 'privacy.scan.completed', kind: 'text', start: 'oldest-retained'}]
};

export function route(event) {
  if (event.topic !== 'privacy.scan.completed' || typeof event.payload.text !== 'string') return [];
  const result = JSON.parse(event.payload.text);
  if (!result || result.version !== 1 || typeof result.scan_id !== 'string' || typeof result.revision !== 'string' || typeof result.collector !== 'string' || !['tcp', 'fixture'].includes(result.mode) || !Array.isArray(result.changes) || !Array.isArray(result.issues)) throw new Error('Invalid privacy completion record');
  if (result.investigation !== undefined) {
    if (!result.investigation || typeof result.investigation.requested !== "boolean" || typeof result.investigation.reason !== "string" || typeof result.investigation.decided_at !== "string") throw new Error("Invalid investigation decision");
    if (!result.investigation.requested) return [];
  }
  if (result.changes.length === 0) return [];
  return [{name: 'investigate', receiver: 'orchestration.start', work: {agent: 'privacy_coordinator', definition: 'investigate_network'}}];
}
