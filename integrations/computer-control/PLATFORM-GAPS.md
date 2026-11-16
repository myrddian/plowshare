# P0/P1 platform and live-acceptance gaps

These findings do not authorize core changes. The provider remains a public SDK
consumer under [ADR 0001](../../docs/decisions/0001-integration-boundary.md).
Implementation/reviewer approval and live acceptance are recorded separately in
the [delivery graph](IMPLEMENTATION.md).

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
current live-test prerequisite, not evidence of that human account's permissions.
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

- There is no verified supported positive unlocked-state proof in the current
  macOS backend. Console/login flags and old application focus do not establish
  it. Native readiness remains unknown and production input is refused. Do not
  call P3 a complete usable desktop loop until a reviewed readiness mechanism
  and the native acceptance matrix pass.
- Screen capture, Accessibility, event posting and event listening require
  separate permission evidence under the actual packaged helper identity. The
  no-prompt preflight is a diagnostic, not consent or successful native input.
- Compiled input paths and simulated ready-state SDK tests cannot prove Unicode
  insertion into a native app, menu/dialog behavior, lock or permission revocation,
  multi-display capture, local interference detection or held-input cleanup after
  helper death. The [P3 graph](P3-IMPLEMENTATION.md) records these open gates.
- Headful browser backends do not yet join the native broker. Mixed native hosts
  refuse them rather than create independent controllers for the same desktop.
  Isolated headless browser sessions can coexist through the shared SDK provider.

- Personal/Global invocation images are refused by the current admission API.
- Native live capture requires OS screen-capture consent. The current helper's
  no-prompt preflight reported no screen-capture or accessibility permission;
  compilation and denial/protocol tests do not establish native pixel delivery.
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

P1 full live acceptance stays open until a supported authorized project/agent
fixture completes capture → Relay invocation → image admission → retained model
observation → correct visual answer, with provider and server on separate hosts.
