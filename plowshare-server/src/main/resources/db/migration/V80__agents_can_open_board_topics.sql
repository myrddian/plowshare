-- Agents granted board: true use the same conversation delivery and opener seats as bots.
ALTER TABLE board_topics DROP CONSTRAINT board_topics_opener_kind_is_known;
ALTER TABLE board_topics ADD CONSTRAINT board_topics_opener_kind_is_known
    CHECK (opener_kind IN ('person', 'bot', 'agent', 'member'));
ALTER TABLE board_topics DROP CONSTRAINT board_topics_a_bot_opens_from_a_conversation;
ALTER TABLE board_topics ADD CONSTRAINT board_topics_a_definition_opens_from_a_conversation
    CHECK ((opener_kind IN ('bot', 'agent')) = (origin_conversation IS NOT NULL));
