---
name: personal-capture
description: Save a user-selected thought, link, file or conversation insight to Personal's Inbox with its origin and capture time. Use for requests to save or remember specific material.
mode: DIRECT
---
Resolve the authorized Personal root from available file roots. Read its schema
and saving preferences when present. If access is unavailable, explain the missing
capability rather than writing to another project's In folder.

Identify the selected material. Clarify only if its identity is unclear. Do not
require tags, a category or a plan before capturing. Check for an existing capture
of the same supplied identity to avoid retry duplicates; do not merge merely
similar thoughts. Create a uniquely named capture in In without replacing a file.

Preserve the supplied content and actual origin. Record current capture time with
get_date or another available time source; do not invent a source publication date.
For URLs, distinguish a saved link from content actually acquired and read. For
history, retain conversation and entry/turn references actually inspected. Copy
attachments only through available granted operations; do not fabricate saved bytes.

Use skill_read for references/capture.md if a template helps. Adapt it to the user's
schema. Read back the capture and return its link. This request captures selected
material; it does not process the whole Inbox or schedule maintenance.
