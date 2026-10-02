import type { Outcome } from '../binding/envelope.ts'
import { bool, count, finite, integer, list, named, noContent, nullable, positive, record, strings, text, type Check } from './wire-checks.ts'

/** Full wire records, kept separate from the smaller frontend display projections. */
export interface ConversationView {
    readonly id: string; readonly project: string | null; readonly maxModelCalls: number | null; readonly modelCallsSpent: number | null
    readonly maxTurns: number | null; readonly noTurnCap: boolean; readonly noBudget: boolean; readonly title: string | null
}
export interface ProjectView { readonly name: string; readonly workspace: string; readonly lent: readonly string[]; readonly exclusions: readonly string[]; readonly machine: string | null; readonly members: readonly string[] }
export interface ProjectMembers { readonly project: string; readonly members: readonly string[] }
export interface AgentView {
    readonly name: string; readonly tools: readonly string[]; readonly calls: readonly string[]; readonly scopes: readonly string[]
    readonly served: boolean; readonly withheld: readonly string[]; readonly bot: boolean; readonly description: string
    readonly preferred: boolean; readonly model: string | null; readonly orchestrations: readonly string[]
}
export interface DefinedAgent { readonly agent: AgentView; readonly restartRequired: boolean }
export interface LifecycleView { readonly id: string; readonly lifecycle: string }
export interface TurnView { readonly ordinal: number; readonly utterance: string; readonly answer: string; readonly ending: string; readonly promptTokens: number | null }
export interface CompactionView { readonly throughOrdinal: number; readonly summary: string }
export interface OpenedView { readonly conversation: string; readonly agent: string }
export interface AskedView { readonly id: string; readonly name: string; readonly arguments: string; readonly length: number; readonly cut: boolean; readonly salient: string | null; readonly opened: OpenedView | null }
export interface EntryView {
    readonly ordinal: number; readonly turnOrdinal: number; readonly kind: string; readonly excerpt: string | null; readonly length: number
    readonly cut: boolean; readonly ejectedAt: string | null; readonly supersededBy: number | null; readonly toolCallId: string | null
    readonly toolCalls: readonly AskedView[]; readonly handle: string | null; readonly recordedAt: string | null; readonly tookMillis: number | null
    readonly dispatch: string | null; readonly wireModel: string | null; readonly completion: string | null
    readonly speaker: string | null; readonly speakerName: string | null; readonly outcome: string | null
}
export interface EntryPageView { readonly entries: readonly EntryView[]; readonly total: number; readonly offset: number; readonly limit: number; readonly through: number; readonly oldest: number | null; readonly more: boolean | null }
export interface TokenCount { readonly tokens: number; readonly basis: string; readonly how: string }
export interface MeasuredTurn { readonly turn: number; readonly promptTokens: number; readonly grewBy: number | null; readonly since: number | null }
export interface Unavailable { readonly component: string; readonly reason: string }
export interface ToolCost { readonly name: string; readonly characters: number }
export interface Prefix {
    readonly agent: string; readonly model: string; readonly systemPromptCharacters: number; readonly toolCharacters: number
    readonly systemPromptTokens: TokenCount; readonly toolTokens: TokenCount; readonly tools: readonly ToolCost[]; readonly contextLength: number | null
}
export interface ContextView {
    readonly sent: number | null; readonly sentAtTurn: number | null; readonly turns: number; readonly turnsMeasured: number
    readonly measuredTurns: readonly MeasuredTurn[]; readonly systemPromptTokens: TokenCount | null; readonly toolTokens: TokenCount | null
    readonly messageTokens: TokenCount | null; readonly cacheHitRate: number | null; readonly unavailable: readonly Unavailable[]; readonly prefix: Prefix | null
}
export interface ProjectionMessage { readonly role: string; readonly content: string }
export interface ProjectionView { readonly agent: string; readonly turn: number | null; readonly systemBlockAsSent: boolean; readonly messages: readonly ProjectionMessage[] }
export interface ConversationReplies {
    'conversation.open': ConversationView
    'conversation.list': readonly ConversationView[]
    'conversation.latest': ConversationView | null
    'conversation.lifecycle': LifecycleView
    'conversation.turns': readonly TurnView[]
    'conversation.compactions': readonly CompactionView[]
    'conversation.chat': EntryPageView
    'conversation.trajectory': EntryPageView
    'conversation.context': ContextView
    'conversation.projection': ProjectionView
    'agent.list': readonly AgentView[]
    'agent.define': DefinedAgent
    'project.list': readonly ProjectView[]
    'project.define': ProjectView
    'project.lend': ProjectView
    'project.unlend': ProjectView
    'project.workspace': ProjectView
    'project.move': null
    'project.forget': null
    'project.member.add': ProjectMembers
    'project.member.remove': ProjectMembers
}
export type ConversationOperation = keyof ConversationReplies
const conversation = record({ id: named, project: nullable(named), maxModelCalls: nullable(count), modelCallsSpent: nullable(count), maxTurns: nullable(count), noTurnCap: bool, noBudget: bool, title: nullable(text) })
const project = record({ name: named, workspace: named, lent: strings, exclusions: strings, machine: nullable(text), members: strings })
const members = record({ project: named, members: strings })
const agent = record({ name: named, tools: strings, calls: strings, scopes: strings, served: bool, withheld: strings, bot: bool, description: text, preferred: bool, model: nullable(text), orchestrations: strings })
const opened = record({ conversation: named, agent: named })
const asked = record({ id: text, name: text, arguments: text, length: count, cut: bool, salient: nullable(text), opened: nullable(opened) })
const entry = record({ ordinal: positive, turnOrdinal: count, kind: named, excerpt: nullable(text), length: count, cut: bool, ejectedAt: nullable(named), supersededBy: nullable(positive),
    toolCallId: nullable(text), toolCalls: list(asked), handle: nullable(text), recordedAt: nullable(named), tookMillis: nullable(count), dispatch: nullable(text), wireModel: nullable(text), completion: nullable(text),
    speaker: nullable(text), speakerName: nullable(text), outcome: nullable(text) })
