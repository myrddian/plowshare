import assert from 'node:assert/strict';
import test from 'node:test';
import { decodeComputerHostConfig } from './config.ts';
import { computerDeclarations } from './provider.ts';

const source = {
  version: 1,
  origin: 'https://fixture.invalid',
  binding: {
    project: 'fixture',
    provider: 'computer',
    account: '@service/11111111-1111-1111-1111-111111111111',
  },
  credential: { kind: 'service-token', environment: 'FIXTURE_TOKEN' },
  journalDirectory: '/fixture/journal',
  catalogue: { leaseSeconds: 300, renewalMs: 30_000 },
  browser: {
    executablePath: '/fixture/browser',
    headless: true,
    targets: [
      {
        targetId: 'browser',
        label: 'Browser',
        url: 'https://canvas.invalid',
        viewport: { width: 320, height: 160 },
      },
    ],
  },
};

await test('host config is explicit, typed and independent of ambient local defaults', () => {
  const config = decodeComputerHostConfig(source);
  assert.equal(config.origin, 'https://fixture.invalid');
  assert.equal(config.budgets.controlMs, 2000);
  assert.equal(config.budgets.observeMs, 10_000);
  assert.equal(config.credential.kind, 'service-token');
  assert.ok(Object.isFrozen(config.binding));
});

await test('malformed credentials, endpoints, paths, budgets and authority fail before startup', () => {
  for (const invalid of [
    { ...source, origin: '' },
    { ...source, origin: ' https://fixture.invalid' },
    { ...source, origin: 'https://owner:secret@fixture.invalid' },
    { ...source, origin: 'https://fixture.invalid/path' },
    { ...source, journalDirectory: 'journal' },
    { ...source, extra: true },
    {
      ...source,
      credential: {
        kind: 'service-token',
        environment: 'token',
        value: 'secret',
      },
    },
    {
      ...source,
      credential: {
        kind: 'saved',
        directory: '/private/credentials',
        environment: 'TOKEN',
      },
    },
    {
      ...source,
      credential: { kind: 'saved', directory: '/private/credentials' },
    },
    { ...source, binding: { ...source.binding, account: 'owner' } },
    {
      ...source,
      binding: { ...source.binding, account: '@service/not-a-uuid' },
    },
    { ...source, budgets: { controlMs: 0 } },
    { ...source, budgets: { observeMs: Infinity } },
    { ...source, budgets: { cleanupMs: null } },
    { ...source, catalogue: { leaseSeconds: 60, renewalMs: 60_000 } },
    { ...source, catalogue: { leaseSeconds: 60, renewalMs: 30_000 } },
    { ...source, prefix: 'bad-prefix' },
    { ...source, binding: { ...source.binding, project: 'PERSONAL:abcdef' } },
    { ...source, browser: undefined },
  ])
    assert.throws(() => decodeComputerHostConfig(invalid));
});

await test('project identity follows protocol scope rather than display names', () => {
  for (const project of ['global', 'Personal', 'client:fixture'])
    assert.equal(
      decodeComputerHostConfig({
        ...source,
        binding: { ...source.binding, project },
      }).binding.project,
      project,
    );
  assert.equal(
    decodeComputerHostConfig({
      ...source,
      binding: { ...source.binding, account: 'owner' },
      credential: { kind: 'saved', directory: '/private/credentials' },
    }).credential.kind,
    'saved',
  );
});

const controlled = {
  ...source.browser,
  ownershipDirectory: '/fixture/browser-ownership',
  control: {
    enabled: true,
    tools: ['windows', 'focus', 'navigate', 'pointer', 'type', 'key', 'scroll'],
    allowedOrigins: ['https://canvas.invalid'],
  },
};

await test('controlled host configuration is explicit and round-trips without changing P1 defaults', () => {
  const config = decodeComputerHostConfig({
    ...source,
    browser: undefined,
    browserControl: controlled,
    sessions: { frameFreshMs: 7000, idleMs: 90_000 },
    budgets: { browserObservationReserveMs: 4000 },
  });
  assert.equal(config.browser, undefined);
  assert.equal(config.browserControl?.control.enabled, true);
  assert.equal(
    config.browserControl?.ownershipDirectory,
    '/fixture/browser-ownership',
  );
  assert.equal(config.sessions?.frameFreshMs, 7000);
  assert.equal(config.budgets.browserObservationReserveMs, 4000);
  assert.deepEqual(decodeComputerHostConfig(config), config);
  assert.equal(
    computerDeclarations(
      config.prefix,
      {
        controlBudgetMs: config.budgets.controlMs,
        observeBudgetMs: config.budgets.observeMs,
      },
      config.browserControl?.control.tools,
    ).length,
    11,
  );
  const old = decodeComputerHostConfig(source);
  assert.equal(old.browserControl, undefined);
  assert.equal(old.sessions, undefined);
  assert.equal(computerDeclarations(old.prefix).length, 4);
});

await test('control policy, shared budgets and session bounds refuse unsafe config before startup', () => {
  const valid = { ...source, browser: undefined, browserControl: controlled };
  for (const delta of [
    {
      browserControl: {
        ...controlled,
        control: { ...controlled.control, enabled: false },
      },
    },
    {
      browserControl: {
        ...controlled,
        control: { ...controlled.control, tools: [] },
      },
    },
    {
      browserControl: {
        ...controlled,
        control: { ...controlled.control, tools: ['*'] },
      },
    },
    {
      browserControl: {
        ...controlled,
        control: {
          ...controlled.control,
          allowedOrigins: ['https://other.invalid'],
        },
      },
    },
    { browserControl: { ...controlled, ownershipDirectory: 'relative' } },
    {
      browserControl: {
        ...controlled,
        endpoint: 'https://unrequested.invalid',
      },
    },
    { budgets: { browserObservationReserveMs: 10_000 } },
    { budgets: { browserObservationReserveMs: null } },
    { sessions: { frameFreshMs: 0 } },
    { sessions: { idleMs: Infinity } },
    { sessions: { trustModel: true } },
    { browser: source.browser },
  ])
    assert.throws(() => decodeComputerHostConfig({ ...valid, ...delta }));
});
