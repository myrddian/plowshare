-- PROC enriches a bounded page of individual conversations, including delegated children.
-- The older root_conversation_id index serves a different selection and cannot support this
-- predicate. Keep call facts page-scoped even as unrelated retained accounting history grows.
CREATE INDEX inference_calls_direct_conversation ON inference_calls(conversation_id);
