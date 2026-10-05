import {
  mkdtemp,
  mkdir,
  readFile,
  realpath,
  rm,
  symlink,
  writeFile,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  AlreadyMarked,
  markerName,
  projectManifest,
  readProjectManifest,
  resolveMarked,
  UNNAMED_MACHINE,
  discover,
  mark,
  markedName,
  namedAfter,
  thisMachine,
  within,
} from './marker.ts';

let top = '';

beforeEach(async () => {
  top = await realpath(await mkdtemp(join(tmpdir(), 'marker-')));
});

afterEach(async () => {
  await rm(top, { recursive: true, force: true });
});

async function marked(directory: string, line: string): Promise<void> {
  await mkdir(join(directory, '.plowshare'), { recursive: true });
  await writeFile(join(directory, '.plowshare', 'project'), line);
}

describe('discovery walks up, and the nearest marker wins', () => {
  it('finds a marker in an ancestor, the way git finds .git', async () => {
    await marked(top, 'accounts\n');
    await mkdir(join(top, 'src', 'deep'), { recursive: true });
    expect(await discover(join(top, 'src', 'deep'))).toEqual({
      root: top,
      project: 'accounts',
    });
  });

  it('lets a sub-project shadow the project it sits in', async () => {
    await marked(top, 'accounts');
    await marked(join(top, 'ledger'), 'ledger');
    await mkdir(join(top, 'ledger', 'src'), { recursive: true });
    expect(await discover(join(top, 'ledger', 'src'))).toEqual({
      root: join(top, 'ledger'),
      project: 'ledger',
    });
  });

  it('walks past a .plowshare that holds definitions and no name', async () => {
    await marked(top, 'accounts');
    await mkdir(join(top, 'tools', '.plowshare', 'bots'), { recursive: true });
    expect((await discover(join(top, 'tools')))?.project).toBe('accounts');
  });

  it('reads a blank name as no marker', async () => {
    await marked(top, '   \n');
    expect(await markedName(top)).toBeUndefined();
  });
});

describe('marking', () => {
  it('writes a version 1 JSON project identity', async () => {
    expect(await mark(top, 'ledger')).toEqual({ root: top, project: 'ledger' });
    expect(
      JSON.parse(await readFile(join(top, '.plowshare', 'project'), 'utf8')),
    ).toEqual({ version: 1, name: 'ledger' });
  });

  it('is a no-op for the name already there, and refuses a different one', async () => {
    await marked(top, 'ledger\n');
    await expect(mark(top, 'ledger')).resolves.toEqual({
      root: top,
      project: 'ledger',
    });
    await expect(mark(top, 'accounts')).rejects.toBeInstanceOf(AlreadyMarked);
    expect(await readFile(join(top, '.plowshare', 'project'), 'utf8')).toBe(
      'ledger\n',
    );
  });
});

describe('names', () => {
  it('takes the machine from PLOWSHARE_MACHINE, then the host, then a placeholder', () => {
    expect(thisMachine({ PLOWSHARE_MACHINE: ' bench ' }, 'host.local')).toBe(
      'bench',
    );
    expect(thisMachine({}, 'host.local')).toBe('host.local');
    expect(thisMachine({ PLOWSHARE_MACHINE: '' }, '  ')).toBe(UNNAMED_MACHINE);
  });

  it('names a new project after its directory', () => {
    expect(namedAfter(join(top, 'ledger'))).toBe('ledger');
  });

  it('knows a directory is within itself and its ancestors, and not a sibling', () => {
    expect(within(top, top)).toBe(true);
    expect(within(top, join(top, 'a', 'b'))).toBe(true);
    expect(within(join(top, 'a'), join(top, 'ab'))).toBe(false);
  });
});

it('refuses a linked marker instead of identifying or overwriting another checkout', async () => {
  const outside = join(top, 'other');
  await mkdir(outside);
  await marked(outside, 'other\n');
  const checkout = join(top, 'checkout');
  await mkdir(checkout);
  await symlink(join(outside, '.plowshare'), join(checkout, '.plowshare'));
  await expect(discover(checkout)).rejects.toThrow('regular');
  await expect(mark(checkout, 'replacement')).rejects.toThrow('regular');
  expect(await readFile(join(outside, '.plowshare/project'), 'utf8')).toBe(
    'other\n',
  );
});

