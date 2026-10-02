import { setTimeout as delay } from 'node:timers/promises'
export { authenticateConfigured, canonicalRoot } from 'plowshare-client-node/session'

export function pause(ms: number, signal: AbortSignal): Promise<void> {
    return delay(ms, undefined, { signal })
}
