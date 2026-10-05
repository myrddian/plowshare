import type { Replies } from 'plowshare-client-ts/operations/replies';
import { array, line, number, quote, record, tier } from './values.js';
export function started(
  value: Replies['agent.run'],
  project: string | null,
): string {
  const job = record(value);
  return `Started ${line(job['id'])}, running '${line(job['agent'])}' over the ${tier(project)} archive.\n\nIt is running now and this call did not wait for it. Ask agent_poll whether it has finished, then agent_result for how it ended. Jobs live in the server's memory: a restart loses the handle, though anything the run wrote to the archive or the queue survives.`;
}
export function job(
  value: Replies['job.status'],
  mode: 'poll' | 'result' | 'cancel',
): string {
  const row = record(value),
    heading = `${line(row['id'])} ('${line(row['agent'])}')`,
    outcome = row['outcome'];
  if (mode === 'poll')
    return outcome != null
      ? heading + ' has finished. Read how it ended with agent_result.'
      : heading +
          ' is still running.' +
          (row['cancelRequested']
            ? ' It has been asked to stop and will do so at its next turn boundary.'
            : '') +
          ' Nothing has been concluded yet; poll again in a moment.';
  if (mode === 'cancel')
    return outcome != null
      ? heading +
          ' had already finished, so there was nothing to stop. Read how it ended with agent_result.'
      : heading +
          ' has been asked to stop, and will do so at its next turn boundary rather than immediately — a turn already in flight is paid for either way. It still reads as running until it gets there. Whatever it already wrote to the archive or the queue stands.';
  if (outcome == null)
    return (
      heading +
      ' has not finished. There is no result to read yet — poll it with agent_poll.'
    );
  const ended = record(outcome),
    steps = number(ended['steps']),
    calls = number(ended['modelCalls']);
  if (typeof ended['answered'] !== 'boolean')
    throw new Error('unreadable answered flag; this is not a completed answer');
  let out =
    heading +
    (ended['answered'] ? ' answered.' : ' stopped without answering.') +
    ` It took ${steps}${steps === 1 ? ' step and ' : ' steps and '}${calls}${calls === 1 ? ' model call.' : ' model calls.'}\n\nHow it ended: ${line(ended['ending'])}\n`;
  if (!ended['answered'])
    out +=
      'This is NOT an answer. The run stopped, and what follows is an account of what it managed to do — not a conclusion it reached.\n';
  if (line(ended['detail'])) out += 'Detail: ' + line(ended['detail']) + '\n';
  return (
    out +
    '\n' +
    quote(
      line(ended['text']) === '' ? '(the run produced no text)' : ended['text'],
    )
  );
}
export function curated(
  value: Replies['agent.curate'],
  project: string,
): string {
  const row = record(value);
  return `Started ${line(row['id'])}, a curator pass over the project '${line(project)}'.\n\nIt lists that project's memories, drops the ones already ruled on and the ones global holds word for word, and puts each survivor to a judge. Confident rulings are applied; the rest are filed for a person to answer with memory_proposals and memory_resolve. Poll it with agent_poll and read it with agent_result.`;
}
export function proposals(
  value: Replies['proposal.list'],
  project: string | null,
): string {
  const rows = array(value).map((value) => record(value));
  if (!rows.length)
    return `(nothing waiting) — nothing in the ${tier(project)} archive is waiting on a decision.`;
  let out = `${rows.length}${rows.length === 1 ? ' proposal waiting' : ' proposals waiting'} on the ${tier(project)} archive. Each one asks whether a memory should be copied into the global archive, which every project reads.\n`;
  for (const row of rows)
    out += `\n${line(row['id'])}  ${line(row['action'])}  ${line(row['memoryId'])}\n    why: ${line(row['reason'])}\n    asked: ${row['createdAt']} by ${row['proposedBy'] == null ? 'somebody this queue did not record' : line(row['proposedBy'])}\n`;
  return (
    out +
    '\nRead the memory itself with memory_read before deciding — the reason above is one sentence, written by whoever the line above names. Settle one with memory_resolve.'
  );
}
export function resolved(
  value: Replies['proposal.resolve'],
  accept: boolean,
): string {
  const row = record(value),
    proposal = record(row['proposal']),
    id = line(proposal['id']),
    memory = line(proposal['memoryId']);
  if (!accept)
    return `${id} is rejected. ${memory} stays where it is, and it will not be proposed again: a rejection is remembered, which is what stops a later pass asking the same question.`;
  let out = `${id} is accepted. ${memory} was promoted as ${line(row['promotedId'])}, which every project now reads; the project's own record is retired and points at the new one.`;
  const demoted = array(row['demoted']);
  if (demoted.length)
    out += `\n\nThe global index was full, so these fell out of it: ${demoted.map(line).join(', ')}. They are cold, not deleted: still readable by id and still found by recall — falling out of the index is not deletion.`;
  return out;
}
