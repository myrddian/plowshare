/** Owner policy is independent of discovery. The server derives account and socket authority. */
export interface ToolScopeConnect {
  readonly project: string;
  readonly scope: string;
  readonly provider: string;
  readonly prefix: string;
  readonly grants: readonly string[];
  readonly agents: readonly string[];
  readonly leaseSeconds: number;
}
/** Use provider/account from this reply as the binding for the existing ToolProvider facade. */
export interface ToolScopeConnection {
  readonly project: string;
  readonly scope: string;
  readonly sourceProvider: string;
  readonly provider: string;
  readonly account: string;
  readonly prefix: string;
  readonly grants: readonly string[];
  readonly agents: readonly string[];
  readonly leaseSeconds: number;
}
export interface ToolScopePayloads {
  'tool.scope.connect': ToolScopeConnect;
  'tool.scope.list': { readonly project: string };
  'tool.scope.disconnect': { readonly project: string; readonly scope: string };
}
export interface ToolScopeReplies {
  'tool.scope.connect': ToolScopeConnection;
  'tool.scope.list': { readonly connections: readonly ToolScopeConnection[] };
  'tool.scope.disconnect': {
    readonly project: string;
    readonly scope: string;
    readonly disconnected: boolean;
  };
}
