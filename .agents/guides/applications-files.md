# Applications, definitions and files

Read this for application deployment/containment, definition source resolution,
FileStores, workspace IO, application file editing or remote providers. Read
[AUTH](auth.md) when changing membership, execution identity, writable areas,
manifest ceilings, service authority or retained operation validity.

## Mental model

Application placement binds source ownership and runtime configuration to a
server project; definition resolution selects the applicable source tier.
Workspace and FileStore providers expose bounded IO on the owning machine,
independently of whether an agent has permission to request that IO.

## Start in code

- [ApplicationDeployments](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/ApplicationDeployments.java) and
  [SourceApplicationDeployments](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/applications/SourceApplicationDeployments.java)
  own staged source deployment and receipt reconciliation.
- [ApplicationResources](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/ApplicationResources.java) and
  [DefinitionResolver](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/DefinitionResolver.java) resolve owning source and definitions.
- [FileStores](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/files/FileStores.java) resolves configured host-local aliases;
  [FileProvider](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/files/FileProvider.java) is the fenced local/remote IO boundary.
- [ApplicationFiles](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/files/ApplicationFiles.java) owns bounded authorized source editing;
  [AtomicFileWrites](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/files/AtomicFileWrites.java) publishes bytes, not authorization.

## Rules that are easy to miss

An application is a server-owned source/runtime boundary, not merely a project
folder. MANAGED, FileStore/external placement and contained child ownership have
different source writers. Use the owning deployment or file operation; do not
make a definition writer another route around a managed package's owner.

Deployment stages outside the transaction and only committed placement becomes
runnable. Keep staged orphans after uncertain commit for reconciliation; their
existence does not prove activation. Schedule-only revisions share deployment's
head fence but do not authorize an arbitrary package rewrite.

Definition precedence, explicit exported names and application ceilings affect
which callers are visible and runnable. A loaded definition is not automatically
a public runnable alias. Personal definitions and contained-child sources keep
their own ownership; neither is a fallback for a refused application mutation.

FileStore aliases are resolved on the owning machine without remote or local-client
fallback. Direct filesystem grants are separate from runtime tool admission.
Preserve canonical containment, symlink/exclusion policy, writable-area fences,
session/provider availability and command cancellation on both server and client.

## Bounded work

Required contract: active deploy/edit/provider/command units need configured
positive finite deadlines covering queueing, waits, IO and result delivery. Short reads,
controls and acceptance use a 2-second default ceiling, normally finishing sooner.
Transfers, staging, validation and commands need appropriate configured deadlines
and budgets, not a 2-second cap on a whole deployment. Bound long-lived session
renewals; retries/nested work consume the earlier deadline without resetting it.
After active expiry, cleanup has a separate positive finite configured drain budget.
On expiry stop new effects and request owned provider/process cancellation; block
late activation or file publication under current content/source/authority fences.
Account physical capacity until the provider/process actually ends. Unknown writes
retain original request identity and receipt/read reconciliation; do not auto-replay.
Current: [RemoteProvider.run](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/files/RemoteProvider.java) passes a command timeout plus reply margin;
this does not bound all source reads, transfers, staging and activation together.

## Legacy traps

Filesystem publication is not a database transaction. `ApplicationFiles.save`
uses a reviewed content revision and rechecks authority; a lost reply must be
reconciled by read, never automatically replayed. Non-cooperating writers can
change files independently. Atomic rename alone does not grant ownership or
serialize all writers. Keep private FileStore configuration fenced from file tools.

## Checks

```sh
./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.applications.SourceApplicationDeploymentsTest' --tests 'io.aeyer.plowshare.server.files.WorkspaceApplicationFilesTest'
./gradlew :plowshare-server:test --tests 'io.aeyer.plowshare.server.files.ConfiguredFileStoresTest' --tests 'io.aeyer.plowshare.server.agents.WorkspaceApplicationResourcesTest'
```

Use `ApplicationWritableAreasTest`, source ownership and contained-application
tests for the affected fence. Default service tests use mocks/temp directories;
only real persistence/locking assertions need full-db. Read
[projects and workspaces](../../docs/manual/05-projects-and-workspaces.md),
[deployment ownership](../../docs/decisions/0005-application-deployment.md) and
[containment](../../docs/decisions/0042-nested-application-containment.md) selectively.
