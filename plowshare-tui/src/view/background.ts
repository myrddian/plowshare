import { errorMessage } from 'plowshare-client-ts/binding/values';
/** Terminal background work reports unexpected failure on the diagnostic stream.
 * Individual operations still own their normal state, cancellation and recovery. */
export function background(work: Promise<unknown> | undefined): void {
  if (work)
    void work.catch((reason: unknown) => {
      process.stderr.write(`Background task failed: ${errorMessage(reason)}\n`);
    });
}
