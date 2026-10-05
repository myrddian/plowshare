import { afterEach, describe, expect, it } from 'vitest';
import {
  mkdtemp,
  mkdir,
  writeFile,
  rm,
  symlink,
  realpath,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { allows, enforcing } from './enforcer.ts';

describe('opaque skill packages through the existing filesystem channel', () => {
  const created: string[] = [];
  afterEach(async () => {
    await Promise.all(
      created
        .splice(0)
        .map((path) => rm(path, { recursive: true, force: true })),
    );
  });
  it('permits harness package/rule reads while keeping model and write requests out', () => {
    const root = '/work';
    for (const relative of [
      '.plowshare/skills/review/SKILL.md',
      '.plowshare/skills/review/references/guide.md',
      '.plowshare/skills/review/scripts/check.py',
      '.plowshare/AGENTS.md',
      '.plowshare/AGENT.md',
      '.plowshare/agents/reviewer/AGENTS.md',
      '.plowshare/skills.yml',
    ]) {
      const path = join(root, relative);
      expect(allows(root, path, 'definitions')).toBe(true);
      expect(allows(root, path, 'reading')).toBe(false);
      expect(allows(root, path, 'writing')).toBe(false);
    }
    for (const relative of [
      '.plowshare/skills/review/.secret',
      '.plowshare/credentials',
      '.git/config',
      '.plowshare/agents/reviewer/private.txt',
      'other/.plowshare/skills/review/SKILL.md',
    ]) {
      expect(allows(root, join(root, relative), 'definitions')).toBe(false);
    }
  });
  it('answers a server glob and opaque reads without granting access through symlinks', async () => {
    const root = await realpath(
      await mkdtemp(join(tmpdir(), 'plowshare-skill-')),
    );
    created.push(root);
    const outside = await mkdtemp(join(tmpdir(), 'plowshare-outside-'));
    created.push(outside);
    await mkdir(join(root, '.plowshare/skills/review/references'), {
      recursive: true,
    });
    await writeFile(
      join(root, '.plowshare/skills/review/SKILL.md'),
      'opaque markdown',
    );
    await writeFile(
      join(root, '.plowshare/skills.yml'),
      'skills:\n  review:\n    agentVisible: false',
    );
    await writeFile(join(outside, 'secret'), 'private');
    await symlink(
      join(outside, 'secret'),
      join(root, '.plowshare/skills/review/references/escape'),
    );
    const enforcer = enforcing(root);
    const listed = await enforcer({
      id: 'list',
      op: 'glob',
      pattern: '.plowshare/skills/*/SKILL.md',
      purpose: 'definitions',
    });
    expect(listed.outcome).toBe('ok');
    expect(listed.paths).toEqual([
      join(root, '.plowshare/skills/review/SKILL.md'),
    ]);
    const read = await enforcer({
      id: 'read',
      op: 'read',
      path: '.plowshare/skills/review/SKILL.md',
      purpose: 'definitions',
    });
    expect(read.span?.lines).toEqual(['opaque markdown']);
    const policy = await enforcer({
      id: 'policy',
      op: 'read',
      path: '.plowshare/skills.yml',
      purpose: 'definitions',
    });
    expect(policy.outcome).toBe('ok');
    expect(policy.span?.lines).toEqual([
      'skills:',
      '  review:',
      '    agentVisible: false',
    ]);
    expect(
      (
        await enforcer({
          id: 'model-policy',
          op: 'read',
          path: '.plowshare/skills.yml',
        })
      ).outcome,
    ).toBe('refused');
    expect(
      (
        await enforcer({
          id: 'policy-write',
          op: 'write',
          path: '.plowshare/skills.yml',
          purpose: 'definitions',
          content: 'skills: {}',
        })
      ).outcome,
    ).toBe('refused');
    expect(
      (
        await enforcer({
          id: 'model',
          op: 'read',
          path: '.plowshare/skills/review/SKILL.md',
        })
      ).outcome,
    ).toBe('refused');
    expect(
      (
        await enforcer({
          id: 'escape',
          op: 'read',
          path: '.plowshare/skills/review/references/escape',
          purpose: 'definitions',
        })
      ).outcome,
    ).toBe('refused');
  });
});
