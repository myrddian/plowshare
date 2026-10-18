import type { Entry } from 'plowshare-client-ts/operations/client-views';

/** One provider response's usage, including output that requests tools. Never infer missing counts. */
export function inferenceTokens(entry: Entry): string {
  const count = (value: number | undefined) =>
    value === undefined ? 'not recorded' : value.toLocaleString();
  return `Input tokens: ${count(entry.promptTokens)} · Output tokens: ${count(entry.completionTokens)}`;
}
