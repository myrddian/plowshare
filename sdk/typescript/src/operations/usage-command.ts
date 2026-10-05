import type { UsageClient } from './usage.ts';
import {
  USAGE_REPORTS,
  decodeUsageCall,
  type UsageFilter,
  type UsageReportType,
} from './usage.ts';
import { referencePrice } from './reference-cost.ts';
import { usageRange, usageText } from './usage-presentation.ts';
export const USAGE_HELP =
  'usage [models|conversation|project|agent|run|orchestration|pools|calls] [target] [--days 1..365] [--direct] [--reference PRESET] [--json]. Presets: openai-gpt-4.1-mini, openai-gpt-4.1, anthropic-sonnet-5.5, anthropic-opus-5.5, google-gemini-2.5-flash. usage count CONVERSATION AGENT counts context separately.';
export interface UsageCommand {
  type: UsageReportType | 'usage.calls' | 'conversation.context.count';
  filter:
    | UsageFilter
    | {
        conversation: string;
        agent: string;
      };
  reference: string;
  json: boolean;
}
export function usageCommand(
  words: readonly string[],
  defaults: {
    conversation?: string;
    project?: string;
    agent?: string;
  } = {},
): UsageCommand {
  const args = [...words];
  if (args.shift() !== 'usage') throw new Error(USAGE_HELP);
  const kind =
    args[0] && !args[0].startsWith('--')
      ? args.shift()!
      : defaults.conversation
        ? 'conversation'
        : 'models';
  if (kind === 'count') {
    const conversation = args.shift() ?? defaults.conversation,
      agent = args.shift() ?? defaults.agent;
    if (!conversation || !agent || args.length) throw new Error(USAGE_HELP);
    return {
      type: 'conversation.context.count',
      filter: { conversation, agent },
      reference: 'openai-gpt-4.1-mini',
      json: true,
    };
  }
  const type = `usage.${kind}` as UsageReportType | 'usage.calls';
  if (
    !(USAGE_REPORTS as readonly string[]).includes(type) &&
    type !== 'usage.calls'
  )
    throw new Error(USAGE_HELP);
  const target =
    args[0] && !args[0].startsWith('--')
      ? args.shift()
      : defaults[kind as keyof typeof defaults];
  let days = 30,
    json = false,
    scope: 'direct' | 'subtree' = 'subtree',
    reference = 'openai-gpt-4.1-mini';
  if (['models', 'pools', 'calls'].includes(kind) && target)
    throw new Error(USAGE_HELP);
  while (args.length) {
    const flag = args.shift();
    if (flag === '--json') {
      json = true;
      continue;
    }
    if (flag === '--direct') {
      scope = 'direct';
      continue;
    }
    const value = args.shift();
    if (!value) throw new Error(USAGE_HELP);
    if (flag === '--days') {
      days = Number(value);
      if (!Number.isInteger(days) || days < 1 || days > 365)
        throw new Error('Usage days must be 1–365.');
    } else if (flag === '--reference') {
      referencePrice(value);
      reference = value;
    } else throw new Error(USAGE_HELP);
  }
  if (
    ['conversation', 'project', 'agent', 'run', 'orchestration'].includes(
      kind,
    ) &&
    !target
  )
    throw new Error(`Usage ${kind} needs a target.`);
  return {
    type,
    filter: {
      ...usageRange(days),
      scope,
      group_by: ['model'],
      ...(['conversation', 'project', 'agent', 'run', 'orchestration'].includes(
        kind,
      )
        ? { [kind]: target }
        : {}),
    },
    reference,
    json,
  };
}
export async function runUsage(
  client: UsageClient,
  command: UsageCommand,
): Promise<string> {
  const result = await client.invoke(
    decodeUsageCall(command.type, command.filter),
  );
  return command.json ||
    command.type === 'usage.calls' ||
    command.type === 'conversation.context.count'
    ? JSON.stringify(result, null, 2)
    : usageText(
        result as Awaited<ReturnType<UsageClient['report']>>,
        referencePrice(command.reference),
      );
}