const page = record({ entries: list(entry), total: count, offset: count, limit: positive, through: count, oldest: nullable(positive), more: nullable(bool) }, row => {
    const entries = row['entries'] as Record<string, unknown>[]
    const ordinals = entries.map(each => each['ordinal'] as number)
    return new Set(ordinals).size === ordinals.length && ordinals.every(ordinal => ordinal <= (row['through'] as number))
        && entries.length <= (row['limit'] as number) && entries.length <= (row['total'] as number)
        && row['oldest'] === (ordinals.length === 0 ? null : Math.min(...ordinals))
})
const tokens = record({ tokens: count, basis: named, how: named })
const measured = record({ turn: positive, promptTokens: positive, grewBy: nullable(integer), since: nullable(positive) })
const prefix = record({ agent: named, model: named, systemPromptCharacters: count, toolCharacters: count, systemPromptTokens: tokens, toolTokens: tokens,
    tools: list(record({ name: text, characters: count })), contextLength: nullable(positive) })
const context = record({ sent: nullable(positive), sentAtTurn: nullable(positive), turns: count, turnsMeasured: count, measuredTurns: list(measured), systemPromptTokens: nullable(tokens), toolTokens: nullable(tokens),
    messageTokens: nullable(tokens), cacheHitRate: nullable(finite), unavailable: list(record({ component: named, reason: text })), prefix: nullable(prefix) })
const readers = {
    'conversation.open': conversation, 'conversation.list': list(conversation), 'conversation.latest': value => noContent(value) || conversation(value),
    'conversation.lifecycle': record({ id: named, lifecycle: named }), 'conversation.turns': list(record({ ordinal: positive, utterance: text, answer: text, ending: named, promptTokens: nullable(positive) })),
    'conversation.compactions': list(record({ throughOrdinal: count, summary: text })), 'conversation.chat': page, 'conversation.trajectory': page, 'conversation.context': context,
    'conversation.projection': record({ agent: named, turn: nullable(positive), systemBlockAsSent: bool, messages: list(record({ role: named, content: text })) }),
    'agent.list': list(agent), 'agent.define': record({ agent, restartRequired: bool }),
    'project.list': list(project), 'project.define': project, 'project.lend': project, 'project.unlend': project, 'project.workspace': project,
    'project.move': noContent, 'project.forget': noContent, 'project.member.add': members, 'project.member.remove': members,
} satisfies Record<ConversationOperation, Check>
export const CONVERSATION_OPERATIONS = Object.keys(readers) as readonly ConversationOperation[]
export function isConversationOperation(type: string): type is ConversationOperation { return Object.hasOwn(readers, type) }
/** Preserve raw replies, including the explicit absence of a latest conversation and CREATED definitions. */
export function conversationReply<T extends ConversationOperation>(type: T, outcome: Outcome): (Outcome & { readonly payload?: ConversationReplies[T] }) | undefined {
    const validCode = type === 'project.move' || type === 'project.forget' ? outcome.code === 'NO_CONTENT'
        : outcome.code === 'OK' || (type === 'agent.define' && outcome.code === 'CREATED')
    return validCode && readers[type](outcome.payload) ? outcome as Outcome & { readonly payload?: ConversationReplies[T] } : undefined
}
