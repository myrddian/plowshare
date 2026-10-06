import { isObject } from 'plowshare-client-ts/binding/values';
export interface Preference {
  selected: string;
  scope: string;
  drafts: Record<string, string>;
  projectExpansion?: Record<string, boolean>;
  chosenAgents?: Record<string, string>;
  personalBotExpansion?: Record<string, boolean>;
}
export type Preferences = Record<string, Preference>;
function dictionary<T>(
  value: unknown,
  check: (item: unknown) => item is T,
): Record<string, T> {
  if (!isObject(value) || Object.keys(value).length > 10000)
    throw new Error('Unreadable saved preferences.');
  const entries: [string, T][] = [];
  for (const [key, item] of Object.entries(value)) {
    if (
      !check(item) ||
      key === '__proto__' ||
      key === 'constructor' ||
      key === 'prototype'
    )
      throw new Error('Unreadable saved preference field.');
    entries.push([key, item]);
  }
  return Object.fromEntries(entries);
}
const text = (value: unknown): value is string =>
  typeof value === 'string' && value.length <= 1000000;
const flag = (value: unknown): value is boolean => typeof value === 'boolean';
/** Local storage is an external boundary too; unknown fields do not become UI state. */
export function readPreferences(value: unknown): Preferences {
  if (!isObject(value)) throw new Error('Unreadable saved preferences.');
  const entries: [string, Preference][] = [];
  for (const [key, item] of Object.entries(value)) {
    if (
      !isObject(item) ||
      Object.keys(item).some(
        (field) =>
          ![
            'selected',
            'scope',
            'drafts',
            'projectExpansion',
            'chosenAgents',
            'personalBotExpansion',
          ].includes(field),
      ) ||
      !text(item.selected) ||
      !text(item.scope)
    )
      throw new Error('Unreadable saved selection.');
    entries.push([
      key,
      {
        selected: item.selected,
        scope: item.scope,
        drafts: dictionary(item.drafts, text),
        ...(item.projectExpansion === undefined
          ? {}
          : { projectExpansion: dictionary(item.projectExpansion, flag) }),
        ...(item.chosenAgents === undefined
          ? {}
          : { chosenAgents: dictionary(item.chosenAgents, text) }),
        ...(item.personalBotExpansion === undefined
          ? {}
          : {
              personalBotExpansion: dictionary(item.personalBotExpansion, flag),
            }),
      },
    ]);
  }
  return Object.fromEntries(entries);
}
