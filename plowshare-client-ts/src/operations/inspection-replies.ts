import type { Outcome } from '../binding/envelope.ts'
import { jobView, type ObservedJob } from '../binding/job-view.ts'
import { boardTopicCheck, type BoardTopic } from './administrative-replies.ts'
import { bool, count, list, named, nullable, positive, record, strings, text, type Check } from './wire-checks.ts'

export interface BoardSummary { readonly topic: BoardTopic; readonly messages: number; readonly documents: number }
export interface BoardTopics { readonly topics: readonly BoardSummary[]; readonly more: boolean; readonly offset: number }
export interface BoardSeat {
    readonly topic: string; readonly occupant: string; readonly conversation: string; readonly passed: boolean; readonly failedEnding: string | null
    readonly silentWakes: number; readonly seenThrough: string | null; readonly alertsUsed: number
}
export interface SeatView { readonly seat: BoardSeat; readonly state: string; readonly job: string | null; readonly reason: string | null; readonly position: number | null; readonly waitedMillis: number | null; readonly overdue: boolean }
export interface BoardMessage {
    readonly id: string; readonly topic: string; readonly replyTo: string | null; readonly authorKind: string; readonly author: string
    readonly conversation: string | null; readonly entry: number | null; readonly kind: string; readonly title: string | null; readonly body: string
    readonly alert: boolean; readonly mentions: readonly string[]; readonly postedAt: string
}
export interface BoardDecision { readonly request: string; readonly approved: boolean; readonly reason: string; readonly child: string | null }
export interface BoardMessages { readonly topic: BoardTopic; readonly root: BoardTopic; readonly seats: readonly SeatView[]; readonly messages: readonly BoardMessage[]; readonly decisions: readonly BoardDecision[] }
export interface Ready { readonly topic: string; readonly member: string; readonly specifier: string; readonly position: number; readonly waitedMillis: number; readonly overdue: boolean }
export interface PoolUse { readonly pool: string; readonly slots: number; readonly used: number }
export interface Swarm { readonly pools: readonly PoolUse[]; readonly ready: readonly Ready[]; readonly topics: readonly BoardSummary[]; readonly seats: readonly SeatView[]; readonly more: boolean }
/** The unrooted response deliberately contains only these two flags. */
export interface IneligibleUnion { readonly eligible: false; readonly enabled: false }
export interface EligibleUnion {
    readonly eligible: true; readonly enabled: boolean; readonly state: string; readonly syncHidden: readonly string[]
    readonly maxFileBytes: number; readonly openConflicts: number; readonly url: string
}
export interface UnionConflict {
    readonly n: number; readonly path: string; readonly baseBlob: string | null; readonly oursBlob: string | null
    readonly theirsBlob: string | null; readonly theirsAuthor: string; readonly runId: string | null; readonly openedAt: string
}
export interface UnionConflicts { readonly conflicts: readonly UnionConflict[] }
export interface InspectionReplies {
    'job.list': readonly ObservedJob[]
    'job.limits': ObservedJob
    'board.topics': BoardTopics
    'board.messages': BoardMessages
    'swarm.status': Swarm
    'union.status': IneligibleUnion | EligibleUnion
    'union.conflict.list': UnionConflicts
}
export type InspectionOperation = keyof InspectionReplies
const summary = record({ topic: boardTopicCheck, messages: count, documents: count })
const seat = record({ seat: record({ topic: named, occupant: named, conversation: named, passed: bool, failedEnding: nullable(text), silentWakes: count, seenThrough: nullable(text), alertsUsed: count }),
    state: named, job: nullable(named), reason: nullable(text), position: nullable(count), waitedMillis: nullable(count), overdue: bool })
const message = record({ id: named, topic: named, replyTo: nullable(named), authorKind: named, author: text, conversation: nullable(named), entry: nullable(count), kind: named,
    title: nullable(text), body: text, alert: bool, mentions: strings, postedAt: named })
const detail = record({ topic: boardTopicCheck, root: boardTopicCheck, seats: list(seat), messages: list(message), decisions: list(record({ request: named, approved: bool, reason: text, child: nullable(named) })) }, row => {
    const topic = row['topic'] as BoardTopic, root = row['root'] as BoardTopic
    return topic.root === root.id && (row['seats'] as SeatView[]).every(each => each.seat.topic === topic.id)
        && (row['messages'] as BoardMessage[]).every(each => each.topic === topic.id)
})
const union = record({ eligible: bool, enabled: bool }, row => row['eligible'] === false ? row['enabled'] === false
    : record({ state: named, syncHidden: strings, maxFileBytes: positive, openConflicts: count, url: named })(row))
const conflict = record({ n: positive, path: named, baseBlob: nullable(text), oursBlob: nullable(text), theirsBlob: nullable(text), theirsAuthor: text, runId: nullable(text), openedAt: named })
const readers = {
    'job.list': list(value => jobView(value) !== undefined), 'job.limits': value => jobView(value) !== undefined,
    'board.topics': record({ topics: list(summary), more: bool, offset: count }), 'board.messages': detail,
    'swarm.status': record({ pools: list(record({ pool: named, slots: count, used: count })), ready: list(record({ topic: named, member: named, specifier: named, position: count, waitedMillis: count, overdue: bool })),
        topics: list(summary), seats: list(seat), more: bool }),
    'union.status': union, 'union.conflict.list': record({ conflicts: list(conflict) }),
} satisfies Record<InspectionOperation, Check>
export const INSPECTION_OPERATIONS = Object.keys(readers) as readonly InspectionOperation[]
export function isInspectionOperation(type: string): type is InspectionOperation { return Object.hasOwn(readers, type) }
export function inspectionReply<T extends InspectionOperation>(type: T, outcome: Outcome): (Outcome & { readonly payload?: InspectionReplies[T] }) | undefined {
    return outcome.code === 'OK' && readers[type](outcome.payload) ? outcome as Outcome & { readonly payload?: InspectionReplies[T] } : undefined
}
