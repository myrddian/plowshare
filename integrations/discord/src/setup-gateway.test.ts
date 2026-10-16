import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Plowshare } from 'plowshare-client-node';
import type { Arrival, Socket } from 'plowshare-client-ts/binding/connection';
import { CURRENT_VERSION } from 'plowshare-client-ts/binding/envelope';
import { decodePayload } from 'plowshare-client-ts/operations/schema';
import { object, text } from './config.ts';
import { SdkSetupGateway } from './setup-gateway.ts';
import { DiscordSetup } from './setup-engine.ts';
import {
  MemorySetup,
  SetupServer,
  setupConfiguration,
  setupSource,
} from './setup.test-support.ts';
import type { Configuration } from './contracts.ts';

/** Real public SDK encoding/decoding, with a bounded in-memory server protocol fixture. */
class SetupWire implements Socket {
  readonly operations: string[] = [];
  private listener: ((event: Arrival) => void) | undefined;
  private pending: Promise<void> = Promise.resolve();
  constructor(readonly server: SetupServer) {}
  addEventListener(type: 'message', listener: (event: Arrival) => void): void;
  addEventListener(type: 'close', listener: (event: unknown) => void): void;
  addEventListener(type: string, listener: (event: Arrival) => void): void {
    if (type === 'message') this.listener = listener;
  }
  close(): void {}
  send(frame: string): void {
    const raw: unknown = JSON.parse(frame);
    const envelope = object(raw, ['id', 'type', 'protocol_version', 'payload']);
    const type = text(envelope.type);
    this.operations.push(type);
    this.pending = this.reply(type, envelope.payload).then(
      (payload) => this.answer(envelope.id, type, 'OK', payload),
      () => this.answer(envelope.id, type, 'BAD_REQUEST', null),
    );
  }
  private answer(
    id: unknown,
    type: string,
    code: string,
    payload: unknown,
  ): void {
    this.listener?.({
      data: JSON.stringify({
        id,
        type,
        protocol_version: CURRENT_VERSION,
        payload: { code, payload },
      }),
    });
  }
  settle() {
    return this.pending;
  }
  private async reply(type: string, value: unknown): Promise<unknown> {
    switch (type) {
      case 'admin.status':
        return this.server.administrator();
      case 'application.deployment.status':
        return this.server.deploymentStatus(decodePayload(type, value).project);
      case 'application.identity.status':
        return this.server.identity(decodePayload(type, value).project);
      case 'application.deploy':
        return this.server.deploy(decodePayload(type, value));
      case 'application.token.issue':
        return this.server.issue(decodePayload(type, value));
      case 'application.deployment.receipt': {
        const request = decodePayload(type, value);
        return this.server.deploymentReceipt(
          request.project,
          request.requestId,
        );
      }
      case 'application.token.receipt': {
        const request = decodePayload(type, value);
        return this.server.tokenReceipt(request.project, request.requestId);
      }
      default:
        throw new Error('Unexpected setup operation');
    }
  }
}
class FixtureGateway extends SdkSetupGateway {
  constructor(
    client: Plowshare,
    private readonly server: SetupServer,
  ) {
    super(client);
  }
  override verify(
    credential: string,
    principal: string,
    _config: Configuration,
  ) {
    return this.server.verify(credential, principal);
  }
}
await test('setup uses only existing public WS contracts and keeps Discord-only runtime configuration', async () => {
  const config = setupConfiguration();
  const server = new SetupServer(config);
  const wire = new SetupWire(server);
  const client = new Plowshare({ socket: wire });
  const repository = new MemorySetup();
  try {
    const engine = new DiscordSetup(
      config,
      repository,
      new FixtureGateway(client, server),
    );
    await engine.install(setupSource());
    await engine.recover();
    assert.equal(server.deployments.length, 1);
    assert.equal(server.issuances.length, 1);
    assert.ok(repository.published);
    assert.equal(
      wire.operations.filter((op) => op === 'application.deploy').length,
      1,
    );
    assert.equal(
      wire.operations.filter((op) => op === 'application.token.issue').length,
      1,
    );
    assert.ok(
      wire.operations.every(
        (op) => op === 'admin.status' || op.startsWith('application.'),
      ),
    );
  } finally {
    await wire.settle();
    client.close();
  }
});
await test('an existing public SDK refusal cannot cause setup to resend an uncertain deployment', async () => {
  const config = setupConfiguration();
  const server = new SetupServer(config);
  const wire = new SetupWire(server);
  const client = new Plowshare({ socket: wire });
  const repository = new MemorySetup();
  server.failDeploy = true;
  try {
    const engine = new DiscordSetup(
      config,
      repository,
      new FixtureGateway(client, server),
    );
    await assert.rejects(engine.install(setupSource()));
    server.missingReceipt = true;
    await assert.rejects(engine.recover());
    assert.equal(server.deployments.length, 1);
    assert.equal(server.issuances.length, 0);
  } finally {
    await wire.settle();
    client.close();
  }
});
