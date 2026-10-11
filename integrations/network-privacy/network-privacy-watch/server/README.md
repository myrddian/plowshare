# Application server declarations

Set `executionAccount` once in the private Application's root `plowshare.json`.
For a service token use its returned `@service/<token UUID>` principal, not the
account handle. Omitted `account` fields in these files inherit that identity.
The bundled files omit it; `relay-workers.json` is simply `{"version":1}`.
Explicit accounts remain supported, including accounts equal to the default or
separate identities. An omitted account with no manifest identity is refused;
explicit null, empty or malformed values never fall back to another identity.

Grant the owning service handle project work access in manifest account grants and
project membership. Inheritance grants no membership, tool scope or authority by
itself. The [setup guide](../../README.md#separate-the-human-manager-from-the-service-account)
uses public provisioning operations and retains separate human `MANAGER` grants.
Use a server supporting manifest account inheritance before deploying these files.

Deployment validates these files before activation. `tools.json` provides initial
schemas and authorizes `privacy-scanner` to publish the `network_` catalogue.
`ports.json` grants the collector's schedule/scan Relay ingress and egress. The
project is always derived from this Application. `relay-workers.json` explicitly
enrolls background Relay processing under the same service principal, so completed
scans can launch investigations without a startup worker binding. Enrollment refreshes
at the server's Relay configuration interval (30 seconds by default); current work
membership and Relay permissions are checked again during processing. Removing or
changing the declaration stops/replaces enrollment without deleting offsets or jobs.
No server restart is needed.
The provider renews its catalogue lease while serving. The Application source is
protected from agent edits; update authority through a reviewed redeployment.

These are bounded declarative capabilities, not arbitrary Spring configuration.
Keep secrets and deployment endpoints in the external collector configuration.
