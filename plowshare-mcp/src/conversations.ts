import { displayText } from 'plowshare-client-ts/binding/values';
import type { Replies } from 'plowshare-client-ts/operations/replies';
import { array, line, number, quote, record, text, tier } from './values.js';
const home = (project: string | null): string =>
  project === null ? 'the global tier' : tier(project);
export function past(
  offset: number,
  total: number,
  tool: string,
  things: string,
): string {
  return `There is nothing at offset ${offset}: this holds ${total} ${things}, the last is at offset ${total - 1}, and the list is not empty. Call ${tool} again with offset=0 for the beginning.`;
}
function more(
  last: number,
  total: number,
  tool: string,
  qualifier = '',
): string {
  return last >= total - 1
    ? ''
    : `\n\nThere is more of this list: call ${tool} again with ${qualifier}offset=${last + 1}.`;
}
export function conversations(
  value: Replies['conversation.list'],
  project: string | null,
  offset: number,
): string {
  const rows = array(value).map((value) => record(value));
  if (!rows.length)
    return `Nothing is open in ${home(project)}. (nothing open)\n\nA conversation is opened by whoever is going to speak in it; this surface reads them and does not open one.`;
  if (offset >= rows.length)
    return past(offset, rows.length, 'conversation_list', 'conversations');
  const last = Math.min(offset + 20, rows.length) - 1;
  let out = `Conversations ${offset} to ${last} of ${rows.length} in ${home(project)}, counting from 0 as offset does, oldest first.\n`;
  for (const row of rows.slice(offset, last + 1))
    out += `\n${line(row['id'])} — allowance ${row['maxModelCalls'] == null ? 'unlimited' : number(row['maxModelCalls'])} model calls`;
  return (
    out +
    '\n\nRead one with conversation_chat for what the model is shown, or conversation_trajectory for everything that happened in it.' +
    more(last, rows.length, 'conversation_list')
  );
}
function entry(value: unknown): string {
  const row = record(value);
  let out = `[${number(row['ordinal'])}] ${line(row['kind'])} — turn ${number(row['turnOrdinal'])}`;
  for (const [field, prefix, suffix] of [
    ['recordedAt', ' — ', ''],
    ['tookMillis', ' — took ', ' ms'],
    ['supersededBy', ' — folded away by the summary at ', ''],
    ['handle', ' — handle ', ''],
  ] as const)
    if (row[field] != null) out += prefix + displayText(row[field]) + suffix;
  if (row['ejectedAt'] != null)
    out += ' — content ejected on ' + displayText(row['ejectedAt']);
  else if (text(row['excerpt']).trim()) {
    const excerpt = text(row['excerpt']),
      shown = Math.min(excerpt.length, 500);
    out += '\n' + quote(excerpt.slice(0, 500));
    if (shown < number(row['length']))
      out += `\n  (showing ${shown} of ${displayText(row['length'])} characters; the rest is not reachable from this surface)`;
  }
  for (const value of array(row['toolCalls'])) {
    const called = record(value),
      args = text(called['arguments']),
      shown = Math.min(args.length, 200);
    out += `\n  calls ${line(called['name'])} (${line(called['id'])}) with ${line(args.slice(0, 200))}`;
    if (shown < number(called['length']))
      out += ` (${shown} of ${displayText(called['length'])} characters)`;
  }
  return out;
}
export function page(
  value: Replies['conversation.chat'],
  offset: number,
  tool: string,
  conversation: string,
): string {
  const row = record(value),
    total = number(row['total']),
    rows = array(row['entries']);
  const what =
    tool === 'conversation_chat'
      ? 'what the model is shown in this conversation: what a fold covered is gone, and so is everything whose kind never reaches a model'
      : "everything this conversation recorded, in the order it happened — including what a fold covered, the harness's own diagnostics, and the model calls that failed";
  if (total === 0)
    return `Conversation ${line(conversation)} holds nothing on this reading: ${what}. Nothing has been said in it yet, or a fold has covered everything there was.`;
  if (!rows.length) return past(offset, total, tool, 'entries');
  const last = offset + rows.length - 1;
  return (
    `Entries ${offset} to ${last} of ${total} in conversation ${line(conversation)}, counting from 0 as offset does. This reading is ${what}.\n` +
    rows.map((value) => '\n' + entry(value)).join('') +
    more(last, total, tool, 'the same conversation and ')
  );
}
function reach(value: unknown, nothing: boolean): string {
  const row = record(value),
    searched = number(row['searched']),
    ejected = number(row['ejected']),
    only = number(row['recordedOnly']);
  let out = `\n\nSearched ${searched}${searched === 1 ? ' entry' : ' entries'}${nothing ? '.' : ' to find these.'}`;
  if (searched === 0 && ejected === 0 && only === 0)
    return (
      out +
      ' Nothing has been said in this tier at all, so this is not a question that found nothing — there was nothing to ask it of.'
    );
  if (ejected > 0)
    out += ` ${ejected}${ejected === 1 ? ' tool result had its payload ejected by a retention sweep, so its words are gone and it could not be searched' : ' tool results had their payloads ejected by a retention sweep, so their words are gone and they could not be searched'}; the entries are still in the trajectory, marked as ejected.`;
  if (only > 0)
    out += ` ${only} more ${only === 1 ? 'entry is' : 'entries are'} of a kind no model is ever shown — a diagnostic, a failed attempt, a runtime note or a plan — and this searches what was said; read those with conversation_trajectory.`;
  return out;
}
export function searched(
  value: Replies['conversation.search'],
  question: string,
  project: string | null,
  offset: number,
): string {
  const found = record(value),
    total = number(found['total']),
    hits = array(found['hits']).map((value) => record(value));
  if (total === 0)
    return (
      `Nothing in ${home(project)} matches ${line(question)}.` +
      reach(found['reach'], true)
    );
  if (!hits.length)
    return past(offset, total, 'conversation_search', 'matching entries');
  const last = offset + hits.length - 1;
  let out = `Entries ${offset} to ${last} of ${total} in ${home(project)} matching ${line(question)}, counting from 0 as offset does, closest first. Read a conversation a hit is in with conversation_trajectory.\n`;
  for (const hit of hits) {
    out += `\n[${line(hit['conversationId'])} entry ${number(hit['ordinal'])}] ${line(hit['kind'])} — turn ${number(hit['turnOrdinal'])}`;
    if (hit['recordedAt'] != null) out += ' — ' + hit['recordedAt'];
    if (hit['supersededBy'] != null)
      out += ' — folded away by the summary at ' + hit['supersededBy'];
    if (hit['handle'] != null) out += ' — handle ' + line(hit['handle']);
    out +=
      '\n' +
      quote(text(hit['snippet']).slice(0, 500)) +
      `\n  (the words around the match; this entry is ${number(hit['length'])} characters in all)`;
  }
  return (
    out +
    reach(found['reach'], false) +
    more(last, total, 'conversation_search', 'the same question and ')
  );
}
export function context(
  value: Replies['conversation.context'],
  conversation: string,
  agent: string | null,
): string {
  const row = record(value);
  let out = `Conversation ${line(conversation)}: ${number(row['turns'])} turns, ${number(row['turnsMeasured'])} of them measured.\n\n`;
  out +=
    row['sent'] == null
      ? 'No turn of this conversation has reached a model call, so nothing has been measured. That is not a cost of zero.'
      : `The whole prompt of turn ${number(row['sentAtTurn'])} cost ${number(row['sent'])} tokens, counted by the model's own tokenizer. That is the entire request — the system prompt, the tool schemas and everything said — and it is the one token figure that exists.`;
  out += '\n\nWhat cannot be given, and why:';
  for (const value of array(row['unavailable'])) {
    const why = record(value);
    out += `\n\n${line(why['component'])}:\n${quote(why['reason'])}`;
  }
  if (row['prefix'] == null)
    return (
      out +
      "\n\nNo agent was named, so the fixed block is not priced. A conversation does not record which agent answered a turn — the agent is chosen per turn — so this call cannot work it out; pass 'agent' to see what one agent's system prompt and tool schemas cost, in characters."
    );
  const prefix = record(row['prefix']),
    tools = array(prefix['tools']).map((value) => record(value));
  out += `\n\nThe fixed block of '${line(prefix['agent'])}' on model '${line(prefix['model'])}', in characters of the JSON actually sent — characters and not tokens, and they must not be scaled into tokens:\n\nsystem prompt — ${number(prefix['systemPromptCharacters'])} characters\ntool schemas — ${number(prefix['toolCharacters'])} characters across ${tools.length} tools`;
  for (const tool of tools)
    out += `\n  ${line(tool['name'])} — ${number(tool['characters'])} characters`;
  return (
    out +
    `\n\nThis is what '${line(agent)}' would be sent before anything was said. It is a fact about the agent and not about this conversation, which records no agent of its own.`
  );
}
