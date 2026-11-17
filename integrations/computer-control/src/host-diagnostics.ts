/** Finite lifecycle edges only. No identifiers, payloads, paths or raw errors belong here. */
const codes = {
  config: ['CONFIG_LOADED', 'CONFIG_FAILED'],
  authentication: [
    'AUTHENTICATION_ATTENTION',
    'AUTHENTICATED',
    'AUTHENTICATION_FAILED',
  ],
  startup: ['STARTING', 'STARTUP_FAILED'],
  catalogue: [
    'CATALOGUE_RECONCILED',
    'CATALOGUE_RENEWED',
    'CATALOGUE_UNCERTAIN',
    'CATALOGUE_EXPIRED',
  ],
  serving: ['READY', 'TRANSPORT_LOST', 'HOST_FAILED'],
  cleanup: ['STOPPING', 'STOPPED', 'CLEANUP_UNCERTAIN', 'RECOVERY_REQUIRED'],
} as const;

export type HostDiagnostic = {
  [Stage in keyof typeof codes]: {
    readonly stage: Stage;
    readonly code: (typeof codes)[Stage][number];
  };
}[keyof typeof codes];

export interface HostDiagnostics {
  /** Failure is observable without disclosing writer errors. Implementations must never throw. */
  readonly unavailable: boolean;
  emit(event: HostDiagnostic): void;
}

export function formatHostDiagnostic(event: unknown): string {
  // Validate even typed callers: never interpolate a forged stage/code into operator logs.
  if (event !== null && typeof event === 'object') {
    const row = event as Readonly<Record<string, unknown>>;
    if (Object.keys(row).length === 2)
      for (const [stage, allowed] of Object.entries(codes))
        for (const code of allowed)
          if (row['stage'] === stage && row['code'] === code)
            return `computer-control: ${stage} ${code}.\n`;
  }
  return 'computer-control: serving HOST_FAILED. No operation was replayed.\n';
}

/** Bounded by the fixed code set; no queue or per-poll output. Writer failure stops all writes. */
export function createHostDiagnostics(
  write: (line: string) => void,
): HostDiagnostics {
  const seen = new Set<string>();
  let unavailable = false;
  return {
    get unavailable() {
      return unavailable;
    },
    emit(event) {
      if (unavailable) return;
      const line = formatHostDiagnostic(event);
      if (seen.has(line)) return;
      seen.add(line);
      try {
        write(line);
      } catch {
        unavailable = true;
      }
    },
  };
}
