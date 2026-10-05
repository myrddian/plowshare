import { parseObject, record, json } from './json.test-support.ts';
import { describe, it, expect } from 'vitest';
import {
  projectSettings,
  withProjectCap,
  withProjectLocalMode,
} from './project-settings.js';

describe('version 1 project settings', () => {
  it('reads all settings and preserves unrelated fields during edits', () => {
    const source = JSON.stringify({
      version: 1,
      name: 'house',
      caps: { budget: 10, autoIncrease: true },
      commands: {
        local: {
          mode: 'ask',
          env: { PATH: 'C:\\tools', LABEL: 'quoted "value"' },
        },
      },
      skills: { research: { agentVisible: true } },
      defaultBot: 'interlocutor',
      routing: { sendTo: ['alerts'] },
      homeAssistant: { entities: ['light.office'] },
    });
    const settings = parseObject(source);
    expect(projectSettings(settings).commands?.local?.env).toEqual(
      record(record(settings.commands).local).env,
    );
    const changed = json(
      withProjectLocalMode(withProjectCap(source, 'auto-increase', 0), 'open'),
    );
    expect(changed).toEqual({
      ...settings,
      caps: { budget: 10, autoIncrease: false },
      commands: {
        local: { ...record(record(settings.commands).local), mode: 'open' },
      },
    });
  });
  it('refuses malformed settings rather than coercing approvals', () => {
    for (const record of [
      { caps: { autoIncrease: 'true' } },
      { caps: { budget: 0 } },
      { caps: { autoIncrease: 1 } },
      { caps: { unexpected: 2 } },
      { commands: { local: { shells: 1 } } },
      { commands: { local: { mode: 'open\nserver:' } } },
      { commands: { remote: { mode: 'open' } } },
      { skills: { research: { agentVisible: 'true' } } },
      { defaultBot: '../other' },
    ])
      expect(() => projectSettings(record)).toThrow();
  });
});
