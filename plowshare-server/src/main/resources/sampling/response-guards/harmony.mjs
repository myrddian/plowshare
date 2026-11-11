// Reject a leading undecoded assistant frame. Markdown that quotes protocol tokens is valid.
export function reject(text) {
  return /^(?:<\|start\|>assistant\b|<\|channel\|>(?:analysis|commentary|final)\b)/.test(text.trimStart());
}
