/** Public HTTP auth contract. This identifier is not a credential or a work receipt. */
export const REFRESH_INTENT_HEADER = 'X-Plowshare-Refresh-Intent';

/** Validate the optional caller-supplied intent before submitting any credential.
 * Mint with platform cryptography once per new fetch; never persist or resubmit
 * after uncertainty. Identical physical deliveries are coalesced for at most
 * thirty seconds. This neutral binding does not own platform randomness. */
export function validateRefreshIntent(value: unknown): asserts value is string {
  if (
    typeof value !== 'string' ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
      value,
    )
  )
    throw new Error(
      'Invalid refresh intent. Expected a canonical random UUID.',
    );
}
