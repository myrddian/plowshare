import { BackendError } from '../contracts.ts';
import type { OperationDeadline } from '../deadline.ts';

/** Vendor timeout zero disables its guard, so never forward an expired remainder. */
export function browserTimeout(deadline: OperationDeadline): number {
  deadline.requireActive();
  const remaining = Math.ceil(deadline.remainingMs());
  if (!Number.isSafeInteger(remaining) || remaining < 1)
    throw new BackendError('DEADLINE_EXPIRED', 'uncertain');
  return remaining;
}

/**
 * Bound an already-started vendor RPC while retaining its rejection observer,
 * including when the deadline expired before this function received the Promise.
 * Expiry is uncertainty, never evidence of physical cancellation. The context
 * owner retains pending work and enters bounded close before releasing its lease.
 */
export async function awaitBrowserWork<T>(
  work: Promise<T>,
  deadline: OperationDeadline,
): Promise<T> {
  let detach = () => {};
  const expired = new Promise<never>((_resolve, reject) => {
    const abort = () =>
      reject(new BackendError('DEADLINE_EXPIRED', 'uncertain'));
    deadline.signal.addEventListener('abort', abort, { once: true });
    detach = () => deadline.signal.removeEventListener('abort', abort);
    if (deadline.signal.aborted || deadline.remainingMs() <= 0) abort();
  });
  try {
    const result = await Promise.race([work, expired]);
    deadline.requireActive();
    return result;
  } finally {
    detach();
  }
}
