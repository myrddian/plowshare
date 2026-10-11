export const manifest = {
  version: 1,
  subscriptions: [{name: 'completed', topic: 'privacy.scan.completed', kind: 'text', start: 'oldest-retained'}]
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function strings(value, maximum) {
  return Array.isArray(value) && value.length <= maximum
    && value.every(item => typeof item === 'string' && item.length <= 4096);
}
function completion(value) {
  return value && !Array.isArray(value) && value.version === 1
    && typeof value.scan_id === 'string' && UUID.test(value.scan_id)
    && typeof value.revision === 'string' && UUID.test(value.revision)
    && typeof value.collector === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/.test(value.collector)
    && ['tcp', 'fixture'].includes(value.mode)
    && strings(value.changes, 512) && strings(value.issues, 32)
    && (value.baseline === undefined || typeof value.baseline === 'boolean');
}
function timestamp(value) {
  if (typeof value !== 'string' || value.length > 64) return false;
  // Routing has no clock/Date access. Check RFC3339 structure and calendar values
  // deterministically; policy timestamps are evidence, not a scheduling authority.
  const match = /^(\d{4})-(\d{2})-(\d{2})T([01]\d|2[0-3]):([0-5]\d):([0-5]\d)(?:\.\d+)?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/.exec(value);
  if (!match) return false;
  const year = Number(match[1]), month = Number(match[2]), day = Number(match[3]);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return year > 0 && month >= 1 && month <= 12 && day >= 1 && day <= days[month - 1];
}
function decision(value) {
  return value && !Array.isArray(value) && typeof value.requested === 'boolean'
    && typeof value.reason === 'string' && value.reason.trim().length > 0 && value.reason.length <= 256
    && timestamp(value.decided_at);
}

export function route(event) {
  if (event.topic !== 'privacy.scan.completed' || typeof event.payload.text !== 'string') return [];
  let result;
  try { result = JSON.parse(event.payload.text); }
  catch (_) {
    // A known malformed input admits no effects. Relay retains the original input
    // and the empty decision, and advances the subscription past this record.
    return [];
  }
  if (!completion(result)) return [];
  if (result.investigation !== undefined) {
    if (!decision(result.investigation) || !result.investigation.requested) return [];
    // An explicit, retained policy decision wins even when there are no changes.
  } else if (result.baseline !== true && result.changes.length === 0) return [];
  return [{name: 'investigate', receiver: 'orchestration.start', work: {agent: 'privacy_coordinator', definition: 'investigate_network'}}];
}
