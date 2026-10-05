/** Public external-work contracts mirror the protocol records. These observations
 * grant no authority to fetch a URL, select an endpoint or execute a local tool. */
export interface IncomingSource {
  readonly messageId: string;
  readonly role: 'ROLE_USER';
  readonly taskId?: string | null;
  readonly contextId?: string | null;
  readonly parts: readonly {
    readonly text: string;
    readonly mediaType?: 'text/plain' | null;
  }[];
  readonly metadata?: { readonly plowshareCommand?: string | null } | null;
  readonly referenceTaskIds?: readonly string[] | null;
  readonly extensions?: readonly string[] | null;
}
export interface IncomingCommand {
  readonly command: string;
  readonly aliases: readonly string[];
  readonly kind: 'skill' | 'orchestration';
  readonly name: string;
  readonly description: string | null;
  readonly argumentHint: string | null;
  readonly executor: string | null;
  readonly mode: string | null;
  readonly tier: string | null;
  readonly hash: string | null;
  readonly agentVisible: boolean;
}
export interface ExternalMetadata {
  readonly plowshareCommand?: string | null;
  readonly plowshareEnding?: string | null;
  readonly plowshareGenerated?: boolean | null;
  readonly plowshareFinal?: boolean | null;
}
/** Integration parameters are bounded primitives, interpreted by the configured binding. */
export type IntegrationParameter = string | number | boolean;
export type IntegrationRequest = {
  readonly schema: 'plowshare-integration/1';
  readonly binding: string;
} & (
  | {
      readonly operation: 'states.read';
      readonly arguments: {
        readonly entities: readonly string[];
        readonly action?: never;
        readonly parameters?: never;
      };
    }
  | {
      readonly operation: 'actions.execute';
      readonly arguments: {
        readonly entities?: never;
        readonly action: string;
        readonly parameters: Readonly<Record<string, IntegrationParameter>>;
      };
    }
);
interface PartMetadata {
  readonly mediaType?: string | null;
  readonly filename?: string | null;
  readonly metadata?: ExternalMetadata | null;
}
export type ExternalPart = PartMetadata &
  (
    | {
        readonly text: string;
        readonly raw?: never;
        readonly url?: never;
        readonly data?: never;
      }
    | {
        readonly text?: never;
        readonly raw: string;
        readonly url?: never;
        readonly data?: never;
      }
    | {
        readonly text?: never;
        readonly raw?: never;
        readonly url: string;
        readonly data?: never;
      }
    | {
        readonly text?: never;
        readonly raw?: never;
        readonly url?: never;
        readonly data: IntegrationRequest;
      }
  );
export interface ExternalMessage {
  readonly messageId?: string | null;
  readonly role?: 'ROLE_USER' | 'ROLE_AGENT' | null;
  readonly parts: readonly ExternalPart[];
  readonly contextId?: string | null;
  readonly taskId?: string | null;
  readonly extensions?: readonly string[] | null;
  readonly metadata?: ExternalMetadata | null;
}
export interface ActionContext {
  readonly id: string | null;
  readonly parent_id: string | null;
}
export interface IntegrationReading {
  readonly alias: string;
  readonly state: string | null;
  readonly availability:
    | 'available'
    | 'unavailable'
    | 'missing'
    | 'invalid_state'
    | 'invalid_timestamp'
    | 'disconnected'
    | 'stale';
  readonly attributes: Readonly<Record<string, IntegrationParameter>>;
  readonly unit: string | null;
  readonly last_changed: string | null;
  readonly last_updated: string | null;
  readonly epoch: string | null;
  readonly stale: boolean | null;
  readonly observed_at: string | null;
  readonly context: ActionContext | null;
}
export type IntegrationResult =
  | {
      readonly states: Readonly<Record<string, IntegrationReading>>;
      readonly acknowledged?: never;
      readonly diagnostic?: never;
      readonly verification?: never;
      readonly context?: never;
    }
  | {
      readonly states?: never;
      readonly acknowledged: boolean;
      readonly diagnostic?: string | null;
      readonly verification?:
        'unconfirmed' | 'not_observed' | 'observed' | null;
      readonly context?: ActionContext | null;
    }
  | {
      readonly states?: never;
      readonly acknowledged?: never;
      readonly diagnostic: string;
      readonly verification?: never;
      readonly context?: never;
    };
