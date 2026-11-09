# Model response guards

Status: accepted for implementation.

A provider can report a successful stop while returning its native tool-call
envelope as plain text. Any dispatcher consumer can mistake that text for a
complete answer. An SDK integration can validate its own assessment, but it
cannot inspect every served route or withhold streamed tokens before delivery.

The existing deployment-owned model profile selects a pure JavaScript filter.
The dispatcher checks the assembled response on the actual wire model before
answer delivery or tool dispatch. It buffers guarded streams and preserves the
provider's original usage. The disclosure policy still governs what can be
retained in diagnostics. The script can only detect a failure. It cannot
rewrite messages, grant a capability or turn text into a structured call.

The turn loop owns a single budgeted, tool-free regeneration from the same
history. Completed results remain attached to their original tool call IDs.
A required-tool turn keeps its existing completion contract and ends with an
explicit failure. Replay fixtures assert both the malformed response boundary
and that a previously executed tool is invoked once. Configuration is read on
startup. There are no public operation or persistence schema changes.
