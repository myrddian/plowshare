// The Hermes template uses XML-like tool-call framing with a JSON payload.
export function reject(text) {
  return /^(?:<\|im_start\|>assistant\b|<tool_call(?:\s|>|$))/.test(text.trimStart());
}
