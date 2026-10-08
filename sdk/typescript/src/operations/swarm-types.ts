import { object, record, named, nullable, list, text } from './wire-checks.ts';
import { isList } from '../binding/values.ts';

/** Retained participant identity; grants and model availability remain server decisions. */
export interface SwarmSelection {
  readonly name: string;
  readonly revision: string;
  readonly description: string;
  readonly members: readonly string[];
  readonly budget: number;
}
export interface SwarmType {
  readonly name: string;
  readonly members: readonly string[];
  readonly budget: number;
  readonly refused: Readonly<Record<string, string>>;
  readonly origin: string;
  readonly selection: SwarmSelection | null;
}
export interface SwarmTypes {
  readonly project: string;
  readonly types: readonly SwarmType[];
}
export const swarmName = (value: unknown): value is string =>
  typeof value === 'string' &&
  value.length <= 64 &&
  /^[a-z][a-z0-9]*(?:[-_][a-z0-9]+)*$/.test(value);
export const swarmSelectionCheck = record({
  name: swarmName,
  revision: (value) =>
    typeof value === 'string' && /^[a-f0-9]{64}$/.test(value),
  description: (value) =>
    typeof value === 'string' && value.length <= 4096 && !value.includes('\0'),
  members: (value) =>
    isList(value) &&
    value.length > 0 &&
    value.length <= 64 &&
    new Set(value).size === value.length &&
    value.every(
      (member) =>
        typeof member === 'string' &&
        member.trim() !== '' &&
        member.length <= 128 &&
        !member.includes('\0'),
    ),
  budget: (value) =>
    typeof value === 'number' &&
    Number.isSafeInteger(value) &&
    value >= 2 &&
    value <= 2147483647,
});
export const swarmTypeCheck = record(
  {
    name: swarmName,
    members: list(named),
    budget: (value) =>
      typeof value === 'number' &&
      Number.isSafeInteger(value) &&
      value >= 2 &&
      value <= 2147483647,
    refused: (value) => object(value) && Object.values(value).every(text),
    origin: named,
    selection: nullable(swarmSelectionCheck),
  },
  (row) => {
    const selection = row['selection'];
    return selection === null
      ? isList(row['members']) && row['members'].length === 0
      : object(selection) &&
          selection['name'] === row['name'] &&
          selection['budget'] === row['budget'] &&
          JSON.stringify(selection['members']) ===
            JSON.stringify(row['members']);
  },
);
export const swarmTypesCheck = record(
  { project: named, types: list(swarmTypeCheck) },
  (row) => {
    const types = row['types'];
    return (
      isList(types) &&
      types.length <= 64 &&
      new Set(types.map((type) => (object(type) ? type['name'] : undefined)))
        .size === types.length
    );
  },
);

/** Generated field graphs are supplemented by bounds and relationships at the SDK boundary. */
export function validateSwarmReply(type: string, payload: unknown): void {
  if (type === 'swarm.types') {
    if (!swarmTypesCheck(payload))
      throw new Error('Invalid named swarm catalog');
    return;
  }
  const topic = (value: unknown) => {
    if (
      !object(value) ||
      (value['swarm'] !== null && !swarmSelectionCheck(value['swarm']))
    )
      throw new Error('Invalid retained swarm selection');
  };
  if (type === 'board.topup') topic(payload);
  if (type === 'board.open' && object(payload)) {
    topic(payload['topic']);
    if (object(payload['topic']) && payload['topic']['swarm'] === null)
      throw new Error('A new topic needs a retained swarm selection');
  }
  if (type === 'board.messages' && object(payload)) {
    topic(payload['topic']);
    topic(payload['root']);
    if (
      object(payload['topic']) &&
      object(payload['root']) &&
      !sameSelection(payload['topic']['swarm'], payload['root']['swarm'])
    )
      throw new Error('Topic and root name different swarm selections');
  }
  if (
    (type === 'board.topics' || type === 'swarm.status') &&
    object(payload) &&
    isList(payload['topics'])
  )
    for (const row of payload['topics']) if (object(row)) topic(row['topic']);
}

function sameSelection(first: unknown, second: unknown): boolean {
  if (first === null || second === null) return first === second;
  return (
    object(first) &&
    object(second) &&
    ['name', 'revision', 'description', 'budget'].every(
      (key) => first[key] === second[key],
    ) &&
    JSON.stringify(first['members']) === JSON.stringify(second['members'])
  );
}
