---
name: house-evidence
description: Read selected house evidence and explain availability and delivery status.
agent: house_coordinator
mode: NEW
allowed-tools: relay_publish
---
For a direct native message or ordinary account-owned TURN, use the
house_coordinator's selected Relay request and configured topic. Finish the turn
while the correlated native reply is pending. An append receipt is not remote
completion or permission to send again. Explain missing, stale or refused evidence.
UNKNOWN actions require operator inspection and must not be repeated automatically.
Event-started orchestration instead reuses its captured authorized input.

This package supplies instructions. It does not execute helper files or grant new
tools. Adapter JavaScript runs in the separate integration runtime; model-called
command helpers would require an executor with run and a permitted environment.
