import { checkedTransport } from './transport.ts';
import { decodePayload, decodeReply } from './schema.ts';
import type { Payloads } from './direct.ts';
import type { OperationTransport } from './response.ts';
import type {
  ApplicationCredential,
  ApplicationIdentityStatus,
  ApplicationTokenReceipt,
} from './application-identities.ts';

type Pending = Payloads['application.token.issue'];
/** Persist only the issue DTO, scoped by authenticated server/account. Secrets never enter this store. */
export interface IdentityIntentStore {
  load(): unknown;
  save(value: Pending | undefined): void;
}
export class ApplicationIdentityRefused extends Error {}

/** Shared control lifecycle: one send, receipt recovery after unknown delivery, explicit rotation. */
export class ApplicationIdentityClient {
  private readonly transport: OperationTransport;
  private readonly intents: IdentityIntentStore;
  constructor(transport: OperationTransport, intents: IdentityIntentStore) {
    this.transport = checkedTransport(transport);
    this.intents = intents;
  }
  pending(): Pending | undefined {
    const value = this.intents.load();
    return value === undefined
      ? undefined
      : decodePayload('application.token.issue', value);
  }
  async status(project: string): Promise<ApplicationIdentityStatus> {
    const answer = await this.transport.ask('application.identity.status', {
      project,
    });
    if (answer.code !== 'OK')
      throw new ApplicationIdentityRefused(
        answer.said ?? 'Identity status refused.',
      );
    return decodeReply('application.identity.status', answer.payload);
  }
  /** The caller reveals the returned secret once, then discards it. It is never cached here. */
  async issue(input: Pending): Promise<ApplicationCredential> {
    const pending = this.pending();
    if (pending)
      throw new Error(
        `Read pending credential receipt ${pending.requestId} before another issuance.`,
      );
    const request = decodePayload('application.token.issue', input);
    this.intents.save(request); // Durable before send; transport failures leave the original DTO fenced.
    const answer = await this.transport.ask('application.token.issue', request);
    if (answer.code !== 'OK') {
      // Server/internal failures can follow a committed effect. Only boundary/domain refusals
      // establish non-admission; every other failure retains the original request fence.
      if (
        ['BAD_REQUEST', 'VALIDATION_FAILED', 'CONFLICT', 'NOT_FOUND'].includes(
          answer.code,
        )
      )
        this.intents.save(undefined);
      throw new ApplicationIdentityRefused(
        answer.said ?? 'Credential issuance refused.',
      );
    }
    const result = decodeReply('application.token.issue', answer.payload);
    this.intents.save(undefined);
    return result;
  }
  async recover(): Promise<ApplicationTokenReceipt> {
    const pending = this.pending();
    if (!pending) throw new Error('No pending credential request.');
    const answer = await this.transport.ask('application.token.receipt', {
      project: pending.project,
      requestId: pending.requestId,
    });
    if (answer.code !== 'OK')
      throw new ApplicationIdentityRefused(
        answer.said ?? 'Receipt not confirmed; no request was replayed.',
      );
    const receipt = decodeReply('application.token.receipt', answer.payload);
    this.intents.save(undefined);
    return receipt;
  }
}
