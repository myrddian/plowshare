# Application server declarations

Replace `REPLACE_WITH_PROVIDER_ACCOUNT` in both JSON files with the authenticated
Python provider account in your private Application copy. Grant that account
project work access in `plowshare.json` (one account entry per handle) and the
project membership policy. Declarations grant no membership by themselves.

Deployment validates these files before activation. `tools.json` provides initial
schemas and authorizes `privacy-scanner` to publish the `network_` catalogue.
`ports.json` grants the collector's schedule/scan Relay ingress and egress. The
project is always derived from this Application; no server restart is needed.
The provider renews its catalogue lease while serving. The Application source is
protected from agent edits; update authority through a reviewed redeployment.

These are bounded declarative capabilities, not arbitrary Spring configuration.
Keep secrets and deployment endpoints in the external collector configuration.
