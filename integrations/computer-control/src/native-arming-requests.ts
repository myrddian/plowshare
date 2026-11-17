import { randomUUID } from 'node:crypto';
import type { NativeHostArming } from './native-host-arming.ts';
import type { OperationDeadline } from './deadline.ts';
import { decodeNativeArmRequest } from './native-arming.ts';

/**
 * Foreground host policy, separate from model tools and session-open delivery.
 * A failed or uncertain intent is never automatically resubmitted. A new opened
 * session has a new lease and needs its own local gesture. Hosts offering richer
 * UI may instead call the public nativeArming facade directly.
 */
export class NativeArmingRequests {
  private readonly requested = new Set<string>();
  constructor(private readonly durationMs: number) {
    decodeNativeArmRequest({ requestId: randomUUID(), durationMs });
  }
  async present(
    arming: NativeHostArming,
    deadline: OperationDeadline,
  ): Promise<void> {
    deadline.requireActive();
    const sessions = arming.sessions();
    if (sessions.length > 32)
      throw new Error('Native session inventory exceeds its bound');
    const key = (session: {
      readonly sessionId: string;
      readonly generation: string;
    }) => JSON.stringify([session.sessionId, session.generation]);
    const current = new Set(sessions.map((row) => key(row.session)));
    for (const previous of this.requested)
      if (!current.has(previous)) this.requested.delete(previous);
    for (const row of sessions) {
      const identity = key(row.session);
      if (row.closing || this.requested.has(identity)) continue;
      deadline.requireActive();
      this.requested.add(identity);
      // Mark before dispatch, including a lost response. No automatic retry or
      // prompt refresh; explicit close/open starts another locally approved grant.
      await arming.request(
        row.owner,
        row.session,
        { requestId: randomUUID(), durationMs: this.durationMs },
        deadline,
      );
      deadline.requireActive();
    }
  }
}
