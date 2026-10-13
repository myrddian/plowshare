-- Retained invocation definitions may span many transport chunks.
-- Use Relay's encoded payload ceiling (6 * 50 MiB + 1 KiB) for JSON/metadata headroom.
-- Requests, results, identities and owning receipts retain their existing constraints.
ALTER TABLE relay_tool_invocations DROP CONSTRAINT relay_tool_invocations_binding_check;
ALTER TABLE relay_tool_invocations ADD CONSTRAINT relay_tool_invocations_binding_check
    CHECK (length(binding) >= 1
        AND octet_length(convert_to(binding, 'UTF8')) <= 314573824);
