import type { Outcome } from '../binding/envelope.ts'
import { retrievalReply, type RetrieveResponse, type DocumentRankingResponse } from './retrieval.ts'

/** Database-backed catalogue rows preserve extra server fields without inventing defaults. */
export interface InformationRow { readonly [key: string]: unknown }
export interface InformationAdmission { readonly revision: string; readonly resource: string; readonly created: boolean }
export interface InformationTicket { readonly id: string; readonly state: string; readonly [key: string]: unknown }
export interface InformationQuestion { readonly job: string; readonly revision: string }
export interface InformationWindow { readonly revision: string; readonly start: number; readonly end: number; readonly total: number; readonly text: string }
export interface InformationEvidence { readonly id: string; readonly revision_id: string; readonly start_offset: number; readonly end_offset: number; readonly quote: string; readonly locator: string; readonly [key: string]: unknown }
export type RecordedEvidence = { readonly evidence: string }
export type FinalisedInformation = { readonly finalised: true }
export type QueuedInformation = { readonly queued: true }
export type ReleasedInformation = { readonly released: true }
export type AdoptedInformation = { readonly adopted: true }
export type ChangedInformation = { readonly changed: true }
export type InformationAvailability = { readonly availability: string }
export interface InformationEvents { readonly events: readonly InformationRow[]; readonly cursor: number }
export interface InformationMigrationInventory { readonly documents: readonly InformationRow[]; readonly payloads: readonly InformationRow[] }
export interface InformationMigrationInspection { readonly log: readonly InformationRow[]; readonly entries: readonly InformationRow[]; readonly job: readonly InformationRow[] }
export interface InformationReplies {
    'information.upload': InformationAdmission
    'information.acquire': InformationTicket
    'information.list': readonly InformationRow[]
    'information.status': InformationRow
    'information.read': InformationWindow
    'information.search': RetrieveResponse
    'information.rank': DocumentRankingResponse
    'information.ask': InformationQuestion
    'information.evidence.record': RecordedEvidence
    'information.evidence.read': InformationEvidence
    'information.record.report': InformationAdmission
    'information.finalise': FinalisedInformation
    'information.link': ChangedInformation
    'information.unlink': ChangedInformation
    'information.share': ChangedInformation
    'information.unshare': ChangedInformation
    'information.withdraw': InformationAvailability
    'information.exclude': InformationAvailability
    'information.unexclude': InformationAvailability
    'information.restore': InformationAvailability
    'information.delete': InformationAvailability
    'information.retry': QueuedInformation
    'information.revise': InformationAdmission
    'information.replace': InformationAdmission
    'information.refresh': InformationTicket
    'information.rebuild': QueuedInformation
    'information.allowance': ChangedInformation
    'information.events': InformationEvents
    'information.migration.list': InformationMigrationInventory
    'information.migration.adopt': AdoptedInformation
    'information.migration.inspect': InformationMigrationInspection
    'information.migration.release': ReleasedInformation
    'information.inventory': readonly InformationRow[]
    'information.acquisitions': readonly InformationRow[]
}
export type InformationFrame = keyof InformationReplies
export const INFORMATION_FRAMES = ["information.upload", "information.acquire", "information.list", "information.status", "information.read", "information.search", "information.rank", "information.ask", "information.evidence.record", "information.evidence.read", "information.record.report", "information.finalise", "information.link", "information.unlink", "information.share", "information.unshare", "information.withdraw", "information.exclude", "information.unexclude", "information.restore", "information.delete", "information.retry", "information.revise", "information.replace", "information.refresh", "information.rebuild", "information.allowance", "information.events", "information.migration.list", "information.migration.adopt", "information.migration.inspect", "information.migration.release", "information.inventory", "information.acquisitions"] as const
export const isInformationFrame = (type: string): type is InformationFrame => (INFORMATION_FRAMES as readonly string[]).includes(type)
const object = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
const text = (value: unknown): value is string => typeof value === 'string' && value.trim() !== ''
const integer = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const accepted: readonly string[] = ["information.upload", "information.revise", "information.replace", "information.record.report", "information.acquire", "information.refresh", "information.ask"]
/** Validate codes and identity-bearing receipts. A queued admission is never completed work. */
export function informationReply<T extends InformationFrame>(type: T, outcome: Outcome): InformationReplies[T] | undefined {
    if (outcome.code !== (accepted.includes(type) ? 'ACCEPTED' : 'OK')) return undefined
    const value = outcome.payload, verb = type.slice('information.'.length)
    if (verb === 'search') return retrievalReply('document.retrieve', value) as InformationReplies[T] | undefined
    if (verb === 'rank') return retrievalReply('document.rank', value) as InformationReplies[T] | undefined
    if (["list", "inventory", "acquisitions"].includes(verb)) {
        return Array.isArray(value) && value.every(row => object(row) && text(row['id']) && (verb !== 'acquisitions' || text(row['state']))) ? value as unknown as InformationReplies[T] : undefined
    }
    if (!object(value)) return undefined
    const rows = (value: unknown): boolean => Array.isArray(value) && value.every(object)
    if (verb === 'events') return rows(value['events']) && integer(value['cursor']) ? value as unknown as InformationReplies[T] : undefined
    if (verb === 'migration.list') return rows(value['documents']) && rows(value['payloads']) ? value as unknown as InformationReplies[T] : undefined
    if (verb === 'migration.inspect') return rows(value['log']) && rows(value['entries']) && rows(value['job']) ? value as unknown as InformationReplies[T] : undefined
    let valid = false
    if (["upload", "revise", "replace", "record.report"].includes(verb)) valid = text(value['revision']) && text(value['resource']) && typeof value['created'] === 'boolean'
    else if (["acquire", "refresh"].includes(verb)) valid = text(value['id']) && text(value['state'])
    else if (verb === 'ask') valid = text(value['job']) && text(value['revision'])
    else if (verb === 'status') valid = text(value['id']) && (text(value['state']) || (Array.isArray(value['steps']) && Array.isArray(value['inputs']) && typeof value['can_manage'] === 'boolean'))
    else if (verb === 'read') valid = text(value['revision']) && integer(value['start']) && integer(value['end']) && integer(value['total']) && value['start'] <= value['end'] && value['end'] <= value['total'] && typeof value['text'] === 'string' && value['text'].length === value['end'] - value['start']
    else if (verb === 'evidence.record') valid = text(value['evidence'])
    else if (verb === 'evidence.read') valid = text(value['id']) && text(value['revision_id']) && integer(value['start_offset']) && integer(value['end_offset']) && value['start_offset'] < value['end_offset'] && text(value['quote']) && value['quote'].length === value['end_offset'] - value['start_offset'] && text(value['locator'])
    else if (["link", "unlink", "share", "unshare", "allowance"].includes(verb)) valid = value['changed'] === true
    else if (["withdraw", "exclude", "unexclude", "restore", "delete"].includes(verb)) valid = value['availability'] === ({withdraw:'withdrawn',exclude:'excluded',unexclude:'included',restore:'active',delete:'deleted'} as Record<string,string>)[verb]
    else valid = value[({finalise:'finalised',retry:'queued',rebuild:'queued','migration.release':'released','migration.adopt':'adopted'} as Record<string,string>)[verb] ?? ''] === true
    return valid ? value as unknown as InformationReplies[T] : undefined
}