export interface ExternalTask {
  readonly id: string;
  readonly contextId: string;
  readonly status: {
    readonly state:
      | 'TASK_STATE_SUBMITTED'
      | 'TASK_STATE_WORKING'
      | 'TASK_STATE_INPUT_REQUIRED'
      | 'TASK_STATE_AUTH_REQUIRED'
      | 'TASK_STATE_COMPLETED'
      | 'TASK_STATE_FAILED'
      | 'TASK_STATE_CANCELED'
      | 'TASK_STATE_REJECTED';
    readonly message?: ExternalMessage | null;
    readonly timestamp?: string | null;
  };
  readonly artifacts?:
    | readonly {
        readonly artifactId: string;
        readonly name?: string | null;
        readonly description?: string | null;
        readonly parts: readonly ExternalPart[];
        readonly extensions?: readonly string[] | null;
        readonly metadata?: ExternalMetadata | null;
      }[]
    | null;
  readonly history?: readonly ExternalMessage[] | null;
  readonly metadata?: ExternalMetadata | null;
}
export type ExternalResult =
  | ({ readonly task: ExternalTask; readonly message?: never } & {
      readonly states?: never;
      readonly acknowledged?: never;
      readonly diagnostic?: never;
    })
  | ({ readonly task?: never; readonly message: ExternalMessage } & {
      readonly states?: never;
      readonly acknowledged?: never;
      readonly diagnostic?: never;
    })
  | (IntegrationResult & { readonly task?: never; readonly message?: never });
/** Discovery describes a peer; configured endpoints and credentials remain adapter-owned. */
export interface AgentCard {
  readonly name: string;
  readonly description: string;
  readonly version?: string | null;
  readonly defaultInputModes?: readonly string[] | null;
  readonly defaultOutputModes?: readonly string[] | null;
  readonly capabilities?: {
    readonly streaming?: boolean | null;
    readonly pushNotifications?: boolean | null;
    readonly extendedAgentCard?: boolean | null;
    readonly stateTransitionHistory?: boolean | null;
    readonly extensions?:
      | readonly {
          readonly uri: string;
          readonly description?: string | null;
          readonly required?: boolean | null;
        }[]
      | null;
  } | null;
  readonly skills: readonly {
    readonly id: string;
    readonly name: string;
    readonly description: string;
    readonly tags: readonly string[];
    readonly examples?: readonly string[] | null;
    readonly inputModes?: readonly string[] | null;
    readonly outputModes?: readonly string[] | null;
    readonly metadata?: { readonly plowshareCommand: IncomingCommand } | null;
  }[];
  readonly supportedInterfaces?:
    | readonly {
        readonly url: string;
        readonly protocolBinding: string;
        readonly protocolVersion: string;
        readonly tenant?: string | null;
      }[]
    | null;
  readonly provider?: {
    readonly organization: string;
    readonly url: string;
  } | null;
  readonly documentationUrl?: string | null;
  readonly iconUrl?: string | null;
  readonly securitySchemes?: Readonly<Record<string, SecurityScheme>> | null;
  readonly securityRequirements?:
    | readonly {
        readonly schemes: Readonly<
          Record<string, { readonly list: readonly string[] }>
        >;
      }[]
    | null;
}
interface SecurityKinds {
  apiKeySecurityScheme: {
    readonly description?: string | null;
    readonly location: 'header' | 'query' | 'cookie';
    readonly name: string;
  };
  httpAuthSecurityScheme: {
    readonly description?: string | null;
    readonly scheme: string;
    readonly bearerFormat?: string | null;
  };
  oauth2SecurityScheme: {
    readonly description?: string | null;
    readonly flows: OAuthFlows;
    readonly oauth2MetadataUrl?: string | null;
  };
  openIdConnectSecurityScheme: {
    readonly description?: string | null;
    readonly openIdConnectUrl: string;
  };
  mtlsSecurityScheme: { readonly description?: string | null };
}
type OneOf<T> = {
  [K in keyof T]: { readonly [P in K]: T[P] } & {
    readonly [P in Exclude<keyof T, K>]?: never;
  };
}[keyof T];
export type SecurityScheme = OneOf<SecurityKinds>;
interface FlowMetadata {
  readonly refreshUrl?: string | null;
  readonly scopes: Readonly<Record<string, string>>;
}
export type OAuthFlows = OneOf<{
  authorizationCode: FlowMetadata & {
    readonly authorizationUrl: string;
    readonly tokenUrl: string;
    readonly pkceRequired?: boolean | null;
  };
  clientCredentials: FlowMetadata & { readonly tokenUrl: string };
  implicit: FlowMetadata & { readonly authorizationUrl: string };
  password: FlowMetadata & { readonly tokenUrl: string };
  deviceCode: FlowMetadata & {
    readonly deviceAuthorizationUrl: string;
    readonly tokenUrl: string;
  };
}>;
