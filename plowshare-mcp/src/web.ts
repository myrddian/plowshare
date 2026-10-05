import type { Replies } from 'plowshare-client-ts/operations/replies';
import { array, line, number, quote, record, text } from './values.js';
export function searched(value: Replies['web.search']): string {
  const row = record(value);
  if (row['refusal'] != null) return 'SEARCH FAILED — ' + line(row['refusal']);
  const hits = array(row['hits']).map((value) => record(value)),
    total = number(row['total']),
    page = number(row['page']);
  if (!hits.length)
    return !total
      ? 'no results for this search.'
      : `nothing on page ${page} of this search — it holds ${total}${total === 1 ? ' result' : ' results'} in all.`;
  let out = `${hits.length} of ${total}${total === 1 ? ' result' : ' results'}, page ${page}${row['hasMore'] ? ' — more after this' : ' — no more after this'}:\n`;
  for (const hit of hits)
    out += `\n${line(hit['title'])}\n${line(hit['url'])}\n${line(hit['snippet'])}\n`;
  return out;
}
export function fetched(value: Replies['web.fetch']): string {
  const row = record(value);
  if (row['refusal'] != null) return text(row['refusal']);
  return `${line(row['title'])} — ${text(row['url'])}\ncharacters ${number(row['offset'])} to ${number(row['nextOffset'])} of ${number(row['total'])}${row['hasMore'] ? '; call fetch again with offset ' + row['nextOffset'] + ' to keep reading' : '; this is the end of the page'}\n\n${quote(row['text'])}`;
}
