# Computer-control platform and live-acceptance gaps

These findings do not authorize core changes. The provider remains a public SDK
consumer under [ADR 0001](../../docs/decisions/0001-integration-boundary.md).
Implementation/reviewer approval and live acceptance are recorded separately in
the [delivery graph](IMPLEMENTATION.md).

The bootstrap and BETA findings below record earlier P0/P1 attempts, not a fresh
audit of the user's later deployments or credentials. Configured server/model
validation remains deferred. Current native source and no-input evidence are in
the [readiness graph](READINESS-IMPLEMENTATION.md); operational fixture/distribution
evidence is in the [P4 graph](P4-IMPLEMENTATION.md).

## Fresh managed Application bootstrap

A fresh `application.deploy` with `expectedRevision: null` reaches generic
[FrameRouter](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/FrameRouter.java)
project admission before the deployment's first-creator transaction can create
membership. The configured live test caller was refused project access there.
Precreating with `project.create` supplies membership but creates MANAGED source,
which first deployment then refuses to replace.

Owning sources:

- [ServerProjectFrames](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/ws/ServerProjectFrames.java)
  creates a manifest for a new MANAGED project.
- [SourceApplicationDeployments](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/SourceApplicationDeployments.java)
  stages and validates the source through the owning deployment API.
- [ProjectStore.activateDeployment](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/ProjectStore.java)
  refuses first deployment over an existing source and owns first-creator membership.

SDK-only alternatives were checked. Member assignment requires an existing
project; the internal scoped-role repository has no public grant-issue operation.
`agent.define` refuses Application-owned definitions, and `application.file.save`
cannot create missing files or edit `server/` authority configuration. Direct
server-file writes, SQL, source-policy weakening or identity substitution are not
acceptable ways to make the fixture pass.

A separate general platform task should define first-deployment admission as a
creation action while preserving existing deployment/update, FileStore, manifest,
creator and role boundaries. Its acceptance needs a fresh public-WebSocket
deployment, concurrent first-deployment races, creator membership, denied callers,
existing-source refusal, receipt reconciliation and historical compatibility.
No implementation of that platform change is included here.

## Native client scope and workspace authority

An explicitly authorized existing ordinary project can host the provider; SDK
availability does not itself grant project creation or workspace-root authority.
The live operator connection lacked the capability to root a new ordinary test
project. This is not permission to retry as another account.

The public session-private `project.attach` path was also evaluated on the
configured server. It returned an owner/session-bound project identity, but
`agent.define` on that identity failed with an internal instance/durable-host
ownership error. That response is not a successful definition or evidence that
instance projects support this fixture. Preserve the returned identity; never
forge, truncate or transplant it into another session. A separate platform audit
must establish whether private client projects are supported for these definition,
scope and artifact operations before claiming that broader client workflow.

These bootstrap findings block the fresh-fixture full Relay/model live test in
this environment. They do not invalidate the controlled SDK transport tests or
the browser backend's real isolated capture, and do not imply all already
authorized ordinary-project deployments are unavailable.

On continuation, the proposed saved human BETA connection was selected instead
of the operator connection. Its public CLI identity check returned LoginRequired:
the registry entry exists but has no usable authenticated session. This is the
live-test prerequisite at that attempt, not evidence of that human account's permissions.
After private sign-in, the reproducible SDK harness can test the supported
ordinary external-workspace route under that caller's own project-creation
authority. It does not require changing the separate managed-Application
bootstrap contract or retrying a refused action as an unrelated identity.

The user subsequently reported BETA login failures and chose to continue without
live validation. Supplied Desktop diagnostics show its account-connection guard,
closed pending requests and a missing saved session; they do not establish a
server-side credential rejection. Runtime inspection also confirmed BETA was
running an older source revision than PROD. Account/client recovery and server
version alignment are separate operator work; this provider delivery does not
repair logins, upgrade either server or switch the acceptance test to PROD.

## Other explicit limits

P3 native source work does not close the following native operating gaps:

- The reviewed macOS backend now implements a short local confirmation grant
  gated by scoped GUI routing and compositor evidence. Console/login flags and
  old application focus alone still do not establish readiness. Its real
  no-input trial passed unarmed AX discovery, capture denial, stable PENDING,
  revoke and cleanup. Positive compositor/input and the native fault matrix
  remain OPEN; implementation alone does not qualify a usable desktop loop.
- Screen capture, Accessibility, event posting and event listening require
  separate permission evidence under the OS-reported responsible identity for
  the actual helper launch, which may be its SDK/Terminal host. A bundle's code
  signature does not establish that attribution. The
  no-prompt preflight is a diagnostic, not consent or successful native input.
- Compiled input paths and simulated ready-state SDK tests cannot prove Unicode
  insertion into a native app, menu/dialog behavior, lock or permission revocation,
  multi-display capture, local interference detection or held-input cleanup after
  helper death. The [P3 graph](P3-IMPLEMENTATION.md) records these open gates.
- Headful browser backends do not yet join the native broker. Mixed native hosts
  refuse them rather than create independent controllers for the same desktop.
  Isolated headless browser sessions can coexist through the shared SDK provider.

- Personal/Global invocation images are refused by the current admission API.
- Native live capture requires OS screen-capture consent. The final controlled
  P3 launch reported Accessibility, event posting and event listening allowed,
  with Screen Recording false. Earlier sandbox checks had all flags false.
  Neither result establishes permissions for every host identity or native pixel
  delivery. Genuine local confirmation and positive acceptance remain required.
- The direct model endpoint accepted the synthetic PNG fixture. This verifies
  endpoint vision only, not Relay admission, retained observation replay or the
  next model request through Plowshare.
- The exact PNG-only version-2 profile, Spark vision declaration and image-admission
  flag were independently reviewed and loaded successfully on BETA, preserving
  the running image/schema and other routes. A separate one-call tool-free
  synthetic probe in the operator's explicitly owned Personal scope was refused
  on identity/scope mapping. No server image-answer success is claimed. PROD has
  only a staged/dry-run candidate and was not reconfigured or restarted. Private
  test definitions/images and isolated fixture registrations remain on BETA;
  they contain synthetic data and are not production provider installations.
- Rolling image-context selection, general external-tool Personal approvals,
  immediate remote cancellation, hosted typed producers and language parity
  remain the [design's separate platform work](../../docs/computer-control-design-and-plan.md#platform-gaps-and-disposition).
- General journal archival is also a public capability gap. P4 reserves future
  result/publication space and refuses full journals; it does not prune receipts,
  clear physical fences or reset retained image counts. Follow the
  [capacity and recovery procedure](OPERATIONS.md#journal-capacity-and-recovery).

P1 full live acceptance stays open until a supported authorized project/agent
fixture completes capture → Relay invocation → image admission → retained model
observation → correct visual answer, with provider and server on separate hosts.
