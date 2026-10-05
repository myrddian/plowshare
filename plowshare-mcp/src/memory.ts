import type { Replies } from 'plowshare-client-ts/operations/replies';
import {
  array,
  line,
  number,
  quote,
  record,
  separator,
  tier,
} from './values.js';
export function index(
  value: Replies['memory.index'],
  project: string | null,
): string {
  const rows = array(value).map((value) => record(value));
  if (!rows.length)
    return `(no memories) — the ${tier(project)} archive holds nothing yet.`;
  let out = `${rows.length} memories in the ${tier(project)} archive:\n`,
    skipped = 0;
  for (const row of rows) {
    if (row['unsearchable']) skipped++;
    out += `\n${line(row['id'])}  ${line(row['summary'])}${row['unsearchable'] ? '  [not searchable]' : ''}\n    when: ${line(row['scope'])}\n`;
  }
  if (skipped)
    out += `\n${skipped} of these ${skipped === 1 ? 'is' : 'are'} [not searchable]: written while the embedding endpoint was down, so ${skipped === 1 ? 'it has' : 'they have'} no vector and memory_recall cannot find ${skipped === 1 ? 'it' : 'them'} whatever the question. Nothing is lost — read ${skipped === 1 ? 'it' : 'them'} by id from this list. Ask an operator to re-embed the archive to make ${skipped === 1 ? 'it' : 'them'} findable again.\n`;
  return out;
}
export function memory(value: Replies['memory.read']): string {
  const row = record(value),
    home = record(row['home']),
    formed = record(row['formed']);
  let out = `${line(row['id'])}  [${line(row['state'])}]  ${home['project'] == null ? 'everywhere' : 'project ' + line(home['project'])}\n${line(row['summary'])}\nwhen: ${line(row['scope'])}\nformed: ${formed['at']} by ${line(formed['by'])}`;
  if (line(formed['where'])) out += ' — ' + line(formed['where']);
  out += '\n';
  if (row['supersedes'] != null)
    out += `replaced: ${line(row['supersedes'])}\n`;
  if (row['supersededBy'] != null)
    out += `superseded by: ${line(row['supersededBy'])} — prefer that one\n`;
  if (row['invalidation'] != null) {
    const invalid = record(row['invalidation']);
    out += `no longer true, as of ${invalid['at']} (${line(invalid['by'])}): ${line(invalid['reason'])}\n`;
  }
  return out + '\n' + quote(row['body']);
}
export function recall(
  value: Replies['memory.recall'],
  question: string,
  project: string | null,
): string {
  const found = record(value),
    memories = array(found['memories']),
    skipped = number(found['unsearchable']);
  const who = skipped === 1 ? 'it' : 'them';
  const reach =
    skipped <= 0
      ? ''
      : `\n\nIncomplete answer: ${skipped} memor${skipped === 1 ? 'y' : 'ies'} in the ${tier(project)} archive ${skipped === 1 ? 'has' : 'have'} no embedding — written while the embedding endpoint was down — so recall could not search ${who} at all, whatever the question. Nothing is lost: memory_index lists ${who} marked [not searchable] and memory_read returns ${who} in full. Look there before concluding the archive does not hold the answer.`;
  return memories.length
    ? `${memories.length} memories for: ${line(question)}${reach}\n\n${memories.map(memory).join(separator)}`
    : `(no memories) — nothing in the ${tier(project)} archive is close to that question. Recall matches on meaning, so a differently framed question can still find something.${reach}`;
}
export function write(
  value: Replies['memory.write'],
  project: string | null,
): string {
  const result = record(value),
    id = line(result['memoryId']),
    target = line(result['targetId']),
    where = ` in the ${tier(project)} archive`;
  let out: string;
  switch (result['kind']) {
    case 'new':
      out = `Wrote ${id}${where}.`;
      break;
    case 'merged_into':
      out = `Added this to ${id}${where}, which it refines. That memory is what recall returns; no new memory was made.`;
      break;
    case 'supersedes':
      out = `Wrote ${id}${where}, replacing ${target}. ${target} is retired: it is still readable by id, and recall will not return it again.`;
      break;
    default:
      throw new Error(
        'unrecognised memory write outcome; do not replay the write',
      );
  }
  out += '\n\n' + line(result['reason']);
  const demoted = array(result['demoted']);
  if (demoted.length)
    out += `\n\nThe ${tier(project)} index was full, so these fell out of it: ${demoted.map(line).join(', ')}. They are still readable by id and still found by recall — falling out of the index is not deletion.`;
  return out;
}