describe('root-level manifests are client-only DISJOINT candidates', () => {
  it('accepts formal version 1 JSON and the legacy first-line name', async () => {
    for (const text of [
      'Integration\nlegacy annotation\n',
      JSON.stringify({ version: 1, name: 'Integration' }),
    ]) {
      await writeFile(join(top, 'plowshare'), text);
      expect(await discover(top)).toEqual({
        root: top,
        project: 'Integration',
        kind: 'DISJOINT',
      });
      await expect(
        readFile(join(top, '.plowshare/project')),
      ).rejects.toMatchObject({ code: 'ENOENT' });
    }
    for (const text of ['{"version":2,"name":"x"}', '{', ''])
      expect(() => markerName(text)).toThrow();
  });
  it('projects declared routing while preserving application fields in the source file', async () => {
    const manifest = {
      version: 1,
      name: 'Integration',
      routing: {
        acceptFrom: ['scheduler'],
        sendTo: ['notifications'],
        routeFiles: ['routes/internal.json'],
      },
      homeAssistant: { entities: ['light.office'] },
    };
    const text = JSON.stringify(manifest);
    const { homeAssistant: _application, ...checked } = manifest;
    expect(projectManifest(text)).toEqual(checked);
    await writeFile(join(top, 'plowshare'), text);
    expect(await readProjectManifest(top)).toEqual(checked);
    expect(await discover(top)).toEqual({
      root: top,
      project: 'Integration',
      kind: 'DISJOINT',
    });
    expect(await readFile(join(top, 'plowshare'), 'utf8')).toBe(text);
    await expect(
      readFile(join(top, '.plowshare/project')),
    ).rejects.toMatchObject({ code: 'ENOENT' });
    await marked(top, 'Local\n');
    expect(await readProjectManifest(top)).toEqual({
      version: 1,
      name: 'Local',
    });
    expect(projectManifest('Legacy\nannotation')).toEqual({
      version: 1,
      name: 'Legacy',
    });
  });
  it('rejects malformed routing lists and route files outside the project', () => {
    for (const routing of [
      null,
      [],
      { acceptFrom: true },
      { sendTo: ['x', 'x'] },
      { sendTo: [''] },
      ...[
        '../outside.json',
        '/absolute.json',
        'routes/../other.json',
        'routes//file.json',
        'C:\\routes.json',
        './routes.json',
        'routes/',
      ].map((file) => ({ routeFiles: [file] })),
    ]) {
      expect(() =>
        projectManifest(
          JSON.stringify({ version: 1, name: 'Integration', routing }),
        ),
      ).toThrow();
    }
  });
  it('gives both local marker conventions precedence over a root manifest', async () => {
    await writeFile(join(top, 'plowshare'), '{invalid');
    await marked(top, 'Local\n');
    expect(await discover(top)).toEqual({ root: top, project: 'Local' });
    await writeFile(
      join(top, '.plowshare/plowshare'),
      JSON.stringify({ version: 1, name: 'Formal local' }),
    );
    expect(await discover(top)).toEqual({ root: top, project: 'Formal local' });
  });
  it('checks registered local/UNION identities before creating private context', async () => {
    const frames: string[] = [];
    const local = {
      name: 'Integration',
      workspace: top,
      machine: 'test',
      lent: [],
      exclusions: [],
      members: [],
      type: 'STANDARD',
    };
    const connection = {
      ask: async (type: string) => {
        frames.push(type);
        return { code: 'OK' as const, payload: [local] };
      },
      close() {},
    };
    expect(
      await resolveMarked(
        { root: top, project: 'Integration', kind: 'DISJOINT' },
        connection,
        'test',
      ),
    ).toEqual({ root: top, project: 'Integration' });
    expect(frames).toEqual(['project.list']);
  });
  it('attaches without touching the manifest or creating metadata and rejects linked manifests', async () => {
    const text = JSON.stringify({ version: 1, name: 'Integration' });
    await writeFile(join(top, 'plowshare'), text);
    const frames: string[] = [];
    const key =
      'client:scope:' + Buffer.from('Integration').toString('base64url');
    const connection = {
      ask: async (type: string) => {
        frames.push(type);
        return {
          code: 'OK' as const,
          payload:
            type === 'project.list'
              ? []
              : {
                  name: key,
                  displayName: 'Integration',
                  workspace: top,
                  machine: 'test',
                  lent: [],
                  exclusions: [],
                  members: [],
                  type: 'DISJOINT',
                },
        };
      },
      close() {},
    };
    const found = await resolveMarked(
      (await discover(top))!,
      connection,
      'test',
    );
    expect(found.project).toBe(key);
    await mark(top, key);
    expect(await readFile(join(top, 'plowshare'), 'utf8')).toBe(text);
    await expect(
      readFile(join(top, '.plowshare/project')),
    ).rejects.toMatchObject({ code: 'ENOENT' });
    expect(frames).toEqual(['project.list', 'project.attach']);
    const linked = join(top, 'linked');
    await mkdir(linked);
    await symlink(join(top, 'plowshare'), join(linked, 'plowshare'));
    await expect(discover(linked)).rejects.toThrow('regular file');
  });
});
