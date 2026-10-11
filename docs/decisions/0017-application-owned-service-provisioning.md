# Proposal 0017: Application-owned execution identities

Status: proposed; no provisioning behavior is implemented by this proposal.

## Problem

A project must exist before existing public service-token creation can grant it
scope. The token principal then has to be incorporated into Application source,
requiring a second deployment. SDK orchestration can hide this sequence, but it
cannot make account creation, Application activation and their recovery one
server-owned lifecycle. Packaged schedules also retain the deploying owner's
identity; account inheritance alone does not transfer that ownership.

The user authorized starting setup simplification. ADR 0016 removes repeated
principal configuration now. Single-pass provisioning needs the platform lifecycle
and contracts below, rather than another integration-specific bootstrap shortcut.

## Proposed lifecycle

An optional manifest service declaration requests an Application-owned service
handle and a bounded project role. A server administrator must explicitly authorize
initial provisioning. Ordinary project management must not silently acquire global
account-creation authority. Handle collisions fail closed; matching a handle is
never enough to adopt an unrelated existing account or credential.

First activation reserves a named token UUID and its existing `@service/<UUID>`
principal, tied durably to the Application project. It does not expose a credential
or make a dormant token authenticatable. Inherited declarations and requested
Application execution grants can resolve against that retained identity before
activation. This preserves today's token-principal isolation and stable rotation
instead of assuming all tokens for a handle share one principal.

A separate explicit credential-issuance operation activates that reserved token,
returns its secret once and retains request/token metadata. Lost secret delivery
requires reconciling the original request and rotating the same retained token
UUID; neither deployment nor the collector may create a replacement identity or
replay uncertain work. Client guidance must distinguish the public handle,
execution principal and secret without asking the operator to copy the principal.

Requested service project membership and Application grants are bounded by the
administrator's reviewed declaration. Human manager grants remain explicit.
Schedules may use the declared execution identity only through a separately tested
ownership policy; changing the deployer cannot transfer historical work. Existing
manual executionAccount packages and their retained receipts remain supported.

## Required implementation and verification

Define a typed Application identity service/repository and reserve/activate/revoke
states before implementation. Reuse retained deployment request IDs and account
administration rules. Source/FileStore publication and database reservation are not
one atomic transaction: recovery must reconcile staged ownership and activation,
never leave active source referring to unowned or missing authority. Explain
rollback, redeployment, removal, handle reuse and token expiry explicitly.

Update the shared protocol, every SDK facade and applicable CLI/TUI/GUI controls
for explicit credential issuance and recovery together. Audit token validation so
dormant reservations cannot log in. Audit Application role ceilings, information
visibility, durable receipt ownership, accounting and live revocation on every
call. PostgreSQL-specific tests will be required for reservation uniqueness,
rollback, concurrent activation and retained request replay; default service and
transport tests should mock repository boundaries.

This proposal does not alter current provisioning, token issuance, schedule
ownership or the live Network Privacy Watch installation. It is the follow-up
needed to change deploy → provision → redeploy into deploy → issue credential →
start the external collector.
