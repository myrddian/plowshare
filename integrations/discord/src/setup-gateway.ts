import { SetupProblem } from './setup-error.ts';
import { connectPlowshare, requirePayload } from 'plowshare-client-node';
import type { Plowshare, Payloads } from 'plowshare-client-node';
import type { Configuration } from './contracts.ts';
import type { SetupGateway } from './setup-contracts.ts';
import { SdkGateway } from './gateway.ts';

/** Verify only identity and catalog access. Setup never sends a test message or starts agent work. */
export async function verifyServiceCredential(
  credential: string,
  principal: string,
  config: Configuration,
): Promise<void> {
  const client = await connectPlowshare({
    origin: config.origin,
    token: credential,
    timeoutMs: config.requestTimeoutMs,
  });
  try {
    const gateway = new SdkGateway(client);
    if ((await gateway.identity()) !== principal)
      throw new SetupProblem(
        'Credential authenticated as another service principal',
      );
    for (const route of config.routes) await gateway.catalog(route);
  } finally {
    client.close();
  }
}
/** The composition root supplies an authenticated administrator SDK connection. */
export class SdkSetupGateway implements SetupGateway {
  constructor(private readonly client: Plowshare) {}
  async administrator() {
    return requirePayload(await this.client.request('admin.status', {}));
  }
  async deploymentStatus(project: string) {
    return requirePayload(
      await this.client.request('application.deployment.status', { project }),
    );
  }
  async deploy(request: Payloads['application.deploy']) {
    return requirePayload(
      await this.client.request('application.deploy', request),
    );
  }
  async deploymentReceipt(project: string, requestId: string) {
    return requirePayload(
      await this.client.request('application.deployment.receipt', {
        project,
        requestId,
      }),
    );
  }
  async identity(project: string) {
    return requirePayload(
      await this.client.request('application.identity.status', { project }),
    );
  }
  async issue(request: Payloads['application.token.issue']) {
    return requirePayload(
      await this.client.request('application.token.issue', request),
    );
  }
  async tokenReceipt(project: string, requestId: string) {
    return requirePayload(
      await this.client.request('application.token.receipt', {
        project,
        requestId,
      }),
    );
  }
  verify(credential: string, principal: string, config: Configuration) {
    return verifyServiceCredential(credential, principal, config);
  }
}
