import { isObject, isOneOf } from 'plowshare-client-ts/binding/values';
import { CAP_KEYS } from 'plowshare-client-ts/binding/environment';
import type { OperatorInput } from './operator-shared.ts';

/** Form/IPC values are checked before they enter control selection or business logic. */
export function decodeOperatorInput(value: unknown): OperatorInput {
  if (!isObject(value)) throw new Error('Control input must be an object.');
  const result: OperatorInput = {};
  for (const [key, entry] of Object.entries(value)) {
    switch (key) {
      case 'id':
      case 'agent':
      case 'body':
      case 'summary':
      case 'scope':
      case 'prefix':
        if (
          typeof entry !== 'string' ||
          entry.includes('\0') ||
          entry.length > (key === 'body' ? 100000 : 8192)
        )
          throw new Error(`Invalid control ${key}.`);
        result[key] = entry;
        break;
      case 'lifecycle':
        if (!isOneOf(entry, ['active', 'archived']))
          throw new Error('Choose a conversation lifecycle.');
        result.lifecycle = entry;
        break;
      case 'decision':
        if (!isOneOf(entry, ['once', 'conversation', 'project', 'deny']))
          throw new Error('Choose an approval decision.');
        result.decision = entry;
        break;
      case 'makeDefault':
        if (!isOneOf(entry, ['true', 'false']))
          throw new Error('Choose whether this instance is the default.');
        result.makeDefault = entry;
        break;
      case 'key':
        if (!isOneOf(entry, CAP_KEYS)) throw new Error('Choose a cap key.');
        result.key = entry;
        break;
      case 'maxTurns':
      case 'maxModelCalls':
      case 'value':
        if (
          typeof entry !== 'number' ||
          !Number.isSafeInteger(entry) ||
          entry < 0 ||
          entry > 1000000
        )
          throw new Error(`Invalid control ${key}.`);
        result[key] = entry;
        break;
      default:
        throw new Error('Unknown control input field.');
    }
  }
  return result;
}
