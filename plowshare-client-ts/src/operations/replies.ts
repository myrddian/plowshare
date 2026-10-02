import type { UsageReplies } from './usage.ts'
import type { InformationReplies } from './information-replies.ts'
import type { ObservedJob } from '../binding/job-view.ts'
import type { AdministrativeReplies } from './administrative-replies.ts'
import type { ConversationReplies } from './conversation-replies.ts'
import type { InspectionReplies } from './inspection-replies.ts'
import type { RecordPageView } from './records.ts'
import type { RetrievalReplies } from './retrieval.ts'

/** The server's StartedJob; older accepted replies may omit agent metadata. */
export interface StartedJob { readonly id: string; readonly agent: string; readonly conversation?: string | null }
export type ObservedStartedJob = Pick<StartedJob, 'id'> & Partial<Pick<StartedJob, 'agent' | 'conversation'>>
/** All one-shot WS payload contracts. Refusals and malformed replies remain raw Outcomes. */
export interface Replies extends UsageReplies, InformationReplies, RetrievalReplies, AdministrativeReplies, ConversationReplies, InspectionReplies {
    'conversation.context.count': Readonly<Record<string, unknown>>
    'memory.digest': ObservedStartedJob
    'agent.curate': ObservedStartedJob
    'agent.run': ObservedStartedJob
    'conversation.resume': ObservedStartedJob
    'document.ask': ObservedStartedJob
    'job.status': ObservedJob
    'job.cancel': ObservedJob
    'orchestration.start': { readonly id: string; readonly state: string; readonly requestId: string }
    'orchestration.receipt': { readonly id: string; readonly state: string; readonly requestId: string }
    'orchestration.record': RecordPageView
}
export type ReplyOperation = keyof Replies
