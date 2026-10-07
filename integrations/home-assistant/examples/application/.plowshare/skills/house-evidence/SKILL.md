---
name: house-evidence
description: Read selected house evidence and explain availability and delivery status.
agent: house_coordinator
mode: NEW
allowed-tools: outgoing_peers outgoing_send outgoing_read
---
Use the house_coordinator's selected HA read message and configured peer. Retain
the request UUID and resulting work ID. Pending receipts are not permission to
send again. Follow existing work with outgoing_read and explain missing or stale
evidence. UNKNOWN actions require inspection and must not be automatically repeated.

This package supplies instructions. It does not execute helper files or grant new
tools. Adapter JavaScript runs in the separate integration runtime; model-called
command helpers would require an executor with run and a permitted environment.
