/**
 * The file channel this client holds: at most one, for one claim.
 *
 * <p>A session roots one project (`PresenceRegistry.declare` withdraws a session's
 * previous claim), so moving is closing one socket and opening another. The close
 * is waited for — boundedly — so the server has withdrawn the old presence before
 * it is asked for the new one; the handler tolerates the other order, but a
 * person reading the server log should see the two in the order they happened.
 *
 * <h2>How a refusal arrives</h2>
 *
 * <p>A claim the server will not accept is a socket that opens and is then closed
 * with 1003 and a reason; one it accepts is a socket that says so, with one
 * `{"ready":true}` frame once the claim is declared and the channel attached
 * (`FileChannelHandler.READY_PARAM`). `root` waits for whichever comes first:
 * the frame resolves it, a close rejects it with the server's reason — said by
 * the caller, and not also through `onLost` — and after `readiness` it resolves
 * anyway for attended callers; `requireReady` refuses an unacknowledged claim.
 *
 * <p><b>Why wait at all.</b> `open` fires when the 101 arrives, and the server
 * declares the claim after sending it, so a caller that asked who answers the
 * moment `root` returned could be answered before its own definitions counted.
 *
 * <p>`root` resolving is still not a promise the rooting lasts: a server restart
 * or a network drop can close it at any moment after. `current()` is the thing to
 * check before committing to a move: it goes `undefined` the instant a close this
 * client did not ask for arrives, and `onLost` says why.
 */
import type { Claim } from 'plowshare-client-ts/binding/auth';
import { serve } from 'plowshare-client-ts/binding/channel';
import type {
  Answering,
  Closing,
  Serving,
} from 'plowshare-client-ts/binding/channel';
import type { Socket } from 'plowshare-client-ts/binding/connection';

export interface RooterOptions {
  /** Opens `/v1/files` with this claim — `openFiles`, with the token bookkeeping done. */
  readonly open: (claim: Claim) => Promise<Socket>;
  readonly answering: (root: string) => Answering;
  /** A close this client did not ask for: a refusal, a server restart, a network drop. */
  readonly onLost: (claim: Claim, closing: Closing) => void;
  /** How long to wait for a released socket to finish closing. Default 2000 ms. */
  readonly patience?: number;
  /**
   * How long `root` waits for the server to say the claim landed before it
   * goes on without hearing it. Default 5000 ms. A server that predates the
   * frame never says, and costs each root this long and nothing else.
   */
  readonly readiness?: number;
  /** Headless clients require explicit acknowledgement; a timeout is a refusal. */
  readonly requireReady?: boolean;
  readonly requireReadyProject?: boolean;
}

export interface Rooter {
  current(): Claim | undefined;
  root(claim: Claim): Promise<void>;
  release(): Promise<void>;
}

interface Held {
  readonly claim: Claim;
  serving?: Serving;
  leaving: boolean;
  readonly closed: Promise<void>;
  readonly stop: () => void;
}

export function sameClaim(
  one: Claim | undefined,
  other: Claim | undefined,
): boolean {
  return (
    one !== undefined &&
    other !== undefined &&
    one.project === other.project &&
    one.machine === other.machine &&
    one.root === other.root
  );
}

export function rooter(options: RooterOptions): Rooter {
  const patience = options.patience ?? 2_000;
  const readiness = options.readiness ?? 5_000;
  let held: Held | undefined;

  const release = async (): Promise<void> => {
    const leaving = held;
    if (leaving === undefined) {
      return;
    }
    held = undefined;
    leaving.leaving = true;
    leaving.stop();
    leaving.serving?.close();
    await Promise.race([
      leaving.closed,
      new Promise<void>((waited) => {
        setTimeout(waited, patience).unref?.();
      }),
    ]);
  };

  return {
    current: () => held?.claim,
    async root(claim: Claim): Promise<void> {
      await release();
      const socket = await options.open(claim);
      let closed: () => void = () => undefined;
      const entry: Held = {
        claim,
        leaving: false,
        closed: new Promise<void>((done) => {
          closed = done;
        }),
        stop: () => answer.close?.(),
      };
      const answer = options.answering(claim.root) as Answering & {
        close?: () => void;
      };
      held = entry;
      // Settled by whichever comes first: the claim landing, the socket
      // closing, or the wait running out. Only the first one counts.
      let landed: (outcome: Closing | undefined) => void = () => undefined;
      let settled = false;
      const landing = new Promise<Closing | undefined>((done) => {
        landed = (outcome) => {
          if (!settled) {
            settled = true;
            done(outcome);
          }
        };
      });
      entry.serving = serve({
        socket,
        answer,
        onReady: (project) =>
          landed(
            project === claim.project
              ? undefined
              : {
                  code: 1003,
                  reason:
                    'the server did not acknowledge the requested project; nothing was rooted',
                },
          ),
        onClose: (closing) => {
          entry.stop();
          closed();
          if (held === entry) {
            held = undefined;
          }
          if (!settled) {
            // BEFORE THE CLAIM LANDED: a refusal, said once, by
            // whoever awaited this root — not a second time as a
            // loss of something that was never held.
            landed(closing);
            return;
          }
          if (!entry.leaving) {
            options.onLost(claim, closing);
          }
        },
      });
      const timer = setTimeout(
        () =>
          landed(
            options.requireReady || options.requireReadyProject
              ? {
                  code: 1006,
                  reason:
                    'the server did not acknowledge file presence; nothing was rooted',
                }
              : undefined,
          ),
        readiness,
      );
      timer.unref?.();
      const refused = await landing;
      clearTimeout(timer);
      if (refused !== undefined && !entry.leaving) {
        await release();
        throw new Error(
          refused.reason ??
            `the file channel closed before the server took the claim (${refused.code ?? 'no code'})`,
        );
      }
    },
    release,
  };
}
